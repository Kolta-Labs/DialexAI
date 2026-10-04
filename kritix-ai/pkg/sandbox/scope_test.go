package sandbox

import "testing"

func TestTenantScopeIsUniquePerRun(t *testing.T) {
	a, b := NewSandboxEnvironment(SandboxConfig{}).Scope(), NewSandboxEnvironment(SandboxConfig{}).Scope()
	if a.TenantID == b.TenantID || a.ConsumerGroup == b.ConsumerGroup {
		t.Fatal("each run needs its own tenant partitions")
	}
}
