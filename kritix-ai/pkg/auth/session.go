package auth

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

var (
	ErrSessionExpired            = errors.New("session storage state has expired")
	ErrDecryptionFailed          = errors.New("failed to decrypt session storage state: invalid key or corrupted data")
	ErrNilStorageState           = errors.New("storage state is nil")
	ErrInsecureStorageProhibited = errors.New("unencrypted plaintext storageState.json is strictly prohibited in enterprise environments under FedRAMP Moderate / GDPR Art. 32. Use Vault or KMS Envelope encryption")
	ErrSessionTTLExceeded        = errors.New("requested session TTL exceeds the enterprise maximum limit of 24 hours")
	ErrTenantAccessDenied        = errors.New("cryptographic tenant mismatch: access to session denied across tenant boundary")
)

const (
	DefaultSessionTTL    = 4 * time.Hour
	MaxAllowedSessionTTL = 24 * time.Hour
)

// Cookie represents a browser cookie preserved across test scenarios.
type Cookie struct {
	Name     string  `json:"name"`
	Value    string  `json:"value"`
	Domain   string  `json:"domain"`
	Path     string  `json:"path"`
	Expires  float64 `json:"expires"`
	HTTPOnly bool    `json:"httpOnly"`
	Secure   bool    `json:"secure"`
	SameSite string  `json:"sameSite"`
}

// OriginStorage represents localStorage key-value items per origin.
type OriginStorage struct {
	Origin       string            `json:"origin"`
	LocalStorage map[string]string `json:"localStorage"`
}

// StorageState models the cached session state (cookies and localStorage).
type StorageState struct {
	Cookies []Cookie        `json:"cookies"`
	Origins []OriginStorage `json:"origins"`
	SavedAt time.Time       `json:"saved_at"`
	TTL     time.Duration   `json:"ttl,omitempty"` // Maximum allowed lifetime
}

// IsExpired checks if the session state has exceeded its TTL or maximum allowed age.
// It strictly clamps maximum allowed session age to enterprise limit of 24 hours.
func (s *StorageState) IsExpired(maxAge time.Duration) bool {
	if s.SavedAt.IsZero() {
		return true
	}
	limit := maxAge
	if s.TTL > 0 && (limit <= 0 || s.TTL < limit) {
		limit = s.TTL
	}
	if limit <= 0 {
		limit = DefaultSessionTTL
	}
	// Hard ceiling: FedRAMP/GDPR requirement forbids session state life > 24 hours
	if limit > MaxAllowedSessionTTL {
		limit = MaxAllowedSessionTTL
	}
	return time.Since(s.SavedAt) > limit
}

// deriveKey derives a 32-byte AES-256 key from any secret string using SHA-256.
func deriveKey(passphrase string) []byte {
	hash := sha256.Sum256([]byte(passphrase))
	return hash[:]
}

// EncryptStorageState serializes and encrypts storage state using AES-256-GCM.
func EncryptStorageState(state StorageState, passphrase string) ([]byte, error) {
	state.SavedAt = time.Now()
	plainText, err := json.Marshal(state)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal storage state: %w", err)
	}

	key := deriveKey(passphrase)
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("failed to create cipher block: %w", err)
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("failed to create GCM: %w", err)
	}

	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, fmt.Errorf("failed to generate nonce: %w", err)
	}

	cipherText := gcm.Seal(nonce, nonce, plainText, nil)
	return cipherText, nil
}

// DecryptStorageState decrypts and deserializes storage state using AES-256-GCM.
func DecryptStorageState(cipherText []byte, passphrase string) (*StorageState, error) {
	key := deriveKey(passphrase)
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, ErrDecryptionFailed
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, ErrDecryptionFailed
	}

	nonceSize := gcm.NonceSize()
	if len(cipherText) < nonceSize {
		return nil, ErrDecryptionFailed
	}

	nonce, actualCipherText := cipherText[:nonceSize], cipherText[nonceSize:]
	plainText, err := gcm.Open(nil, nonce, actualCipherText, nil)
	if err != nil {
		return nil, ErrDecryptionFailed
	}

	var state StorageState
	if err := json.Unmarshal(plainText, &state); err != nil {
		return nil, fmt.Errorf("failed to unmarshal decrypted storage state: %w", err)
	}

	return &state, nil
}

