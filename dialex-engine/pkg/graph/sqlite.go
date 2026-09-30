package graph

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"math"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

type SQLiteGraphStore struct {
	db *sql.DB
	mu sync.RWMutex
}

// OpenSQLite opens or creates the SQLite database at path and applies schema.
func OpenSQLite(path string) (*SQLiteGraphStore, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, fmt.Errorf("failed to open sqlite graph database: %w", err)
	}

	// Pragmas for optimal local concurrent performance
	pragmas := []string{
		"PRAGMA journal_mode = WAL;",
		"PRAGMA synchronous = NORMAL;",
		"PRAGMA foreign_keys = ON;",
		"PRAGMA busy_timeout = 5000;",
	}
	for _, p := range pragmas {
		if _, err := db.Exec(p); err != nil {
			db.Close()
			return nil, fmt.Errorf("failed pragma %q: %w", p, err)
		}
	}

	store := &SQLiteGraphStore{db: db}
	if err := store.initSchema(); err != nil {
		db.Close()
		return nil, fmt.Errorf("failed to init schema: %w", err)
	}

	return store, nil
}

func (s *SQLiteGraphStore) initSchema() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	schema := `
	CREATE TABLE IF NOT EXISTS graph_nodes (
		id TEXT PRIMARY KEY NOT NULL,
		project_id TEXT NOT NULL,
		type TEXT NOT NULL,
		title TEXT NOT NULL,
		content TEXT NOT NULL,
		weight REAL NOT NULL DEFAULT 1.0,
		decay_half_life_secs INTEGER NOT NULL DEFAULT 2592000,
		created_at INTEGER NOT NULL,
		last_accessed_at INTEGER NOT NULL,
		metadata_json TEXT NOT NULL DEFAULT '{}'
	);

	CREATE INDEX IF NOT EXISTS idx_nodes_proj_type ON graph_nodes(project_id, type);
	CREATE INDEX IF NOT EXISTS idx_nodes_accessed ON graph_nodes(last_accessed_at);

	CREATE VIRTUAL TABLE IF NOT EXISTS graph_nodes_fts USING fts5(
		title,
		content,
		content='graph_nodes',
		content_rowid='rowid',
		tokenize='porter unicode61'
	);

	CREATE TRIGGER IF NOT EXISTS trg_nodes_ai AFTER INSERT ON graph_nodes BEGIN
		INSERT INTO graph_nodes_fts(rowid, title, content) VALUES (new.rowid, new.title, new.content);
	END;

	CREATE TRIGGER IF NOT EXISTS trg_nodes_ad AFTER DELETE ON graph_nodes BEGIN
		INSERT INTO graph_nodes_fts(graph_nodes_fts, rowid, title, content) VALUES('delete', old.rowid, old.title, old.content);
	END;

	CREATE TRIGGER IF NOT EXISTS trg_nodes_au AFTER UPDATE ON graph_nodes BEGIN
		INSERT INTO graph_nodes_fts(graph_nodes_fts, rowid, title, content) VALUES('delete', old.rowid, old.title, old.content);
		INSERT INTO graph_nodes_fts(rowid, title, content) VALUES (new.rowid, new.title, new.content);
	END;

	CREATE TABLE IF NOT EXISTS graph_edges (
		id TEXT PRIMARY KEY NOT NULL,
		project_id TEXT NOT NULL,
		source_id TEXT NOT NULL,
		target_id TEXT NOT NULL,
		relation TEXT NOT NULL,
		strength REAL NOT NULL DEFAULT 0.5,
		created_at INTEGER NOT NULL,
		last_reinforced_at INTEGER NOT NULL,
		FOREIGN KEY (source_id) REFERENCES graph_nodes(id) ON DELETE CASCADE,
		FOREIGN KEY (target_id) REFERENCES graph_nodes(id) ON DELETE CASCADE,
		CONSTRAINT uq_edge UNIQUE (source_id, target_id, relation)
	);

	CREATE INDEX IF NOT EXISTS idx_edges_source ON graph_edges(source_id);
	CREATE INDEX IF NOT EXISTS idx_edges_target ON graph_edges(target_id);
	CREATE INDEX IF NOT EXISTS idx_edges_project ON graph_edges(project_id);
	`
	_, err := s.db.Exec(schema)
	return err
}

// ComputeDecay calculates W(t) = W0 * 2^(-delta_t / half_life).
func ComputeDecay(baseWeight float64, lastAccessedSecs, halfLifeSecs, nowSecs int64) float64 {
	if baseWeight <= 0 {
		return 0.0
	}
	if halfLifeSecs <= 0 {
		halfLifeSecs = 2592000 // default 30 days
	}
	delta := float64(nowSecs - lastAccessedSecs)
	if delta <= 0 {
		return baseWeight
	}
	decay := baseWeight * math.Pow(2.0, -delta/float64(halfLifeSecs))
	if decay < 0.001 {
		return 0.0
	}
	return math.Round(decay*1000) / 1000
}

