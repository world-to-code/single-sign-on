package com.example.sso.email;

/**
 * How a tenant's mail leaves the building.
 *
 * <p>A code-bound enum rather than free text because each value implies a transport, a credential shape and a
 * request format that only the matching client knows how to build; a value with no client would be a setting
 * that saves happily and then sends nothing.
 */
public enum EmailProvider {

    /** A relay the tenant operates or rents, reached on a submission port. */
    SMTP,

    /** Resend's HTTPS API — reachable where outbound submission ports are blocked, which is common. */
    RESEND
}