// EnvelopeEncryptedContainer implements KMS envelope encryption.
// The session data is encrypted with a random ephemeral DEK.
// The DEK is encrypted with the KMS Master Key (KEK).
type EnvelopeEncryptedContainer struct {
	EncryptedDEK  []byte    `json:"encrypted_dek"`
	EncryptedData []byte    `json:"encrypted_data"`
	Algorithm     string    `json:"algorithm"`
	CreatedAt     time.Time `json:"created_at"`
}

// EncryptEnvelope encrypts the storage state using KMS-style envelope encryption.
func EncryptEnvelope(state StorageState, masterKEK string) ([]byte, error) {
	if masterKEK == "" {
		return nil, errors.New("master KEK (Key Encryption Key) cannot be empty")
	}

	// 1. Generate an ephemeral 32-byte Data Encryption Key (DEK)
	dek := make([]byte, 32)
	if _, err := io.ReadFull(rand.Reader, dek); err != nil {
		return nil, fmt.Errorf("failed to generate ephemeral DEK: %w", err)
	}

	// 2. Encrypt the session payload with DEK using AES-256-GCM
	dekBlock, err := aes.NewCipher(dek)
	if err != nil {
		return nil, err
	}
	dekGCM, err := cipher.NewGCM(dekBlock)
	if err != nil {
		return nil, err
	}
	dekNonce := make([]byte, dekGCM.NonceSize())
	if _, err := io.ReadFull(rand.Reader, dekNonce); err != nil {
		return nil, err
	}
	state.SavedAt = time.Now()
	payloadJSON, _ := json.Marshal(state)
	encryptedPayload := dekGCM.Seal(dekNonce, dekNonce, payloadJSON, nil)

	// 3. Encrypt the DEK using the Master KEK (AWS KMS / HashiCorp Vault pattern)
	kekKey := deriveKey(masterKEK)
	kekBlock, err := aes.NewCipher(kekKey)
	if err != nil {
		return nil, err
	}
	kekGCM, err := cipher.NewGCM(kekBlock)
	if err != nil {
		return nil, err
	}
	kekNonce := make([]byte, kekGCM.NonceSize())
	if _, err := io.ReadFull(rand.Reader, kekNonce); err != nil {
		return nil, err
	}
	encryptedDEK := kekGCM.Seal(kekNonce, kekNonce, dek, nil)

	container := EnvelopeEncryptedContainer{
		EncryptedDEK:  encryptedDEK,
		EncryptedData: encryptedPayload,
		Algorithm:     "AES-256-GCM+Envelope",
		CreatedAt:     time.Now(),
	}

	return json.Marshal(container)
}

// DecryptEnvelope unwraps the DEK using the Master KEK and decrypts the storage state.
func DecryptEnvelope(containerJSON []byte, masterKEK string) (*StorageState, error) {
	if masterKEK == "" {
		return nil, errors.New("master KEK cannot be empty")
	}

	var container EnvelopeEncryptedContainer
	if err := json.Unmarshal(containerJSON, &container); err != nil {
		return nil, fmt.Errorf("invalid envelope container: %w", err)
	}

	// 1. Decrypt the DEK using the Master KEK
	kekKey := deriveKey(masterKEK)
	kekBlock, err := aes.NewCipher(kekKey)
	if err != nil {
		return nil, ErrDecryptionFailed
	}
	kekGCM, err := cipher.NewGCM(kekBlock)
	if err != nil {
		return nil, ErrDecryptionFailed
	}
	kekNonceSize := kekGCM.NonceSize()
	if len(container.EncryptedDEK) < kekNonceSize {
		return nil, ErrDecryptionFailed
	}
	kekNonce, actualEncDEK := container.EncryptedDEK[:kekNonceSize], container.EncryptedDEK[kekNonceSize:]
	dek, err := kekGCM.Open(nil, kekNonce, actualEncDEK, nil)
	if err != nil {
		return nil, ErrDecryptionFailed
	}

	// 2. Decrypt the payload using the recovered DEK
	dekBlock, err := aes.NewCipher(dek)
	if err != nil {
		return nil, ErrDecryptionFailed
	}
	dekGCM, err := cipher.NewGCM(dekBlock)
	if err != nil {
		return nil, ErrDecryptionFailed
	}
	dekNonceSize := dekGCM.NonceSize()
	if len(container.EncryptedData) < dekNonceSize {
		return nil, ErrDecryptionFailed
	}
	dekNonce, actualEncPayload := container.EncryptedData[:dekNonceSize], container.EncryptedData[dekNonceSize:]
	payloadJSON, err := dekGCM.Open(nil, dekNonce, actualEncPayload, nil)
	if err != nil {
		return nil, ErrDecryptionFailed
	}

	var state StorageState
	if err := json.Unmarshal(payloadJSON, &state); err != nil {
		return nil, fmt.Errorf("failed to unmarshal state: %w", err)
	}

	return &state, nil
}

