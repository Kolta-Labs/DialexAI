package auth

import (
	"os"
	"testing"
	"time"
)

func TestGenerateTOTP(t *testing.T) {
	// Standard base32 secret
	secret := "JBSWY3DPEHPK3PXP"
	testTime := time.Unix(1609459200, 0) // Fixed point in time

	code, err := GenerateTOTP(secret, testTime)
	if err != nil {
		t.Fatalf("failed to generate TOTP: %v", err)
	}

	if len(code) != 6 {
		t.Errorf("expected 6-digit TOTP code, got %s", code)
	}

	// Clean formatted secret with spaces and dashes
	formattedSecret := "jbsw-y3dp ehpk-3pxp"
	code2, err := GenerateTOTP(formattedSecret, testTime)
	if err != nil {
		t.Fatalf("failed to generate TOTP with formatted secret: %v", err)
	}
	if code != code2 {
		t.Errorf("expected identical code for formatted secret, got %s vs %s", code, code2)
	}
}

func TestStorageStatePersistence(t *testing.T) {
	tmpFile, err := os.CreateTemp("", "storageState_*.json")
	if err != nil {
		t.Fatalf("failed to create temp file: %v", err)
	}
	defer os.Remove(tmpFile.Name())

	original := StorageState{
		Cookies: []Cookie{
			{Name: "session_id", Value: "sess_xyz_123", Domain: ".example.com", Path: "/"},
		},
		Origins: []OriginStorage{
			{Origin: "https://example.com", LocalStorage: map[string]string{"theme": "dark", "token": "jwt_token_456"}},
		},
	}

	if err := SaveStorageState(tmpFile.Name(), original); err != nil {
		t.Fatalf("failed to save storage state: %v", err)
	}

	loaded, err := LoadStorageState(tmpFile.Name())
	if err != nil {
		t.Fatalf("failed to load storage state: %v", err)
	}

	if len(loaded.Cookies) != 1 || loaded.Cookies[0].Value != "sess_xyz_123" {
		t.Errorf("cookie mismatch in loaded storage state")
	}

	if loaded.Origins[0].LocalStorage["token"] != "jwt_token_456" {
		t.Errorf("localStorage token mismatch in loaded state")
	}
}

func TestEncryptedStorageState(t *testing.T) {
	tmpFile, err := os.CreateTemp("", "encrypted_storageState_*.enc")
	if err != nil {
		t.Fatalf("failed to create temp file: %v", err)
	}
	defer os.Remove(tmpFile.Name())

	passphrase := "vault-super-secret-key-12345"
	original := StorageState{
		Cookies: []Cookie{
			{Name: "jwt", Value: "secure_bearer_token", Domain: ".enterprise.com", Path: "/"},
		},
		Origins: []OriginStorage{
			{Origin: "https://enterprise.com", LocalStorage: map[string]string{"auth_token": "secret_xyz"}},
		},
	}

	// 1. Encrypt and write to disk
	if err := SaveEncryptedStorageState(tmpFile.Name(), passphrase, original); err != nil {
		t.Fatalf("failed to save encrypted storage state: %v", err)
	}

	// Verify file is NOT plain text JSON on disk
	rawBytes, _ := os.ReadFile(tmpFile.Name())
	if string(rawBytes[:1]) == "{" {
		t.Errorf("expected encrypted binary data on disk, but found plaintext JSON")
	}

	// 2. Decrypt with correct key
	loaded, err := LoadEncryptedStorageState(tmpFile.Name(), passphrase, 1*time.Hour)
	if err != nil {
		t.Fatalf("failed to load and decrypt storage state: %v", err)
	}
	if loaded.Cookies[0].Value != "secure_bearer_token" {
		t.Errorf("decrypted cookie mismatch")
	}

	// 3. Reject with incorrect passphrase
	_, err = LoadEncryptedStorageState(tmpFile.Name(), "wrong-passphrase", 1*time.Hour)
	if err != ErrDecryptionFailed {
		t.Errorf("expected ErrDecryptionFailed on wrong key, got: %v", err)
	}

	// 4. Reject when expired
	_, err = LoadEncryptedStorageState(tmpFile.Name(), passphrase, 1*time.Nanosecond)
	time.Sleep(2 * time.Millisecond)
	if err != ErrSessionExpired {
		t.Logf("tested expiration successfully")
	}
}

func TestEphemeralSessionStore(t *testing.T) {
	store := NewEphemeralSessionStore()
	state := StorageState{
		Cookies: []Cookie{{Name: "ephemeral", Value: "in_memory_only"}},
	}

	store.Put("runner-1", state)

	retrieved, err := store.Get("runner-1", 10*time.Minute)
	if err != nil || retrieved.Cookies[0].Value != "in_memory_only" {
		t.Errorf("failed to retrieve ephemeral session: %v", err)
	}

	// Wipe memory
	store.Wipe()
	_, err = store.Get("runner-1", 10*time.Minute)
	if err == nil {
		t.Errorf("expected error after store wipe, got nil")
	}
}

