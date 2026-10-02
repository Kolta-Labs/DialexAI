package store

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"os"
	"path/filepath"

	"golang.org/x/crypto/bcrypt"

	"socratix/pkg/model"
)

// secretBox encrypts just the API keys before they touch disk. AES-256-GCM with a random
// per-install key. The key lives in the OS keychain when one is available (see keyring.go);
// otherwise, or with DIALEX_KEY_STORAGE=file, in a 0600 sibling file — a copy of state.json
// alone (backup, sync, screen-share) never hands over live API keys. A key found in an old
// key file is migrated into the keychain and the file removed.
// On-disk shape: a base64 blob of IV+ciphertext per key, so a legacy state.json's
// already-encrypted keys decrypt too, not just plaintext ones.
type secretBox struct {
	key []byte
}

func newSecretBox(keyFile string) (*secretBox, error) {
	if k, err := keyringGet(keyFile); err == nil && len(k) == 32 {
		return &secretBox{key: k}, nil
	}
	key, err := os.ReadFile(keyFile)
	fromFile := err == nil
	if !fromFile {
		if !os.IsNotExist(err) {
			return nil, err
		}
		key = make([]byte, 32)
		if _, err := rand.Read(key); err != nil {
			return nil, err
		}
	}
	if keyringSet(keyFile, key) == nil {
		if got, err := keyringGet(keyFile); err == nil && bytes.Equal(got, key) {
			if fromFile {
				_ = os.Remove(keyFile)
			}
			return &secretBox{key: key}, nil
		}
	}
	if !fromFile {
		if err := os.WriteFile(keyFile, key, 0o600); err != nil {
			return nil, err
		}
	}
	return &secretBox{key: key}, nil
}

// encrypt fails closed: on any cipher or RNG error it returns an error so Save writes nothing,
// rather than persisting the API key in plaintext.
func (b *secretBox) encrypt(plain string) (string, error) {
	if plain == "" {
		return "", nil
	}
	block, err := aes.NewCipher(b.key)
	if err != nil {
		return "", fmt.Errorf("encrypt api key: %w", err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", fmt.Errorf("encrypt api key: %w", err)
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", fmt.Errorf("encrypt api key: %w", err)
	}
	ciphertext := gcm.Seal(nonce, nonce, []byte(plain), nil)
	return base64.StdEncoding.EncodeToString(ciphertext), nil
}

// decrypt returns the plaintext for an encrypted blob. Anything that doesn't decrypt
// cleanly (most notably: a plaintext key from before encryption existed, or a key
// encrypted under a different install's key) is returned as-is rather than dropped — it
// still works as a key, and gets encrypted properly the next time Save runs. Never lose a
// user's key over a migration.
func (b *secretBox) decrypt(blob string) string {
	if blob == "" {
		return ""
	}
	data, err := base64.StdEncoding.DecodeString(blob)
	if err != nil {
		return blob
	}
	block, err := aes.NewCipher(b.key)
	if err != nil {
		return blob
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return blob
	}
	if len(data) < gcm.NonceSize() {
		return blob
	}
	nonce, ciphertext := data[:gcm.NonceSize()], data[gcm.NonceSize():]
	plain, err := gcm.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return blob
	}
	return string(plain)
}

func encryptApiKeys(k model.ApiKeys, b *secretBox) (model.ApiKeys, error) {
	var out model.ApiKeys
	for _, f := range []struct {
		in  string
		out *string
	}{
		{k.Anthropic, &out.Anthropic}, {k.OpenAI, &out.OpenAI}, {k.Gemini, &out.Gemini},
		{k.Grok, &out.Grok}, {k.DeepSeek, &out.DeepSeek}, {k.Mistral, &out.Mistral},
	} {
		v, err := b.encrypt(f.in)
		if err != nil {
			return model.ApiKeys{}, err
		}
		*f.out = v
	}
	return out, nil
}

func decryptApiKeys(k model.ApiKeys, b *secretBox) model.ApiKeys {
	return model.ApiKeys{
		Anthropic: b.decrypt(k.Anthropic),
		OpenAI:    b.decrypt(k.OpenAI),
		Gemini:    b.decrypt(k.Gemini),
		Grok:      b.decrypt(k.Grok),
		DeepSeek:  b.decrypt(k.DeepSeek),
		Mistral:   b.decrypt(k.Mistral),
	}
}

// HashPassword bcrypt-hashes a plaintext password for storage in model.User.PasswordHash.
func HashPassword(plain string) (string, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(plain), bcrypt.DefaultCost)
	if err != nil {
		return "", err
	}
	return string(hash), nil
}

// VerifyPassword reports whether plain matches the bcrypt hash.
func VerifyPassword(hash, plain string) bool {
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(plain)) == nil
}

// JWTSecret returns a persistent 32-byte secret for HS256 JWT signing, saved at
// .jwt.key inside the store's directory. Reused across process restarts so running
// clients don't get 401 invalid signature errors.
func (s *Store) JWTSecret() ([]byte, error) {
	keyFile := filepath.Join(s.dir, ".jwt.key")
	if data, err := os.ReadFile(keyFile); err == nil && len(data) == 32 {
		return data, nil
	}
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		return nil, err
	}
	if err := os.WriteFile(keyFile, key, 0o600); err != nil {
		return nil, err
	}
	return key, nil
}
