package decomposition

// ProblemAxis represents one orthogonal dimension of contention within a complex dilemma.
type ProblemAxis struct {
	ID           string   `json:"id"`
	Title        string   `json:"title"`
	Thesis       string   `json:"thesis"`
	Antithesis   string   `json:"antithesis"`
	KeyQuestions []string `json:"keyQuestions"`
	Weight       float64  `json:"weight"`
	Selected     bool     `json:"selected"`
}

// Perspective represents a structured cognitive lens (e.g. Systems Architecture vs. Product Strategy).
type Perspective struct {
	ID              string        `json:"id"`
	Name            string        `json:"name"`
	LensDescription string        `json:"lensDescription"`
	Axes            []ProblemAxis `json:"axes"`
}

// DecompositionResult contains the competing divergent perspectives generated for a problem.
type DecompositionResult struct {
	Topic        string      `json:"topic"`
	PerspectiveA Perspective `json:"perspectiveA"`
	PerspectiveB Perspective `json:"perspectiveB"`
}

// DecompositionRequest is the payload sent to request problem decomposition.
type DecompositionRequest struct {
	Topic    string `json:"topic"`
	Context  string `json:"context,omitempty"`
	Model    string `json:"model,omitempty"`
	Provider string `json:"provider,omitempty"`
}