func (s *SQLiteGraphStore) UpsertNode(ctx context.Context, n *Node) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	metaJSON, err := json.Marshal(n.Metadata)
	if err != nil {
		metaJSON = []byte("{}")
	}

	if n.DecayHalfLifeSecs <= 0 {
		n.DecayHalfLifeSecs = 2592000
	}
	if n.Weight <= 0 {
		n.Weight = 1.0
	}
	if n.CreatedAt <= 0 {
		n.CreatedAt = time.Now().Unix()
	}
	if n.LastAccessedAt <= 0 {
		n.LastAccessedAt = n.CreatedAt
	}

	query := `
	INSERT INTO graph_nodes (id, project_id, type, title, content, weight, decay_half_life_secs, created_at, last_accessed_at, metadata_json)
	VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	ON CONFLICT(id) DO UPDATE SET
		title = excluded.title,
		content = excluded.content,
		type = excluded.type,
		weight = excluded.weight,
		decay_half_life_secs = excluded.decay_half_life_secs,
		last_accessed_at = excluded.last_accessed_at,
		metadata_json = excluded.metadata_json;
	`
	_, err = s.db.ExecContext(ctx, query,
		n.ID, n.ProjectID, string(n.Type), n.Title, n.Content, n.Weight,
		n.DecayHalfLifeSecs, n.CreatedAt, n.LastAccessedAt, string(metaJSON),
	)
	return err
}

func (s *SQLiteGraphStore) TouchNode(ctx context.Context, id string, now time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	query := `UPDATE graph_nodes SET weight = 1.0, last_accessed_at = ? WHERE id = ?;`
	_, err := s.db.ExecContext(ctx, query, now.Unix(), id)
	return err
}

func (s *SQLiteGraphStore) GetNode(ctx context.Context, id string, now time.Time) (*Node, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	query := `
	SELECT id, project_id, type, title, content, weight, decay_half_life_secs, created_at, last_accessed_at, metadata_json
	FROM graph_nodes WHERE id = ?;
	`
	row := s.db.QueryRowContext(ctx, query, id)
	var n Node
	var typeStr, metaStr string
	if err := row.Scan(
		&n.ID, &n.ProjectID, &typeStr, &n.Title, &n.Content, &n.Weight,
		&n.DecayHalfLifeSecs, &n.CreatedAt, &n.LastAccessedAt, &metaStr,
	); err != nil {
		return nil, err
	}
	n.Type = NodeType(typeStr)
	n.CurrentWeight = ComputeDecay(n.Weight, n.LastAccessedAt, n.DecayHalfLifeSecs, now.Unix())
	_ = json.Unmarshal([]byte(metaStr), &n.Metadata)
	return &n, nil
}

func (s *SQLiteGraphStore) DeleteNode(ctx context.Context, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	_, err := s.db.ExecContext(ctx, `DELETE FROM graph_nodes WHERE id = ?;`, id)
	return err
}

func (s *SQLiteGraphStore) UpsertEdge(ctx context.Context, e *Edge) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if e.Strength <= 0 {
		e.Strength = 0.5
	}
	if e.CreatedAt <= 0 {
		e.CreatedAt = time.Now().Unix()
	}
	if e.LastReinforcedAt <= 0 {
		e.LastReinforcedAt = e.CreatedAt
	}

	query := `
	INSERT INTO graph_edges (id, project_id, source_id, target_id, relation, strength, created_at, last_reinforced_at)
	VALUES (?, ?, ?, ?, ?, ?, ?, ?)
	ON CONFLICT(source_id, target_id, relation) DO UPDATE SET
		strength = excluded.strength,
		last_reinforced_at = excluded.last_reinforced_at;
	`
	_, err := s.db.ExecContext(ctx, query,
		e.ID, e.ProjectID, e.SourceID, e.TargetID, string(e.Relation), e.Strength, e.CreatedAt, e.LastReinforcedAt,
	)
	return err
}

// ReinforceEdge strengthens an edge via Hebbian co-reference update: ΔS = eta * (1.0 - S).
func (s *SQLiteGraphStore) ReinforceEdge(ctx context.Context, projectID, sourceID, targetID string, rel RelationType, eta float64, now time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if eta <= 0 {
		eta = 0.15
	}
	query := `
	SELECT id, strength FROM graph_edges
	WHERE source_id = ? AND target_id = ? AND relation = ?;
	`
	var id string
	var currentStrength float64
	err := s.db.QueryRowContext(ctx, query, sourceID, targetID, string(rel)).Scan(&id, &currentStrength)
	if err == sql.ErrNoRows {
		// Create new edge with default strength
		newID := fmt.Sprintf("%s-%s-%s", sourceID[:8], targetID[:8], rel)
		insertQ := `
		INSERT INTO graph_edges (id, project_id, source_id, target_id, relation, strength, created_at, last_reinforced_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?);
		`
		_, err = s.db.ExecContext(ctx, insertQ, newID, projectID, sourceID, targetID, string(rel), 0.5+eta*0.5, now.Unix(), now.Unix())
		return err
	} else if err != nil {
		return err
	}

	newStrength := currentStrength + eta*(1.0-currentStrength)
	if newStrength > 1.0 {
		newStrength = 1.0
	}
	updateQ := `UPDATE graph_edges SET strength = ?, last_reinforced_at = ? WHERE id = ?;`
	_, err = s.db.ExecContext(ctx, updateQ, newStrength, now.Unix(), id)
	return err
}