// SaveEnvelopeEncryptedStorageState writes envelope encrypted state to disk with 0600 permissions.
func SaveEnvelopeEncryptedStorageState(filePath, masterKEK string, state StorageState) error {
	cipherText, err := EncryptEnvelope(state, masterKEK)
	if err != nil {
		return err
	}
	return os.WriteFile(filePath, cipherText, 0600)
}

// LoadEnvelopeEncryptedStorageState loads envelope encrypted state, validating TTL.
func LoadEnvelopeEncryptedStorageState(filePath, masterKEK string, maxAge time.Duration) (*StorageState, error) {
	data, err := os.ReadFile(filePath)
	if err != nil {
		return nil, err
	}

	state, err := DecryptEnvelope(data, masterKEK)
	if err != nil {
		return nil, err
	}

	if state.IsExpired(maxAge) {
		return nil, ErrSessionExpired
	}

	return state, nil
}

// SaveEncryptedStorageState writes AES-256-GCM encrypted state to disk with 0600 permissions.
func SaveEncryptedStorageState(filePath, passphrase string, state StorageState) error {
	cipherText, err := EncryptStorageState(state, passphrase)
	if err != nil {
		return err
	}
	return os.WriteFile(filePath, cipherText, 0600)
}

// LoadEncryptedStorageState reads and decrypts state from disk, validating expiration.
func LoadEncryptedStorageState(filePath, passphrase string, maxAge time.Duration) (*StorageState, error) {
	cipherData, err := os.ReadFile(filePath)
	if err != nil {
		return nil, err
	}

	state, err := DecryptStorageState(cipherData, passphrase)
	if err != nil {
		return nil, err
	}

	if state.IsExpired(maxAge) {
		return nil, ErrSessionExpired
	}

	return state, nil
}

// EphemeralSessionStore holds session credentials purely in memory with automated cleanup.
type EphemeralSessionStore struct {
	mu       sync.RWMutex
	sessions map[string]*StorageState
}

// NewEphemeralSessionStore constructs a memory-only session cache.
func NewEphemeralSessionStore() *EphemeralSessionStore {
	return &EphemeralSessionStore{
		sessions: make(map[string]*StorageState),
	}
}

// Put stores a session in memory with timestamp.
func (s *EphemeralSessionStore) Put(id string, state StorageState) {
	s.mu.Lock()
	defer s.mu.Unlock()
	state.SavedAt = time.Now()
	s.sessions[id] = &state
}

// Get retrieves a session from memory if not expired.
func (s *EphemeralSessionStore) Get(id string, maxAge time.Duration) (*StorageState, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	state, exists := s.sessions[id]
	if !exists {
		return nil, errors.New("session not found")
	}
	if state.IsExpired(maxAge) {
		return nil, ErrSessionExpired
	}
	return state, nil
}

// Wipe permanently flushes all active sessions from memory.
func (s *EphemeralSessionStore) Wipe() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for k := range s.sessions {
		delete(s.sessions, k)
	}
}

// SaveStorageState writes unencrypted state to disk.
// Hardened Default: Strictly prohibited in CI and production environments under FedRAMP Moderate and GDPR Art. 32.
func SaveStorageState(filePath string, state StorageState) error {
	if os.Getenv("CI") == "true" || os.Getenv("KRITIX_ENFORCE_VAULT") == "true" || os.Getenv("KRITIX_ENV") == "production" {
		return ErrInsecureStorageProhibited
	}
	state.SavedAt = time.Now()
	data, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filePath, data, 0600)
}

// LoadStorageState reads unencrypted state from disk.
func LoadStorageState(filePath string) (*StorageState, error) {
	data, err := os.ReadFile(filePath)
	if err != nil {
		return nil, err
	}
	var state StorageState
	if err := json.Unmarshal(data, &state); err != nil {
		return nil, err
	}
	return &state, nil
}

