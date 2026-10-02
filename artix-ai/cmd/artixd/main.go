package main

import (
	"flag"
	"fmt"
	"net/http"
	"os"
	"path/filepath"

	"artix/pkg/forge"
	"artix/pkg/persona"
)

func main() {
	addr := flag.String("addr", ":8080", "Server listen address")
	ghSecret := flag.String("gh-secret", os.Getenv("GITHUB_WEBHOOK_SECRET"), "GitHub Webhook HMAC SHA256 secret")
	glToken := flag.String("gl-token", os.Getenv("GITLAB_WEBHOOK_TOKEN"), "GitLab Webhook token")
	workDir := flag.String("work-dir", filepath.Join(os.TempDir(), "artix-worker"), "Directory for ephemeral git clones")
	flag.Parse()

	cwd, _ := os.Getwd()
	registry := persona.NewRegistry(cwd)
	worker := forge.NewRemoteWorker(*workDir, registry)

	server := forge.NewWebhookServer(forge.WebhookServerConfig{
		ListenAddr:   *addr,
		GitHubSecret: *ghSecret,
		GitLabToken:  *glToken,
		Worker:       worker,
	})

	fmt.Printf("Starting Artix Daemon on %s...\n", *addr)
	fmt.Printf("Endpoints:\n  - GET  /healthz\n  - POST /webhook/github\n  - POST /webhook/gitlab\n  - GET  /jobs\n")

	if err := http.ListenAndServe(*addr, server.Handler()); err != nil {
		fmt.Fprintf(os.Stderr, "Server fatal error: %v\n", err)
		os.Exit(1)
	}
}
