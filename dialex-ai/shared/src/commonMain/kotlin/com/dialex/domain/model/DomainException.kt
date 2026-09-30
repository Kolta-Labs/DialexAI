package com.dialex.domain.model

/**
 * Sealed hierarchy of domain-level exceptions. Every [DataSource][com.dialex.data.datasource]
 * maps raw network/IO errors to one of these at its boundary — nothing above the DataSource
 * ever sees a raw Ktor [ClientRequestException] or [IOException].
 */
sealed class DomainException(message: String? = null, cause: Throwable? = null) :
    Exception(message, cause) {
    /** Lost connectivity (no response from server). */
    class NoConnectivity(cause: Throwable? = null) :
        DomainException("No connection to engine", cause)

    /** 401 / expired or missing JWT. */
    class Unauthorized(cause: Throwable? = null) :
        DomainException("Authentication required — please reconnect", cause)

    /** 404 — the resource no longer exists (deleted on another device, etc.). */
    class NotFound(message: String? = null, cause: Throwable? = null) :
        DomainException(message ?: "Resource not found", cause)

    /** 409 / 422 — the server rejected the request for a business-logic reason. */
    class InvalidRequest(message: String, cause: Throwable? = null) :
        DomainException(message, cause)

    /** Any other server error. */
    class Unknown(message: String? = null, cause: Throwable? = null) :
        DomainException(message ?: "An unexpected error occurred", cause)
}
