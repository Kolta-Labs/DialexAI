package mcp

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHTTPClient_FullLifecycle(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req JSONRPCRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), 400)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		switch req.Method {
		case "initialize":
			res := InitializeResult{
				ProtocolVersion: "2024-11-05",
				ServerInfo: ServerInfo{
					Name:    "mock-mcp-server",
					Version: "1.0",
				},
			}
			resBytes, _ := json.Marshal(res)
			_ = json.NewEncoder(w).Encode(JSONRPCResponse{
				JSONRPC: "2.0",
				ID:      req.ID,
				Result:  resBytes,
			})
		case "tools/list":
			tools := []MCPTool{
				{
					Name:        "query_database",
					Description: "Executes a read-only SQL query",
				},
			}
			resBytes, _ := json.Marshal(map[string]interface{}{"tools": tools})
			_ = json.NewEncoder(w).Encode(JSONRPCResponse{
				JSONRPC: "2.0",
				ID:      req.ID,
				Result:  resBytes,
			})
		case "tools/call":
			callRes := ToolCallResult{
				Content: []ToolContent{
					{
						Type: "text",
						Text: `{"rows": [{"id": 1, "name": "Alice"}]}`,
					},
				},
			}
			resBytes, _ := json.Marshal(callRes)
			_ = json.NewEncoder(w).Encode(JSONRPCResponse{
				JSONRPC: "2.0",
				ID:      req.ID,
				Result:  resBytes,
			})
		case "resources/list":
			resources := []MCPResource{
				{
					URI:  "jira://PROJ-123",
					Name: "PROJ-123: Fix payment bug",
				},
			}
			resBytes, _ := json.Marshal(map[string]interface{}{"resources": resources})
			_ = json.NewEncoder(w).Encode(JSONRPCResponse{
				JSONRPC: "2.0",
				ID:      req.ID,
				Result:  resBytes,
			})
		case "resources/read":
			contents := []ResourceContent{
				{
					URI:  "jira://PROJ-123",
					Text: "Issue details: payment gateway timeout handling",
				},
			}
			resBytes, _ := json.Marshal(map[string]interface{}{"contents": contents})
			_ = json.NewEncoder(w).Encode(JSONRPCResponse{
				JSONRPC: "2.0",
				ID:      req.ID,
				Result:  resBytes,
			})
		default:
			http.Error(w, "unknown method", 404)
		}
	}))
	defer server.Close()

	client := NewHTTPClient(server.URL, map[string]string{
		"X-Test-Auth": "token-123",
	})
	ctx := context.Background()

	// 1. Initialize
	initRes, err := client.Initialize(ctx)
	if err != nil {
		t.Fatalf("failed to initialize: %v", err)
	}
	if initRes.ServerInfo.Name != "mock-mcp-server" {
		t.Errorf("unexpected server name: %s", initRes.ServerInfo.Name)
	}

	// 2. Tools
	tools, err := client.ListTools(ctx)
	if err != nil || len(tools) != 1 {
		t.Fatalf("unexpected tools: %v (err: %v)", tools, err)
	}
	if tools[0].Name != "query_database" {
		t.Errorf("unexpected tool name: %s", tools[0].Name)
	}

	callRes, err := client.CallTool(ctx, "query_database", map[string]interface{}{"sql": "SELECT 1"})
	if err != nil || len(callRes.Content) == 0 {
		t.Fatalf("failed tool call: %v (err: %v)", callRes, err)
	}

	// 3. Resources
	resources, err := client.ListResources(ctx)
	if err != nil || len(resources) != 1 {
		t.Fatalf("unexpected resources: %v (err: %v)", resources, err)
	}

	resContent, err := client.ReadResource(ctx, "jira://PROJ-123")
	if err != nil || resContent.Text == "" {
		t.Fatalf("failed read resource: %v (err: %v)", resContent, err)
	}
}

func TestConnectors_Creation(t *testing.T) {
	jiraClient := JiraConnector("acme.atlassian.net", "bot@acme.com", "token")
	if jiraClient == nil {
		t.Errorf("expected jira client")
	}

	figmaClient := FigmaConnector("figma-pat")
	if figmaClient == nil {
		t.Errorf("expected figma client")
	}
}
