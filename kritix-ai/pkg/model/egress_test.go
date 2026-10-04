package model

import "testing"

func TestEgressAllowed(t *testing.T) {
	if !egressAllowed("127.0.0.1", nil) || !egressAllowed("llm.corp", []string{"LLM.corp"}) {
		t.Fatal("loopback and allowlisted hosts must pass")
	}
	if egressAllowed("api.openai.com", nil) {
		t.Fatal("air-gapped client must block external hosts")
	}
}
