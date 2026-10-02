package api

import (
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"socratix/pkg/graph"
)

// handleGetGraph returns the active knowledge graph for a project.
func (s *Server) handleGetGraph(w http.ResponseWriter, r *http.Request) {
	if s.GraphStore == nil {
		writeJSON(w, http.StatusOK, &graph.Graph{Nodes: []*graph.Node{}, Edges: []*graph.Edge{}})
		return
	}
	projectID := r.PathValue("id")
	minWeight := 0.1
	if mwStr := r.URL.Query().Get("min_weight"); mwStr != "" {
		if mw, err := strconv.ParseFloat(mwStr, 64); err == nil {
			minWeight = mw
		}
	}

	g, err := s.GraphStore.GetActiveGraph(r.Context(), projectID, minWeight, time.Now().UTC())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to get graph: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, g)
}

// handleSearchGraph performs FTS5 search on graph nodes.
func (s *Server) handleSearchGraph(w http.ResponseWriter, r *http.Request) {
	if s.GraphStore == nil {
		writeJSON(w, http.StatusOK, []*graph.Node{})
		return
	}
	projectID := r.PathValue("id")
	query := r.URL.Query().Get("q")
	if query == "" {
		writeJSON(w, http.StatusOK, []*graph.Node{})
		return
	}
	limit := 10
	if limStr := r.URL.Query().Get("limit"); limStr != "" {
		if l, err := strconv.Atoi(limStr); err == nil && l > 0 {
			limit = l
		}
	}

	nodes, err := s.GraphStore.SearchFTS(r.Context(), projectID, query, limit, time.Now().UTC())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to search graph: "+err.Error())
		return
	}
	if nodes == nil {
		nodes = []*graph.Node{}
	}
	writeJSON(w, http.StatusOK, nodes)
}

// handleUpsertNode creates or updates a node in the graph.
func (s *Server) handleUpsertNode(w http.ResponseWriter, r *http.Request) {
	if s.GraphStore == nil {
		writeError(w, http.StatusServiceUnavailable, "graph store not initialized")
		return
	}
	projectID := r.PathValue("id")
	var n graph.Node
	if err := json.NewDecoder(r.Body).Decode(&n); err != nil {
		writeError(w, http.StatusBadRequest, "invalid node json: "+err.Error())
		return
	}
	if n.ProjectID == "" {
		n.ProjectID = projectID
	}
	if n.ID == "" {
		writeError(w, http.StatusBadRequest, "node id is required")
		return
	}

	if err := s.GraphStore.UpsertNode(r.Context(), &n); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to upsert node: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, &n)
}

// handleDeleteNode removes a node by ID.
func (s *Server) handleDeleteNode(w http.ResponseWriter, r *http.Request) {
	if s.GraphStore == nil {
		writeError(w, http.StatusServiceUnavailable, "graph store not initialized")
		return
	}
	nodeID := r.PathValue("nodeId")
	if nodeID == "" {
		writeError(w, http.StatusBadRequest, "nodeId is required")
		return
	}

	if err := s.GraphStore.DeleteNode(r.Context(), nodeID); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to delete node: "+err.Error())
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// handleUpsertEdge creates or updates a directed edge.
func (s *Server) handleUpsertEdge(w http.ResponseWriter, r *http.Request) {
	if s.GraphStore == nil {
		writeError(w, http.StatusServiceUnavailable, "graph store not initialized")
		return
	}
	projectID := r.PathValue("id")
	var e graph.Edge
	if err := json.NewDecoder(r.Body).Decode(&e); err != nil {
		writeError(w, http.StatusBadRequest, "invalid edge json: "+err.Error())
		return
	}
	if e.ProjectID == "" {
		e.ProjectID = projectID
	}
	if e.SourceID == "" || e.TargetID == "" {
		writeError(w, http.StatusBadRequest, "source_id and target_id are required")
		return
	}

	if err := s.GraphStore.UpsertEdge(r.Context(), &e); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to upsert edge: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, &e)
}

// handleTriggerDecay runs maintenance pruning on stale graph nodes.
func (s *Server) handleTriggerDecay(w http.ResponseWriter, r *http.Request) {
	if s.GraphStore == nil {
		writeError(w, http.StatusServiceUnavailable, "graph store not initialized")
		return
	}
	threshold := 0.05
	maxStaleSecs := int64(180 * 86400) // 180 days default
	affected, err := s.GraphStore.RunMaintenanceDecay(r.Context(), threshold, maxStaleSecs, time.Now().UTC())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "decay maintenance failed: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"pruned_count": affected})
}
