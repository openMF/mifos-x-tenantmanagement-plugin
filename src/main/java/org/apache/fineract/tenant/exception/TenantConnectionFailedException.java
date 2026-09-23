/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.exception;

import org.apache.fineract.infrastructure.core.exception.AbstractPlatformDomainRuleException;
import org.apache.fineract.tenant.domain.ConnectionFailureKind;

/**
 * Thrown when the database behind a tenant cannot be used with the supplied details.
 *
 * <p>Each cause carries its own globalisation code, because an administrator's next step differs
 * completely between them: a rejected password is theirs to fix, a missing privilege belongs to
 * whoever administers the database server, and an unreachable host is usually neither. The plugin
 * first reported all of them as "could not connect", which said nothing about which had happened.
 *
 * <p>The message names the host, port and schema that were tried but never the credentials, and the
 * underlying {@link java.sql.SQLException} is attached as the cause for the server log rather than
 * folded into the user message: driver errors routinely echo the connection URL and user back, and
 * infrastructure detail must stay out of API responses. The classification is safe to return
 * precisely because it is a classification and not the driver's text.
 */
public class TenantConnectionFailedException extends AbstractPlatformDomainRuleException {

    /**
     * Held here instead of passed to {@code initCause}.
     *
     * <p>Fineract's {@code AbstractPlatformException} constructs through {@code
     * RuntimeException(String, Throwable)}, which marks the cause as already set, so a later {@code
     * initCause} throws "Can't overwrite cause" - turning a clean domain error into a 500. This
     * holds on 1.15 and 1.16 alike; it went unnoticed because every test mocked the services that
     * throw this exception, and surfaced only against a running Fineract. Overriding {@link
     * #getCause()} chains the cause without that conflict, so loggers still print "Caused by".
     */
    private final Throwable underlyingCause;

    /** Which of the causes this was, for a caller that wants to branch rather than re-parse. */
    private final ConnectionFailureKind kind;

    @Override
    public Throwable getCause() {
        return underlyingCause;
    }

    public ConnectionFailureKind getKind() {
        return kind;
    }

    private TenantConnectionFailedException(
            final ConnectionFailureKind kind,
            final String code,
            final String message,
            final Throwable cause,
            final Object... args) {
        super(code, message, args);
        this.underlyingCause = cause;
        this.kind = kind;
    }

    /**
     * Classifies a driver failure and describes it accordingly.
     *
     * <p>The single entry point, so a caller cannot accidentally report a rejected password as an
     * unreachable host by picking the wrong factory.
     */
    public static TenantConnectionFailedException from(
            final String schemaServer,
            final String schemaServerPort,
            final String schemaName,
            final Throwable cause) {

        final ConnectionFailureKind kind = ConnectionFailureKind.from(cause);
        final String where = schemaServer + ":" + schemaServerPort;
        return switch (kind) {
            case CREDENTIALS_REJECTED ->
                    new TenantConnectionFailedException(
                            kind,
                            "error.msg.tenant.connection.credentials.rejected",
                            "The database server at "
                                    + where
                                    + " rejected the supplied credentials",
                            cause,
                            schemaServer,
                            schemaServerPort,
                            schemaName);
            case INSUFFICIENT_PRIVILEGE ->
                    new TenantConnectionFailedException(
                            kind,
                            "error.msg.tenant.schema.creation.not.permitted",
                            "The database user is not permitted to create "
                                    + schemaName
                                    + " on "
                                    + where
                                    + "; it needs permission to create databases, or the"
                                    + " database must be created before the tenant",
                            cause,
                            schemaServer,
                            schemaServerPort,
                            schemaName);
            case SCHEMA_ABSENT ->
                    new TenantConnectionFailedException(
                            kind,
                            "error.msg.tenant.schema.absent",
                            "The database server at " + where + " has no database " + schemaName,
                            cause,
                            schemaServer,
                            schemaServerPort,
                            schemaName);
            case UNREACHABLE ->
                    new TenantConnectionFailedException(
                            kind,
                            "error.msg.tenant.connection.failed",
                            "Could not connect to "
                                    + schemaName
                                    + " at "
                                    + schemaServer
                                    + ":"
                                    + schemaServerPort,
                            cause,
                            schemaServer,
                            schemaServerPort,
                            schemaName);
        };
    }
}
