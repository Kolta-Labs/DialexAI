package steering

import (
	"crypto/sha256"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// RemoteSyncer manages fetching and caching external steering documents.
type RemoteSyncer struct {
	cacheDir string
	client   *http.Client
}

// NewRemoteSyncer creates a remote steering syncer.
func NewRemoteSyncer() *RemoteSyncer {
	home, _ := os.UserHomeDir()
	cache := filepath.Join(home, ".artix", "cache", "steering")
	_ = os.MkdirAll(cache, 0755)

	return &RemoteSyncer{
		cacheDir: cache,
		client: &http.Client{
			Timeout: 15 * time.Second,
		},
	}
}

// SyncSource synchronizes an external source and returns its steering rules.
func (s *RemoteSyncer) SyncSource(src ExternalSource) ([]RuleFile, error) {
	switch src.Type {
	case SourceLocalSibling:
		return s.syncLocalSibling(src)
	case SourceRemoteHTTP:
		return s.syncHTTP(src)
	case SourceRemoteGit:
		return s.syncGit(src)
	default:
		return nil, fmt.Errorf("unsupported external source type: %s", src.Type)
	}
}

func (s *RemoteSyncer) syncLocalSibling(src ExternalSource) ([]RuleFile, error) {
	if _, err := os.Stat(src.Path); err != nil {
		return nil, fmt.Errorf("sibling path does not exist: %s", src.Path)
	}

	var rules []RuleFile
	err := filepath.WalkDir(src.Path, func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() || filepath.Ext(d.Name()) != ".md" {
			return nil
		}
		content, err := os.ReadFile(path)
		if err != nil || len(content) == 0 {
			return nil
		}
		hash := fmt.Sprintf("%x", sha256.Sum256(content))
		rules = append(rules, RuleFile{
			ID:         fmt.Sprintf("ext_%s_%s", src.ID, strings.TrimSuffix(d.Name(), ".md")),
			Name:       d.Name(),
			RelPath:    path,
			SourceType: SourceLocalSibling,
			Content:    string(content),
			Hash:       hash,
		})
		return nil
	})

	return rules, err
}

func (s *RemoteSyncer) syncHTTP(src ExternalSource) ([]RuleFile, error) {
	req, err := http.NewRequest(http.MethodGet, src.URL, nil)
	if err != nil {
		return nil, fmt.Errorf("invalid URL %s: %w", src.URL, err)
	}

	resp, err := s.client.Do(req)
	if err != nil {
		// Fallback to cache if offline
		return s.readFromCache(src.ID)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return s.readFromCache(src.ID)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	// Persist to cache
	cachedFile := filepath.Join(s.cacheDir, fmt.Sprintf("%s.md", src.ID))
	_ = os.WriteFile(cachedFile, body, 0644)

	hash := fmt.Sprintf("%x", sha256.Sum256(body))
	return []RuleFile{
		{
			ID:         fmt.Sprintf("ext_%s", src.ID),
			Name:       src.Name,
			RelPath:    src.URL,
			SourceType: SourceRemoteHTTP,
			Content:    string(body),
			Hash:       hash,
		},
	}, nil
}

func (s *RemoteSyncer) syncGit(src ExternalSource) ([]RuleFile, error) {
	targetDir := filepath.Join(s.cacheDir, "git", src.ID)
	_ = os.MkdirAll(filepath.Dir(targetDir), 0755)

	if _, err := os.Stat(filepath.Join(targetDir, ".git")); err == nil {
		// Repo exists, pull latest
		cmd := exec.Command("git", "-C", targetDir, "pull", "--ff-only")
		_ = cmd.Run()
	} else {
		// Clone shallow
		branch := src.Branch
		if branch == "" {
			branch = "main"
		}
		cmd := exec.Command("git", "clone", "--depth", "1", "--branch", branch, src.URL, targetDir)
		if err := cmd.Run(); err != nil {
			return nil, fmt.Errorf("failed to clone git standards repo %s: %w", src.URL, err)
		}
	}

	// Ingest markdown from cloned repo
	srcCopy := src
	srcCopy.Path = targetDir
	return s.syncLocalSibling(srcCopy)
}

func (s *RemoteSyncer) readFromCache(sourceID string) ([]RuleFile, error) {
	cachedFile := filepath.Join(s.cacheDir, fmt.Sprintf("%s.md", sourceID))
	content, err := os.ReadFile(cachedFile)
	if err != nil {
		return nil, fmt.Errorf("no cached steering available for %s: %w", sourceID, err)
	}

	hash := fmt.Sprintf("%x", sha256.Sum256(content))
	return []RuleFile{
		{
			ID:         fmt.Sprintf("ext_%s", sourceID),
			Name:       sourceID,
			RelPath:    cachedFile,
			SourceType: SourceRemoteHTTP,
			Content:    string(content),
			Hash:       hash,
		},
	}, nil
}
