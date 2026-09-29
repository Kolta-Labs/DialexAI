package skills

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"kritix/pkg/sandbox"
)

// SkillParameter defines an input argument for a skill.
type SkillParameter struct {
	Type        string `json:"type"`
	Description string `json:"description"`
	Required    bool   `json:"required"`
	Default     string `json:"default,omitempty"`
}

// Skill represents a custom repository automation tool or workflow script.
type Skill struct {
	Name           string                    `json:"name"`
	Description    string                    `json:"description"`
	Parameters     map[string]SkillParameter `json:"parameters,omitempty"`
	Command        string                    `json:"command"`
	PromptTemplate string                    `json:"promptTemplate,omitempty"`
	SourcePath     string                    `json:"sourcePath"`
	IsProjectLevel bool                      `json:"isProjectLevel"`
}

// Loader scans and indexes skills from workspace and user home directories.
type Loader struct {
	projectDir string
	globalDir  string
}

// NewLoader creates a new skills loader.
func NewLoader(projectDir string) *Loader {
	home, _ := os.UserHomeDir()
	globalDir := ""
	if home != "" {
		globalDir = filepath.Join(home, ".kritix", "skills")
	}

	return &Loader{
		projectDir: projectDir,
		globalDir:  globalDir,
	}
}

// LoadAll loads all global and project-level skills. Project skills override global skills with same name.
func (l *Loader) LoadAll() ([]Skill, error) {
	skillMap := make(map[string]Skill)

	// 1. Load global skills
	if l.globalDir != "" {
		gSkills, _ := l.scanDir(l.globalDir, false)
		for _, s := range gSkills {
			skillMap[s.Name] = s
		}
	}

	// 2. Load project-level skills (.kritix/skills)
	if l.projectDir != "" {
		pDir := filepath.Join(l.projectDir, ".kritix", "skills")
		pSkills, _ := l.scanDir(pDir, true)
		for _, s := range pSkills {
			skillMap[s.Name] = s
		}
	}

	res := make([]Skill, 0, len(skillMap))
	for _, s := range skillMap {
		res = append(res, s)
	}
	return res, nil
}

func (l *Loader) scanDir(dir string, isProject bool) ([]Skill, error) {
	if _, err := os.Stat(dir); os.IsNotExist(err) {
		return nil, nil
	}

	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}

	var skills []Skill
	for _, entry := range entries {
		var skillPath string
		if entry.IsDir() {
			candidate := filepath.Join(dir, entry.Name(), "SKILL.md")
			if _, err := os.Stat(candidate); err == nil {
				skillPath = candidate
			}
		} else if strings.HasSuffix(entry.Name(), ".json") || strings.HasSuffix(entry.Name(), ".md") {
			skillPath = filepath.Join(dir, entry.Name())
		}

		if skillPath != "" {
			s, err := parseSkillFile(skillPath, isProject)
			if err == nil {
				skills = append(skills, *s)
			}
		}
	}

	return skills, nil
}

func parseSkillFile(path string, isProject bool) (*Skill, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	baseName := strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))
	if baseName == "SKILL" {
		baseName = filepath.Base(filepath.Dir(path))
	}

	if strings.HasSuffix(path, ".json") {
		var s Skill
		if err := json.Unmarshal(data, &s); err != nil {
			return nil, err
		}
		if s.Name == "" {
			s.Name = baseName
		}
		s.SourcePath = path
		s.IsProjectLevel = isProject
		return &s, nil
	}

	s := &Skill{
		Name:           baseName,
		SourcePath:     path,
		IsProjectLevel: isProject,
		Parameters:     make(map[string]SkillParameter),
	}

	scanner := bufio.NewScanner(bytes.NewReader(data))
	inFrontmatter := false
	var body strings.Builder

	for scanner.Scan() {
		line := scanner.Text()
		trimmed := strings.TrimSpace(line)

		if trimmed == "---" {
			inFrontmatter = !inFrontmatter
			continue
		}

		if inFrontmatter {
			parts := strings.SplitN(trimmed, ":", 2)
			if len(parts) == 2 {
				key := strings.TrimSpace(parts[0])
				val := strings.TrimSpace(parts[1])
				val = trimOuterQuotes(val)
				switch strings.ToLower(key) {
				case "name":
					s.Name = val
				case "description":
					s.Description = val
				case "command":
					s.Command = val
				}
			}
		} else {
			body.WriteString(line)
			body.WriteByte('\n')
		}
	}

	s.PromptTemplate = strings.TrimSpace(body.String())
	if s.Description == "" {
		s.Description = s.PromptTemplate
	}

	return s, nil
}

func trimOuterQuotes(s string) string {
	s = strings.TrimSpace(s)
	if len(s) >= 2 {
		if (s[0] == '"' && s[len(s)-1] == '"') || (s[0] == '\'' && s[len(s)-1] == '\'') {
			return s[1 : len(s)-1]
		}
	}
	return s
}

// Execute runs the skill's command inside the sandbox, replacing placeholders like {{param}}.
func (l *Loader) Execute(ctx context.Context, skill *Skill, args map[string]string) (*sandbox.ExecResult, error) {
	if skill.Command == "" {
		return nil, fmt.Errorf("skill %s has no executable command defined", skill.Name)
	}

	cmdStr := skill.Command
	for k, v := range args {
		placeholder := fmt.Sprintf("{{%s}}", k)
		cmdStr = strings.ReplaceAll(cmdStr, placeholder, v)
	}

	box := sandbox.NewSandbox(l.projectDir)
	res := box.Run(ctx, cmdStr, &sandbox.ExecOptions{
		Cwd: l.projectDir,
	})
	return res, nil
}
