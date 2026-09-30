package api

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"

	"dialex/pkg/model"
	"dialex/pkg/persona"
)

// handleGetPersonaDNA returns the 8-Layer DNA for a specific persona.
func (s *Server) handleGetPersonaDNA(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	for _, p := range state.Personas {
		if p.ID == id {
			if p.DNA != nil {
				writeJSON(w, http.StatusOK, p.DNA)
				return
			}
			// If persona has no DNA, attempt to see if a canonical built-in DNA exists
			if builtin := persona.GetBuiltinPersonaDNA(p.ID); builtin != nil {
				writeJSON(w, http.StatusOK, builtin)
				return
			}
			// Construct a minimal fallback DNA from standard Persona fields
			fallback := &model.PersonaDNA{
				SchemaVersion: "dialex.dna/v1.0",
				ID:            p.ID,
				Name:          p.Name,
				Role:          p.Role,
				Category:      p.Category,
				Icon:          p.Icon,
				CoreIdentity: model.CoreIdentity{
					Title:           p.Role,
					Background:      p.RoleAndPersona,
					DomainAuthority: p.CoreExpertise,
				},
				CommunicationVector: model.CommunicationVector{
					Tone:                  p.ToneAndVoice,
					FormalityLevel:        3,
					TargetSentenceCeiling: 4,
				},
				RawCustomPrompt: p.SystemPrompt,
			}
			writeJSON(w, http.StatusOK, fallback)
			return
		}
	}

	writeError(w, http.StatusNotFound, "persona not found")
}

// handleUpdatePersonaDNA saves the 8-Layer DNA for a persona and updates its compiled prompt.
func (s *Server) handleUpdatePersonaDNA(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	var dna model.PersonaDNA
	if err := json.NewDecoder(r.Body).Decode(&dna); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON")
		return
	}

	if dna.ID == "" {
		dna.ID = id
	}
	if err := persona.ValidateDNA(&dna); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	found := false
	for i, p := range state.Personas {
		if p.ID == id {
			found = true
			state.Personas[i].DNA = &dna
			// Recompile system prompt from DNA mandate
			compiledPrompt := persona.CompilePrompt(&dna)
			if compiledPrompt != "" {
				state.Personas[i].SystemPrompt = compiledPrompt
			}
			break
		}
	}

	if !found {
		// If persona doesn't exist yet, create it with this DNA
		newP := model.Persona{
			ID:             id,
			Name:           dna.Name,
			Role:           dna.Role,
			Category:       dna.Category,
			Icon:           dna.Icon,
			SystemPrompt:   persona.CompilePrompt(&dna),
			RoleAndPersona: dna.CoreIdentity.Background,
			CoreExpertise:  dna.CoreIdentity.DomainAuthority,
			ToneAndVoice:   dna.CommunicationVector.Tone,
			DNA:            &dna,
		}
		state.Personas = append(state.Personas, newP)
	}

	if err := s.Store.Save(state); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	writeJSON(w, http.StatusOK, dna)
}

// handleListBuiltinHeuristics returns the built-in library of mental models.
func (s *Server) handleListBuiltinHeuristics(w http.ResponseWriter, r *http.Request) {
	heuristics := persona.GetBuiltinHeuristics()
	writeJSON(w, http.StatusOK, heuristics)
}

type CompileDNARequest struct {
	DNA model.PersonaDNA `json:"dna"`
}

type CompileDNAResponse struct {
	CompiledPrompt string `json:"compiledPrompt"`
}

// handleCompilePersonaDNA converts a DNA payload into a compiled instruction prompt.
func (s *Server) handleCompilePersonaDNA(w http.ResponseWriter, r *http.Request) {
	var req CompileDNARequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON")
		return
	}

	compiled := persona.CompilePrompt(&req.DNA)
	writeJSON(w, http.StatusOK, CompileDNAResponse{CompiledPrompt: compiled})
}

// handleImportPersonaDNA parses YAML or JSON text into a PersonaDNA object.
func (s *Server) handleImportPersonaDNA(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		writeError(w, http.StatusBadRequest, "failed to read body")
		return
	}

	formatHint := r.URL.Query().Get("format")
	imported, err := persona.ImportDNA(body, formatHint)
	if err != nil {
		writeError(w, http.StatusBadRequest, "failed to import DNA: "+err.Error())
		return
	}

	writeJSON(w, http.StatusOK, imported)
}

// handleExportPersonaDNA exports a persona's DNA in either YAML (MMOS) or JSON format.
func (s *Server) handleExportPersonaDNA(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	format := r.URL.Query().Get("format")
	if format == "" {
		format = "yaml"
	}

	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	var targetDNA *model.PersonaDNA
	for _, p := range state.Personas {
		if p.ID == id {
			targetDNA = p.DNA
			if targetDNA == nil {
				targetDNA = persona.GetBuiltinPersonaDNA(p.ID)
			}
			break
		}
	}

	if targetDNA == nil {
		writeError(w, http.StatusNotFound, "persona DNA not found")
		return
	}

	exported, err := persona.ExportDNA(targetDNA, format)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	if strings.EqualFold(format, "yaml") || strings.EqualFold(format, "yml") {
		w.Header().Set("Content-Type", "application/x-yaml")
		w.Header().Set("Content-Disposition", "attachment; filename=\""+id+"_dna.yaml\"")
	} else {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Content-Disposition", "attachment; filename=\""+id+"_dna.json\"")
	}

	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(exported)
}