// MultiTenantEnvelopeEncrypt wraps state with a tenant-isolated key derived from masterKEK + tenantID.
func MultiTenantEnvelopeEncrypt(tenantID, masterKEK string, state StorageState) ([]byte, error) {
	if tenantID == "" {
		tenantID = "default"
	}
	tenantSpecificKEK := fmt.Sprintf("%s::tenant::%s", masterKEK, tenantID)
	return EncryptEnvelope(state, tenantSpecificKEK)
}

// MultiTenantEnvelopeDecrypt unwraps state with tenant-specific key. Fails if wrong tenant accesses data.
func MultiTenantEnvelopeDecrypt(tenantID, masterKEK string, containerJSON []byte) (*StorageState, error) {
	if tenantID == "" {
		tenantID = "default"
	}
	tenantSpecificKEK := fmt.Sprintf("%s::tenant::%s", masterKEK, tenantID)
	state, err := DecryptEnvelope(containerJSON, tenantSpecificKEK)
	if err != nil {
		if errors.Is(err, ErrDecryptionFailed) {
			return nil, ErrTenantAccessDenied
		}
		return nil, err
	}
	return state, nil
}

// VaultProvider defines the enterprise session vault interface (HashiCorp Vault, AWS Secrets Manager, GCP Secret Manager).
type VaultProvider interface {
	Type() string
	StoreSession(ctx context.Context, tenantID, sessionID string, state StorageState) error
	RetrieveSession(ctx context.Context, tenantID, sessionID string, maxAge time.Duration) (*StorageState, error)
	DeleteSession(ctx context.Context, tenantID, sessionID string) error
	GetSecret(ctx context.Context, secretName string) (string, error)
}

// LocalKMSVaultProvider provides in-memory / local customer-managed KMS key encrypted vaulting.
type LocalKMSVaultProvider struct {
	mu        sync.RWMutex
	masterKEK string
	sessions  map[string][]byte
	secrets   map[string]string
}

// randomKEK returns a fresh 256-bit key, hex encoded.
func randomKEK() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err) // no entropy: refuse to run rather than use a weak key
	}
	return hex.EncodeToString(b)
}

// NewLocalKMSVaultProvider creates a customer KMS envelope encrypted vault.
// An empty masterKEK yields a random ephemeral key.
func NewLocalKMSVaultProvider(masterKEK string) *LocalKMSVaultProvider {
	if masterKEK == "" {
		masterKEK = randomKEK() // ephemeral per-process key: sessions die with the runner
	}
	return &LocalKMSVaultProvider{
		masterKEK: masterKEK,
		sessions:  make(map[string][]byte),
		secrets:   make(map[string]string),
	}
}

func (p *LocalKMSVaultProvider) Type() string {
	return "kms_envelope"
}

func (p *LocalKMSVaultProvider) SetSecret(name, value string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.secrets[name] = value
}

func (p *LocalKMSVaultProvider) GetSecret(ctx context.Context, secretName string) (string, error) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	val, ok := p.secrets[secretName]
	if !ok {
		return "", fmt.Errorf("secret %q not found in vault", secretName)
	}
	return val, nil
}

func (p *LocalKMSVaultProvider) StoreSession(ctx context.Context, tenantID, sessionID string, state StorageState) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	// Enforce 24h ceiling on state TTL
	if state.TTL > MaxAllowedSessionTTL {
		return ErrSessionTTLExceeded
	}
	if state.TTL <= 0 {
		state.TTL = DefaultSessionTTL
	}

	encrypted, err := MultiTenantEnvelopeEncrypt(tenantID, p.masterKEK, state)
	if err != nil {
		return fmt.Errorf("failed to encrypt session in vault: %w", err)
	}

	key := fmt.Sprintf("%s:%s", tenantID, sessionID)
	p.sessions[key] = encrypted
	return nil
}

func (p *LocalKMSVaultProvider) RetrieveSession(ctx context.Context, tenantID, sessionID string, maxAge time.Duration) (*StorageState, error) {
	p.mu.RLock()
	defer p.mu.RUnlock()

	key := fmt.Sprintf("%s:%s", tenantID, sessionID)
	data, exists := p.sessions[key]
	if !exists {
		return nil, errors.New("session not found in vault")
	}

	state, err := MultiTenantEnvelopeDecrypt(tenantID, p.masterKEK, data)
	if err != nil {
		return nil, err
	}

	if state.IsExpired(maxAge) {
		return nil, ErrSessionExpired
	}

	return state, nil
}

