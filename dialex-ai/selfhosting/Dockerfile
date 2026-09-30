# ==============================================================================
# Dialex Engine Production Dockerfile (Multi-Stage Minimal Build)
# ==============================================================================

# Build Stage
FROM golang:1.22-alpine AS builder

WORKDIR /app

# Install build tools and SSL certificates
RUN apk add --no-cache git ca-certificates

# Cache dependencies
COPY engine/go.mod engine/go.sum ./
RUN go mod download

# Copy source code and build statically linked binary
COPY engine/ ./
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /app/dialex ./cmd/dialex

# Final Production Stage
FROM alpine:3.20

RUN apk add --no-cache ca-certificates tzdata

WORKDIR /root/

# Copy binary from builder
COPY --from=builder /app/dialex /usr/local/bin/dialex

# Create config storage directory
RUN mkdir -p /root/.config/dialex && chmod 700 /root/.config/dialex

# Expose default HTTP/SSE port
EXPOSE 7890

# Health check
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:7890/health || exit 1

# Environment defaults
ENV DIALEX_HOST=0.0.0.0:7890 \
    DIALEX_PORT=7890 \
    DIALEX_ALLOW_INSECURE_LAN=true \
    DIALEX_CONFIG_DIR=/root/.config/dialex

VOLUME ["/root/.config/dialex"]

ENTRYPOINT ["dialex", "serve"]
