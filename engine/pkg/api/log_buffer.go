package api

import (
	"bytes"
	"fmt"
	"io"
	"strings"
	"sync"
	"time"
)

// LogEntry represents a single captured log message with timestamp.
type LogEntry struct {
	Timestamp string `json:"timestamp"`
	Message   string `json:"message"`
	Level     string `json:"level"`
}

// LogRingBuffer is a thread-safe circular in-memory buffer for server logs.
type LogRingBuffer struct {
	mu       sync.RWMutex
	capacity int
	entries  []LogEntry
	buf      bytes.Buffer
}

// GlobalLogBuffer holds recent server logs across the engine.
var GlobalLogBuffer = NewLogRingBuffer(500)

// NewLogRingBuffer creates a buffer with the given capacity.
func NewLogRingBuffer(capacity int) *LogRingBuffer {
	if capacity <= 0 {
		capacity = 500
	}
	return &LogRingBuffer{
		capacity: capacity,
		entries:  make([]LogEntry, 0, capacity),
	}
}

// Write implements io.Writer so the standard logger can pipe output directly here.
func (rb *LogRingBuffer) Write(p []byte) (n int, err error) {
	rb.mu.Lock()
	defer rb.mu.Unlock()

	n = len(p)
	rb.buf.Write(p)

	for {
		line, err := rb.buf.ReadString('\n')
		if err != nil {
			// Incomplete line remains in buffer
			rb.buf.WriteString(line)
			break
		}
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}

		level := "INFO"
		upper := strings.ToUpper(line)
		if strings.Contains(upper, "ERROR") || strings.Contains(upper, "FAIL") {
			level = "ERROR"
		} else if strings.Contains(upper, "WARN") {
			level = "WARN"
		} else if strings.Contains(upper, "DEBUG") {
			level = "DEBUG"
		}

		entry := LogEntry{
			Timestamp: time.Now().UTC().Format(time.RFC3339),
			Message:   line,
			Level:     level,
		}

		if len(rb.entries) >= rb.capacity {
			// Drop oldest
			rb.entries = rb.entries[1:]
		}
		rb.entries = append(rb.entries, entry)
	}

	return n, nil
}

// Add appends an explicit log entry to the ring buffer.
func (rb *LogRingBuffer) Add(level, tag, message string) {
	if rb == nil {
		return
	}
	rb.mu.Lock()
	defer rb.mu.Unlock()

	formattedMsg := message
	if tag != "" {
		formattedMsg = fmt.Sprintf("[%s] %s", tag, message)
	}

	entry := LogEntry{
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		Message:   formattedMsg,
		Level:     strings.ToUpper(level),
	}

	if len(rb.entries) >= rb.capacity {
		rb.entries = rb.entries[1:]
	}
	rb.entries = append(rb.entries, entry)
}

// Entries returns a copy of the stored log entries (oldest first).
func (rb *LogRingBuffer) Entries() []LogEntry {
	rb.mu.RLock()
	defer rb.mu.RUnlock()

	copied := make([]LogEntry, len(rb.entries))
	copy(copied, rb.entries)
	return copied
}

// Clear empties the buffer.
func (rb *LogRingBuffer) Clear() {
	rb.mu.Lock()
	defer rb.mu.Unlock()
	rb.entries = rb.entries[:0]
	rb.buf.Reset()
}

// LogWriter wraps an existing writer (like os.Stderr) and also sends output to the ring buffer.
func MultiLogWriter(w io.Writer, rb *LogRingBuffer) io.Writer {
	if rb == nil {
		return w
	}
	return io.MultiWriter(w, rb)
}

// AppendDirect adds a structured entry directly.
func (rb *LogRingBuffer) AppendDirect(level, msg string) {
	rb.mu.Lock()
	defer rb.mu.Unlock()

	entry := LogEntry{
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		Message:   fmt.Sprintf("[%s] %s", level, msg),
		Level:     level,
	}
	if len(rb.entries) >= rb.capacity {
		rb.entries = rb.entries[1:]
	}
	rb.entries = append(rb.entries, entry)
}