func (p *LocalKMSVaultProvider) DeleteSession(ctx context.Context, tenantID, sessionID string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	key := fmt.Sprintf("%s:%s", tenantID, sessionID)
	delete(p.sessions, key)
	return nil
}

var (
	ErrEnterpriseLocalKMSProhibited = errors.New("enterprise mode prohibits in-memory LocalKMSVaultProvider under FedRAMP Moderate / SOC 2 CC6.1; configure HashiCorp Vault or Cloud KMS")
	ErrVaultAuthFailed              = errors.New("vault: authentication failed (invalid or missing X-Vault-Token)")
	ErrVaultTransitFailed           = errors.New("vault: transit encrypt/decrypt operation failed")
	ErrCloudKMSUnsupported          = errors.New("cloud KMS: cloud credentials or region not configured")
)

// ValidateEnterpriseVault ensures that enterprise mode never runs with insecure/ephemeral in-memory KMS keys.
func ValidateEnterpriseVault(provider VaultProvider) error {
	if provider == nil {
		return errors.New("vault provider cannot be nil")
	}
	if os.Getenv("KRITIX_ENTERPRISE") == "true" {
		if provider.Type() == "kms_envelope" || provider.Type() == "in_memory" || provider.Type() == "local_kms" {
			return ErrEnterpriseLocalKMSProhibited
		}
	}
	return nil
}

// HashiCorpVaultProvider interacts directly with the HashiCorp Vault HTTP API
// for Transit envelope encryption and KV v2 secret storage.
type HashiCorpVaultProvider struct {
	vaultAddr  string
	vaultToken string
	transitKey string
	kvMount    string
	httpClient *http.Client
}

// NewHashiCorpVaultProvider constructs a real Vault HTTP client.
// Fails closed if VAULT_TOKEN is missing or empty to prevent insecure defaults.
func NewHashiCorpVaultProvider(vaultAddr, transitKey string) (*HashiCorpVaultProvider, error) {
	token := os.Getenv("VAULT_TOKEN")
	if token == "" {
		return nil, errors.New("vault: missing required VAULT_TOKEN environment variable; refusing to start with empty or default credentials")
	}
	return NewHashiCorpVaultProviderWithClient(vaultAddr, token, transitKey, "secret", nil), nil
}

// NewHashiCorpVaultProviderWithClient constructs a real Vault HTTP client with custom options.
func NewHashiCorpVaultProviderWithClient(vaultAddr, vaultToken, transitKey, kvMount string, httpClient *http.Client) *HashiCorpVaultProvider {
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 10 * time.Second}
	}
	if kvMount == "" {
		kvMount = "secret"
	}
	vaultAddr = strings.TrimRight(vaultAddr, "/")
	return &HashiCorpVaultProvider{
		vaultAddr:  vaultAddr,
		vaultToken: vaultToken,
		transitKey: transitKey,
		kvMount:    kvMount,
		httpClient: httpClient,
	}
}

func (h *HashiCorpVaultProvider) Type() string {
	return "hashicorp_vault"
}

func (h *HashiCorpVaultProvider) doVaultRequest(ctx context.Context, method, path string, payload interface{}) (*http.Response, error) {
	if h.vaultAddr == "" {
		return nil, errors.New("vault: missing vaultAddr")
	}
	if ctx == nil {
		ctx = context.Background()
	}

	var bodyReader io.Reader
	if payload != nil {
		b, err := json.Marshal(payload)
		if err != nil {
			return nil, err
		}
		bodyReader = bytes.NewReader(b)
	}

	reqURL := fmt.Sprintf("%s%s", h.vaultAddr, path)
	req, err := http.NewRequestWithContext(ctx, method, reqURL, bodyReader)
	if err != nil {
		return nil, err
	}

	req.Header.Set("X-Vault-Token", h.vaultToken)
	req.Header.Set("Content-Type", "application/json")

	resp, err := h.httpClient.Do(req)
	if err != nil {
		return nil, err
	}

	if resp.StatusCode == http.StatusForbidden || resp.StatusCode == http.StatusUnauthorized {
		resp.Body.Close()
		return nil, ErrVaultAuthFailed
	}

	return resp, nil
}

