package auth

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base32"
	"encoding/binary"
	"fmt"
	"strings"
	"time"
)

// GenerateTOTP calculates a standard 6-digit RFC 6238 TOTP passcode from a Base32 secret.
func GenerateTOTP(secretBase32 string, t time.Time) (string, error) {
	// Clean secret (strip spaces, dashes, convert to upper)
	cleanSecret := strings.ToUpper(strings.ReplaceAll(strings.ReplaceAll(secretBase32, " ", ""), "-", ""))

	key, err := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(cleanSecret)
	if err != nil {
		// Try standard padding if unpadded failed
		key, err = base32.StdEncoding.DecodeString(cleanSecret)
		if err != nil {
			return "", fmt.Errorf("invalid base32 TOTP secret: %w", err)
		}
	}

	// 30-second step interval
	interval := uint64(t.Unix() / 30)

	var buf [8]byte
	binary.BigEndian.PutUint64(buf[:], interval)

	mac := hmac.New(sha1.New, key)
	mac.Write(buf[:])
	hash := mac.Sum(nil)

	// Dynamic truncation (RFC 4226)
	offset := hash[len(hash)-1] & 0x0f
	truncatedHash := binary.BigEndian.Uint32(hash[offset:offset+4]) & 0x7fffffff

	code := truncatedHash % 1000000
	return fmt.Sprintf("%06d", code), nil
}
