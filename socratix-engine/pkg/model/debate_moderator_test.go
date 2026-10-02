package model

import "testing"

func TestModeratorAgentPicksSeatThenProviderThenPrimary(t *testing.T) {
	skeptic := Agent{ID: "seat-a", Provider: ProviderAnthropic}
	optimist := Agent{ID: "seat-b", Provider: ProviderAnthropic}
	other := Agent{ID: "seat-c", Provider: ProviderOpenAI}
	cfg := func(m *ModeratorConfig) DebateConfig {
		return DebateConfig{Primary: skeptic, Secondary: &optimist, Tertiary: &other, Moderation: m}
	}

	cases := []struct {
		name string
		m    *ModeratorConfig
		want string
	}{
		{"seat wins among same-provider seats", &ModeratorConfig{ModeratorSeatID: "seat-b"}, "seat-b"},
		{"seat wins over provider", &ModeratorConfig{ModeratorSeatID: "seat-b", ModeratorProvider: ProviderOpenAI}, "seat-b"},
		{"legacy provider picks first seat of it", &ModeratorConfig{ModeratorProvider: ProviderOpenAI}, "seat-c"},
		{"unknown seat falls back to primary", &ModeratorConfig{ModeratorSeatID: "gone"}, "seat-a"},
		{"no moderation config is primary", nil, "seat-a"},
	}
	for _, c := range cases {
		if got := cfg(c.m).ModeratorAgent().ID; got != c.want {
			t.Errorf("%s: got %s, want %s", c.name, got, c.want)
		}
	}
}
