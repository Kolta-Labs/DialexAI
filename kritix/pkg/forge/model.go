package forge

// ForgeType identifies the remote git hosting provider.
type ForgeType string

const (
	ForgeGitHub ForgeType = "github"
	ForgeGitLab ForgeType = "gitlab"
)

// ForgeAuth provides authentication credentials for a remote forge.
type ForgeAuth struct {
	Type     ForgeType `json:"type"`
	Token    string    `json:"token"`
	Username string    `json:"username,omitempty"`
	BaseURL  string    `json:"baseUrl,omitempty"` // For GitHub Enterprise / Self-hosted GitLab
}

// RemoteRepoTarget identifies a remote repository to operate against.
type RemoteRepoTarget struct {
	CloneURL string `json:"cloneUrl"`
	Owner    string `json:"owner"`
	Repo     string `json:"repo"`
	Branch   string `json:"branch"` // Base branch (e.g. main / master)
}

// PullRequestRequest defines parameters to open a Pull Request / Merge Request.
type PullRequestRequest struct {
	Title string `json:"title"`
	Body  string `json:"body"`
	Head  string `json:"head"` // The newly created feature branch
	Base  string `json:"base"` // The target branch (e.g. main)
}

// PullRequestResponse contains the created PR details.
type PullRequestResponse struct {
	ID        int64  `json:"id"`
	Number    int    `json:"number"`
	Title     string `json:"title"`
	URL       string `json:"url"`
	HTMLURL   string `json:"htmlUrl"`
	State     string `json:"state"`
}

// ForgeClient is the unified interface for remote Git hosts.
type ForgeClient interface {
	CreatePullRequest(target *RemoteRepoTarget, req *PullRequestRequest) (*PullRequestResponse, error)
}
