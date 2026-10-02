package plugins

import (
	"os"
	"path/filepath"
	"testing"
)

func TestBuildDrivers_Detection(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "kritix-plugins-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	reg := NewRegistry()

	// 1. Initially no driver
	_, found := reg.DetectDriver(tempDir)
	if found {
		t.Errorf("expected no driver detected for empty directory")
	}

	// 2. Go project detection
	goMod := filepath.Join(tempDir, "go.mod")
	_ = os.WriteFile(goMod, []byte("module testmod\n"), 0644)
	driver, found := reg.DetectDriver(tempDir)
	if !found || driver.Name() != "go" {
		t.Errorf("expected go driver, got found=%v, driver=%v", found, driver)
	}
	_ = os.Remove(goMod)

	// 3. Gradle project detection
	gradleKts := filepath.Join(tempDir, "build.gradle.kts")
	_ = os.WriteFile(gradleKts, []byte("plugins { kotlin(\"multiplatform\") }\n"), 0644)
	driver, found = reg.DetectDriver(tempDir)
	if !found || driver.Name() != "gradle" {
		t.Errorf("expected gradle driver, got found=%v, driver=%v", found, driver)
	}
	_ = os.Remove(gradleKts)

	// 4. Cargo project detection
	cargoToml := filepath.Join(tempDir, "Cargo.toml")
	_ = os.WriteFile(cargoToml, []byte("[package]\nname = \"test\"\n"), 0644)
	driver, found = reg.DetectDriver(tempDir)
	if !found || driver.Name() != "cargo" {
		t.Errorf("expected cargo driver, got found=%v, driver=%v", found, driver)
	}
	_ = os.Remove(cargoToml)

	// 5. npm project detection
	pkgJson := filepath.Join(tempDir, "package.json")
	_ = os.WriteFile(pkgJson, []byte("{\"name\": \"test\"}\n"), 0644)
	driver, found = reg.DetectDriver(tempDir)
	if !found || driver.Name() != "npm" {
		t.Errorf("expected npm driver, got found=%v, driver=%v", found, driver)
	}
}

func TestGoDriver_ParseErrorTrace(t *testing.T) {
	d := &GoDriver{}
	output := `
# example/pkg
foo/bar.go:18:5: undefined: UserProfile
foo/bar.go:24: undefined: CalculateTotal
`
	diags := d.ParseErrorTrace(output)
	if len(diags) != 2 {
		t.Fatalf("expected 2 diagnostics, got %d", len(diags))
	}

	if diags[0].File != "foo/bar.go" || diags[0].Line != 18 || diags[0].Column != 5 || diags[0].Message != "undefined: UserProfile" {
		t.Errorf("unexpected first diagnostic: %+v", diags[0])
	}
	if diags[1].File != "foo/bar.go" || diags[1].Line != 24 || diags[1].Column != 0 || diags[1].Message != "undefined: CalculateTotal" {
		t.Errorf("unexpected second diagnostic: %+v", diags[1])
	}
}

func TestGradleDriver_ParseErrorTrace(t *testing.T) {
	d := &GradleDriver{}
	output := `
/app/src/main/kotlin/App.kt:42:15: e: Unresolved reference: KoltViewModel
/app/src/main/kotlin/App.kt:88:5: w: Parameter 'unused' is never used
`
	diags := d.ParseErrorTrace(output)
	if len(diags) != 2 {
		t.Fatalf("expected 2 diagnostics, got %d", len(diags))
	}

	if diags[0].Severity != SeverityError || diags[0].Line != 42 || diags[0].Column != 15 || diags[0].Message != "Unresolved reference: KoltViewModel" {
		t.Errorf("unexpected first diagnostic: %+v", diags[0])
	}
	if diags[1].Severity != SeverityWarning || diags[1].Line != 88 || diags[1].Column != 5 || diags[1].Message != "Parameter 'unused' is never used" {
		t.Errorf("unexpected second diagnostic: %+v", diags[1])
	}
}

func TestCargoDriver_ParseErrorTrace(t *testing.T) {
	d := &CargoDriver{}
	output := `
error[E0425]: cannot find value 'calc' in this scope
  --> src/main.rs:15:9
`
	diags := d.ParseErrorTrace(output)
	if len(diags) != 1 {
		t.Fatalf("expected 1 diagnostic, got %d", len(diags))
	}

	if diags[0].File != "src/main.rs" || diags[0].Line != 15 || diags[0].Column != 9 || diags[0].Rule != "E0425" {
		t.Errorf("unexpected diagnostic: %+v", diags[0])
	}
}

func TestNpmDriver_ParseErrorTrace(t *testing.T) {
	d := &NpmDriver{}
	output := `
src/index.ts(12,4): error TS2304: Cannot find name 'Config'.
src/utils.ts:45:10: warning Unused variable 'flag'
`
	diags := d.ParseErrorTrace(output)
	if len(diags) != 2 {
		t.Fatalf("expected 2 diagnostics, got %d", len(diags))
	}

	if diags[0].File != "src/index.ts" || diags[0].Line != 12 || diags[0].Column != 4 || diags[0].Severity != SeverityError {
		t.Errorf("unexpected first diagnostic: %+v", diags[0])
	}
	if diags[1].File != "src/utils.ts" || diags[1].Line != 45 || diags[1].Column != 10 || diags[1].Severity != SeverityWarning {
		t.Errorf("unexpected second diagnostic: %+v", diags[1])
	}
}
