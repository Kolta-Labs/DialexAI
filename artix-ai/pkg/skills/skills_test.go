package skills

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestSkillsLoader_LoadAndExecute(t *testing.T) {
	tempProject, err := os.MkdirTemp("", "kritix-skill-test-*")
	if err != nil {
		t.Fatalf("failed to create temp project dir: %v", err)
	}
	defer os.RemoveAll(tempProject)

	skillsDir := filepath.Join(tempProject, ".kritix", "skills")
	_ = os.MkdirAll(skillsDir, 0755)

	// 1. Write markdown skill with frontmatter
	mdSkillContent := `---
name: format_code
description: Formats repository source code
command: echo "Formatting file {{target}} with mode {{mode}}"
---
You are a code formatter. Always follow strict lint rules.
`
	_ = os.WriteFile(filepath.Join(skillsDir, "format_code.md"), []byte(mdSkillContent), 0644)

	// 2. Write json skill
	jsonSkillContent := `{
		"name": "generate_docs",
		"description": "Generates API documentation",
		"command": "echo \"Generating docs for {{module}}\""
	}`
	_ = os.WriteFile(filepath.Join(skillsDir, "generate_docs.json"), []byte(jsonSkillContent), 0644)

	// 3. Write subdirectory skill with SKILL.md
	subSkillDir := filepath.Join(skillsDir, "db_migrate")
	_ = os.MkdirAll(subSkillDir, 0755)
	subSkillContent := `---
name: db_migrate
description: Run database migrations
command: echo "Migrating DB"
---
`
	_ = os.WriteFile(filepath.Join(subSkillDir, "SKILL.md"), []byte(subSkillContent), 0644)

	loader := NewLoader(tempProject)
	skills, err := loader.LoadAll()
	if err != nil {
		t.Fatalf("failed to load skills: %v", err)
	}

	if len(skills) != 3 {
		t.Fatalf("expected 3 skills loaded, got %d", len(skills))
	}

	// Find format_code
	var formatSkill *Skill
	for i := range skills {
		if skills[i].Name == "format_code" {
			formatSkill = &skills[i]
			break
		}
	}
	if formatSkill == nil {
		t.Fatalf("format_code skill not found")
	}

	// 4. Test execute with args interpolation
	ctx := context.Background()
	res, err := loader.Execute(ctx, formatSkill, map[string]string{
		"target": "pkg/auth/login.go",
		"mode":   "strict",
	})
	if err != nil {
		t.Fatalf("failed to execute skill: %v", err)
	}

	if !res.Success() {
		t.Fatalf("skill command failed: exit %d\nStderr: %s", res.ExitCode, res.Stderr)
	}

	expectedOut := "Formatting file pkg/auth/login.go with mode strict"
	if res.Stdout != expectedOut+"\n" && res.Stdout != expectedOut {
		t.Errorf("unexpected stdout: %q, expected %q", res.Stdout, expectedOut)
	}
}