func (s *SQLiteGraphStore) SearchFTS(ctx context.Context, projectID, query string, limit int, now time.Time) ([]*Node, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if limit <= 0 {
		limit = 10
	}
	sqlQuery := `
	SELECT n.id, n.project_id, n.type, n.title, n.content, n.weight, n.decay_half_life_secs, n.created_at, n.last_accessed_at, n.metadata_json
	FROM graph_nodes_fts fts
	JOIN graph_nodes n ON n.rowid = fts.rowid
	WHERE graph_nodes_fts MATCH ?
	  AND (n.project_id = ? OR ? = '')
	ORDER BY bm25(graph_nodes_fts) ASC
	LIMIT ?;
	`
	rows, err := s.db.QueryContext(ctx, sqlQuery, query, projectID, projectID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var results []*Node
	for rows.Next() {
		var n Node
		var typeStr, metaStr string
		if err := rows.Scan(
			&n.ID, &n.ProjectID, &typeStr, &n.Title, &n.Content, &n.Weight,
			&n.DecayHalfLifeSecs, &n.CreatedAt, &n.LastAccessedAt, &metaStr,
		); err != nil {
			return nil, err
		}
		n.Type = NodeType(typeStr)
		n.CurrentWeight = ComputeDecay(n.Weight, n.LastAccessedAt, n.DecayHalfLifeSecs, now.Unix())
		_ = json.Unmarshal([]byte(metaStr), &n.Metadata)
		results = append(results, &n)
	}
	return results, rows.Err()
}

func (s *SQLiteGraphStore) GetActiveGraph(ctx context.Context, projectID string, minWeight float64, now time.Time) (*Graph, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	nodeQuery := `
	SELECT id, project_id, type, title, content, weight, decay_half_life_secs, created_at, last_accessed_at, metadata_json
	FROM graph_nodes
	WHERE (project_id = ? OR ? = '')
	ORDER BY created_at DESC;
	`
	rows, err := s.db.QueryContext(ctx, nodeQuery, projectID, projectID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	activeNodesMap := make(map[string]*Node)
	var nodes []*Node
	for rows.Next() {
		var n Node
		var typeStr, metaStr string
		if err := rows.Scan(
			&n.ID, &n.ProjectID, &typeStr, &n.Title, &n.Content, &n.Weight,
			&n.DecayHalfLifeSecs, &n.CreatedAt, &n.LastAccessedAt, &metaStr,
		); err != nil {
			return nil, err
		}
		n.Type = NodeType(typeStr)
		n.CurrentWeight = ComputeDecay(n.Weight, n.LastAccessedAt, n.DecayHalfLifeSecs, now.Unix())
		_ = json.Unmarshal([]byte(metaStr), &n.Metadata)

		if n.CurrentWeight >= minWeight {
			nodes = append(nodes, &n)
			activeNodesMap[n.ID] = &n
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}

	edgeQuery := `
	SELECT id, project_id, source_id, target_id, relation, strength, created_at, last_reinforced_at
	FROM graph_edges
	WHERE (project_id = ? OR ? = '');
	`
	edgeRows, err := s.db.QueryContext(ctx, edgeQuery, projectID, projectID)
	if err != nil {
		return nil, err
	}
	defer edgeRows.Close()

	var edges []*Edge
	for edgeRows.Next() {
		var e Edge
		var relStr string
		if err := edgeRows.Scan(
			&e.ID, &e.ProjectID, &e.SourceID, &e.TargetID, &relStr, &e.Strength, &e.CreatedAt, &e.LastReinforcedAt,
		); err != nil {
			return nil, err
		}
		e.Relation = RelationType(relStr)
		// Only include edges connecting active nodes
		if _, ok1 := activeNodesMap[e.SourceID]; ok1 {
			if _, ok2 := activeNodesMap[e.TargetID]; ok2 {
				edges = append(edges, &e)
			}
		}
	}
	if err := edgeRows.Err(); err != nil {
		return nil, err
	}

	return &Graph{Nodes: nodes, Edges: edges}, nil
}

func (s *SQLiteGraphStore) RunMaintenanceDecay(ctx context.Context, minThreshold float64, maxStaleSecs int64, now time.Time) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	cutoff := now.Unix() - maxStaleSecs
	query := `
	DELETE FROM graph_nodes
	WHERE last_accessed_at < ?
	  AND weight < ?;
	`
	res, err := s.db.ExecContext(ctx, query, cutoff, minThreshold)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

func (s *SQLiteGraphStore) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.db.Close()
}