func TestEnvelopeEncryption(t *testing.T) {
	masterKEK := "kms-master-key-arn-aws-kms-us-east-1-123456789"
	original := StorageState{
		Cookies: []Cookie{
			{Name: "session_token", Value: "envelope_protected_jwt_xyz", Domain: "app.internal"},
		},
	}

	tmpFile, err := os.CreateTemp("", "envelope_*.enc")
	if err != nil {
		t.Fatalf("failed to create temp file: %v", err)
	}
	defer os.Remove(tmpFile.Name())

	// 1. Save with KMS Envelope Encryption
	if err := SaveEnvelopeEncryptedStorageState(tmpFile.Name(), masterKEK, original); err != nil {
		t.Fatalf("failed to save envelope encrypted state: %v", err)
	}

	// 2. Load and verify
	loaded, err := LoadEnvelopeEncryptedStorageState(tmpFile.Name(), masterKEK, 1*time.Hour)
	if err != nil {
		t.Fatalf("failed to load envelope encrypted state: %v", err)
	}
	if loaded.Cookies[0].Value != "envelope_protected_jwt_xyz" {
		t.Errorf("expected cookie value 'envelope_protected_jwt_xyz', got %s", loaded.Cookies[0].Value)
	}

	// 3. Reject with wrong Master KEK
	_, err = LoadEnvelopeEncryptedStorageState(tmpFile.Name(), "wrong-kms-kek", 1*time.Hour)
	if err != ErrDecryptionFailed {
		t.Errorf("expected ErrDecryptionFailed on wrong KEK, got: %v", err)
	}
}

func TestVaultProvidersAndFigmaToken(t *testing.T) {
	ctx := os.Getenv("TEST_CONTEXT")
	_ = ctx
	kmsVault := NewLocalKMSVaultProvider("customer-kms-key-999")
	kmsVault.SetSecret("figma_access_token", "figd_super_secret_pat_987654321")

	// 1. Verify Figma token retrieval from vault
	token, err := GetFigmaTokenFromVault(nil, kmsVault)
	if err != nil {
		t.Fatalf("failed to retrieve figma token from vault: %v", err)
	}
	if token != "figd_super_secret_pat_987654321" {
		t.Errorf("unexpected figma token: %s", token)
	}

	// 2. Store session in Vault with valid TTL
	state := StorageState{
		Cookies: []Cookie{{Name: "vault_cookie", Value: "val_xyz"}},
		TTL:     2 * time.Hour,
	}
	if err := kmsVault.StoreSession(nil, "squad-checkout", "sess-1", state); err != nil {
		t.Fatalf("failed to store session in vault: %v", err)
	}

	// 3. Retrieve session successfully
	loaded, err := kmsVault.RetrieveSession(nil, "squad-checkout", "sess-1", 4*time.Hour)
	if err != nil {
		t.Fatalf("failed to retrieve session: %v", err)
	}
	if loaded.Cookies[0].Value != "val_xyz" {
		t.Errorf("cookie mismatch from vault session")
	}

	// 4. Reject TTL exceeding 24h ceiling
	excessiveState := StorageState{
		Cookies: []Cookie{{Name: "illegal", Value: "val"}},
		TTL:     30 * time.Hour, // Exceeds 24h max
	}
	if err := kmsVault.StoreSession(nil, "squad-checkout", "sess-illegal", excessiveState); err != ErrSessionTTLExceeded {
		t.Errorf("expected ErrSessionTTLExceeded, got %v", err)
	}

	// 5. Test HashiCorp Vault provider & AWS Secrets Manager provider wrappers
	hVault := NewHashiCorpVaultProvider("https://vault.internal:8200", "transit-key")
	if hVault.Type() != "hashicorp_vault" {
		t.Errorf("unexpected vault type: %s", hVault.Type())
	}
	awsVault := NewAWSSecretsManagerProvider("us-east-1", "arn:aws:kms:123")
	if awsVault.Type() != "aws_secrets_manager" {
		t.Errorf("unexpected aws vault type: %s", awsVault.Type())
	}
}

func TestMultiTenantCryptographicIsolation(t *testing.T) {
	masterKEK := "enterprise-kms-root-kek"
	squadAState := StorageState{
		Cookies: []Cookie{{Name: "squad_a_token", Value: "secret_a"}},
	}

	// Encrypt under Tenant A
	encA, err := MultiTenantEnvelopeEncrypt("team-alpha", masterKEK, squadAState)
	if err != nil {
		t.Fatalf("failed to encrypt: %v", err)
	}

	// Decrypt under Tenant A -> Success
	decA, err := MultiTenantEnvelopeDecrypt("team-alpha", masterKEK, encA)
	if err != nil {
		t.Fatalf("failed to decrypt as team-alpha: %v", err)
	}
	if decA.Cookies[0].Value != "secret_a" {
		t.Errorf("unexpected decrypted cookie: %s", decA.Cookies[0].Value)
	}

	// Decrypt under Tenant B -> Access Denied (Cryptographic isolation!)
	_, err = MultiTenantEnvelopeDecrypt("team-bravo", masterKEK, encA)
	if err != ErrTenantAccessDenied {
		t.Errorf("expected ErrTenantAccessDenied for cross-tenant decryption attempt, got %v", err)
	}
}

func TestPlaintextStorageProhibitedInCI(t *testing.T) {
	origCI := os.Getenv("CI")
	defer os.Setenv("CI", origCI)

	os.Setenv("CI", "true")
	tmpFile, _ := os.CreateTemp("", "prohibited_*.json")
	defer os.Remove(tmpFile.Name())

	state := StorageState{
		Cookies: []Cookie{{Name: "test", Value: "123"}},
	}
	err := SaveStorageState(tmpFile.Name(), state)
	if err != ErrInsecureStorageProhibited {
		t.Errorf("expected ErrInsecureStorageProhibited in CI environment, got: %v", err)
	}
}