// TransitEncrypt encrypts plaintext bytes using Vault's Transit secrets engine.
func (h *HashiCorpVaultProvider) TransitEncrypt(ctx context.Context, plaintext []byte) (string, error) {
	key := h.transitKey
	if key == "" {
		key = "kritix-transit-key"
	}
	path := fmt.Sprintf("/v1/transit/encrypt/%s", key)
	b64Plaintext := base64.StdEncoding.EncodeToString(plaintext)

	resp, err := h.doVaultRequest(ctx, http.MethodPost, path, map[string]string{
		"plaintext": b64Plaintext,
	})
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("%w: status %d: %s", ErrVaultTransitFailed, resp.StatusCode, string(b))
	}

	var res struct {
		Data struct {
			Ciphertext string `json:"ciphertext"`
		} `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&res); err != nil {
		return "", err
	}
	if res.Data.Ciphertext == "" {
		return "", errors.New("vault transit returned empty ciphertext")
	}
	return res.Data.Ciphertext, nil
}

// TransitDecrypt decrypts ciphertext using Vault's Transit secrets engine.
func (h *HashiCorpVaultProvider) TransitDecrypt(ctx context.Context, ciphertext string) ([]byte, error) {
	key := h.transitKey
	if key == "" {
		key = "kritix-transit-key"
	}
	path := fmt.Sprintf("/v1/transit/decrypt/%s", key)

	resp, err := h.doVaultRequest(ctx, http.MethodPost, path, map[string]string{
		"ciphertext": ciphertext,
	})
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("%w: status %d: %s", ErrVaultTransitFailed, resp.StatusCode, string(b))
	}

	var res struct {
		Data struct {
			Plaintext string `json:"plaintext"`
		} `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&res); err != nil {
		return nil, err
	}
	return base64.StdEncoding.DecodeString(res.Data.Plaintext)
}

func (h *HashiCorpVaultProvider) StoreSession(ctx context.Context, tenantID, sessionID string, state StorageState) error {
	if state.TTL > MaxAllowedSessionTTL {
		return ErrSessionTTLExceeded
	}
	if state.TTL <= 0 {
		state.TTL = DefaultSessionTTL
	}
	if state.SavedAt.IsZero() {
		state.SavedAt = time.Now()
	}

	stateBytes, err := json.Marshal(state)
	if err != nil {
		return err
	}

	// 1. Encrypt state via real Vault transit
	ciphertext, err := h.TransitEncrypt(ctx, stateBytes)
	if err != nil {
		return fmt.Errorf("failed to transit encrypt session: %w", err)
	}

	// 2. Persist in Vault KV v2 engine
	path := fmt.Sprintf("/v1/%s/data/sessions/%s/%s", h.kvMount, tenantID, sessionID)
	resp, err := h.doVaultRequest(ctx, http.MethodPost, path, map[string]interface{}{
		"data": map[string]interface{}{
			"ciphertext": ciphertext,
			"stored_at":  time.Now().UTC().Format(time.RFC3339),
			"ttl_hours":  state.TTL.Hours(),
		},
	})
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated && resp.StatusCode != http.StatusNoContent {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("vault store session failed (%d): %s", resp.StatusCode, string(b))
	}
	return nil
}

