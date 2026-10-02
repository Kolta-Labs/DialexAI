package store

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func fakeKeyring(t *testing.T) map[string][]byte {
	t.Helper()
	m := map[string][]byte{}
	sg, gg := keyringSet, keyringGet
	keyringSet = func(a string, k []byte) error { m[a] = k; return nil }
	keyringGet = func(a string) ([]byte, error) {
		if k, ok := m[a]; ok {
			return k, nil
		}
		return nil, errors.New("missing")
	}
	t.Cleanup(func() { keyringSet, keyringGet = sg, gg })
	return m
}

func TestSecretBoxUsesKeyringAndWritesNoFile(t *testing.T) {
	m := fakeKeyring(t)
	f := filepath.Join(t.TempDir(), ".k")
	b, err := newSecretBox(f)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(f); !os.IsNotExist(err) {
		t.Fatal("key file must not exist when keyring works")
	}
	enc, err := b.encrypt("sk-abc")
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(m[f], b.key) || b.decrypt(enc) != "sk-abc" {
		t.Fatal("key not in keyring or round trip failed")
	}
	b2, _ := newSecretBox(f)
	if !bytes.Equal(b2.key, b.key) {
		t.Fatal("second open must reuse the keyring key")
	}
}

func TestSecretBoxMigratesFileKeyToKeyring(t *testing.T) {
	m := fakeKeyring(t)
	f := filepath.Join(t.TempDir(), ".k")
	old := bytes.Repeat([]byte{7}, 32)
	os.WriteFile(f, old, 0o600)
	b, _ := newSecretBox(f)
	if !bytes.Equal(b.key, old) || !bytes.Equal(m[f], old) {
		t.Fatal("legacy key must be kept and moved")
	}
	if _, err := os.Stat(f); !os.IsNotExist(err) {
		t.Fatal("file should be removed after migration")
	}
}

func TestSecretBoxFallsBackToFile(t *testing.T) {
	sg, gg := keyringSet, keyringGet
	keyringSet = func(string, []byte) error { return errors.New("no keyring") }
	keyringGet = func(string) ([]byte, error) { return nil, errors.New("no keyring") }
	t.Cleanup(func() { keyringSet, keyringGet = sg, gg })
	f := filepath.Join(t.TempDir(), ".k")
	b, err := newSecretBox(f)
	if err != nil {
		t.Fatal(err)
	}
	if k, _ := os.ReadFile(f); !bytes.Equal(k, b.key) {
		t.Fatal("file fallback must persist the key")
	}
}
