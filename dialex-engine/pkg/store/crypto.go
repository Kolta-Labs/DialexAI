package store

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"os"
	"path/filepath"

	"golang.org/x/crypto/bcrypt"

	"dialex/pkg/model"
)

// secretBox encrypts just the API keys before they touch disk. AES-256-GCM with a random
// per-install key kept in a sibling file — a copy of state.json alone (backup, sync,
// screen-share) no longer hands over live API keys, only this separate key file does.
// Ported from the Kotlin AppStore's SecretBox — same algorithm, same on-disk shape (a
// base64 blob of IV+ciphertext per key), so a legacy state.json's already-encrypted keys
// decrypt here too, not just plaintext ones.
//
// ponytail: a software key on disk, not an OS keychain/hardware-backed secret — a real step
// up from plaintext, not the final word (see ENGINE_SPEC_REVIEW.md / round-2 decisions).
type secretBox struct {
	key []byte
}

func newSecretBox(keyFile string) (*secretBox, error) {
	if _, err := os.Stat(keyFile); os.IsNotExist(err) {
		key := make([]byte, 32)
		if _, err := rand.Read(key); err != nil {
			return nil, err
		}
		if err := os.WriteFile(keyFile, key, 0o600); err != nil {
			return nil, err
		}
		return &secretBox{key: key}, nil
	}
	key, err := os.ReadFile(keyFile)
	if err != nil {
		return nil, err
	}
	return &secretBox{key: key}, nil
}

func (b *secretBox) encrypt(plain string) string {
	if plain == "" {
		return ""
	}
	block, err := aes.NewCipher(b.key)
	if err != nil {
		return plain // key is malformed — fail open rather than lose the value entirely
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return plain
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return plain
	}
	ciphertext := gcm.Seal(nonce, nonce, []byte(plain), nil)
	return base64.StdEncoding.EncodeToString(ciphertext)
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

func encryptApiKeys(k model.ApiKeys, b *secretBox) model.ApiKeys {
	return model.ApiKeys{
		Anthropic: b.encrypt(k.Anthropic),
		OpenAI:    b.encrypt(k.OpenAI),
		Gemini:    b.encrypt(k.Gemini),
		Grok:      b.encrypt(k.Grok),
		DeepSeek:  b.encrypt(k.DeepSeek),
		Mistral:   b.encrypt(k.Mistral),
	}
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