func (h *HashiCorpVaultProvider) RetrieveSession(ctx context.Context, tenantID, sessionID string, maxAge time.Duration) (*StorageState, error) {
	path := fmt.Sprintf("/v1/%s/data/sessions/%s/%s", h.kvMount, tenantID, sessionID)
	resp, err := h.doVaultRequest(ctx, http.MethodGet, path, nil)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return nil, errors.New("session not found in vault")
	}
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("vault retrieve session failed (%d): %s", resp.StatusCode, string(b))
	}

	var kvRes struct {
		Data struct {
			Data struct {
				Ciphertext string `json:"ciphertext"`
			} `json:"data"`
		} `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&kvRes); err != nil {
		return nil, err
	}

	// 2. Decrypt via real Vault transit
	decryptedBytes, err := h.TransitDecrypt(ctx, kvRes.Data.Data.Ciphertext)
	if err != nil {
		return nil, fmt.Errorf("failed to transit decrypt session: %w", err)
	}

	var state StorageState
	if err := json.Unmarshal(decryptedBytes, &state); err != nil {
		return nil, err
	}

	if state.IsExpired(maxAge) {
		return nil, ErrSessionExpired
	}

	return &state, nil
}

func (h *HashiCorpVaultProvider) DeleteSession(ctx context.Context, tenantID, sessionID string) error {
	path := fmt.Sprintf("/v1/%s/metadata/sessions/%s/%s", h.kvMount, tenantID, sessionID)
	resp, err := h.doVaultRequest(ctx, http.MethodDelete, path, nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return nil
}

func (h *HashiCorpVaultProvider) GetSecret(ctx context.Context, secretName string) (string, error) {
	path := fmt.Sprintf("/v1/%s/data/%s", h.kvMount, secretName)
	resp, err := h.doVaultRequest(ctx, http.MethodGet, path, nil)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("vault secret %q not found (%d)", secretName, resp.StatusCode)
	}

	var kvRes struct {
		Data struct {
			Data map[string]interface{} `json:"data"`
		} `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&kvRes); err != nil {
		return "", err
	}

	val, ok := kvRes.Data.Data["value"]
	if !ok || val == nil {
		return "", fmt.Errorf("key 'value' not found in vault secret %q", secretName)
	}
	return fmt.Sprint(val), nil
}

func (h *HashiCorpVaultProvider) SetSecret(name, value string) {
	path := fmt.Sprintf("/v1/%s/data/%s", h.kvMount, name)
	resp, err := h.doVaultRequest(context.Background(), http.MethodPost, path, map[string]interface{}{
		"data": map[string]string{
			"value": value,
		},
	})
	if err == nil && resp != nil {
		resp.Body.Close()
	}
}

// CloudKMSProvider defines the interface for native cloud KMS providers (AWS KMS, GCP Cloud KMS, Azure Key Vault).
type CloudKMSProvider interface {
	VaultProvider
	CloudProviderName() string
}

// AWSSecretsManagerProvider provides AWS Secrets Manager / KMS integration.
type AWSSecretsManagerProvider struct {
	region   string
	kmsKeyID string
}

// NewAWSSecretsManagerProvider constructs an AWS Secrets Manager provider.
func NewAWSSecretsManagerProvider(region, kmsKeyID string) *AWSSecretsManagerProvider {
	return &AWSSecretsManagerProvider{
		region:   region,
		kmsKeyID: kmsKeyID,
	}
}

func (a *AWSSecretsManagerProvider) Type() string {
	return "aws_secrets_manager"
}

func (a *AWSSecretsManagerProvider) CloudProviderName() string {
	return "aws"
}

func (a *AWSSecretsManagerProvider) StoreSession(ctx context.Context, tenantID, sessionID string, state StorageState) error {
	if a.region == "" || a.kmsKeyID == "" {
		return ErrCloudKMSUnsupported
	}
	return ErrCloudKMSUnsupported
}

func (a *AWSSecretsManagerProvider) RetrieveSession(ctx context.Context, tenantID, sessionID string, maxAge time.Duration) (*StorageState, error) {
	if a.region == "" || a.kmsKeyID == "" {
		return nil, ErrCloudKMSUnsupported
	}
	return nil, ErrCloudKMSUnsupported
}

func (a *AWSSecretsManagerProvider) DeleteSession(ctx context.Context, tenantID, sessionID string) error {
	if a.region == "" {
		return ErrCloudKMSUnsupported
	}
	return nil
}

func (a *AWSSecretsManagerProvider) GetSecret(ctx context.Context, secretName string) (string, error) {
	if a.region == "" {
		return "", ErrCloudKMSUnsupported
	}
	return "", ErrCloudKMSUnsupported
}

func (a *AWSSecretsManagerProvider) SetSecret(name, value string) {
	// Unsupported without AWS credentials
}

// GetFigmaTokenFromVault retrieves Figma Personal Access Token securely from the configured vault.
// Prevents exposing Figma credentials in developer configs or git commits.
func GetFigmaTokenFromVault(ctx context.Context, vault VaultProvider) (string, error) {
	if vault == nil {
		return "", errors.New("vault provider cannot be nil")
	}
	return vault.GetSecret(ctx, "figma_access_token")
}

