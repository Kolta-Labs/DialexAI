package model

// Persona is a predefined AI persona that can be applied to any agent seat.
type Persona struct {
	ID             string `json:"id"`
	Name           string `json:"name"`
	Description    string `json:"description"`
	Category       string `json:"category,omitempty"`
	Role           string `json:"role"`
	Icon           string `json:"icon,omitempty"`
	SystemPrompt   string `json:"systemPrompt"`
	Caveman        bool   `json:"caveman"`
	Ponytail       bool   `json:"ponytail"`
	IsSystem       bool   `json:"isSystem"`
	RoleAndPersona string `json:"roleAndPersona,omitempty"`
	CoreExpertise  string `json:"coreExpertise,omitempty"`
	ToneAndVoice   string `json:"toneAndVoice,omitempty"`
	Objective      string `json:"objective,omitempty"`
}

