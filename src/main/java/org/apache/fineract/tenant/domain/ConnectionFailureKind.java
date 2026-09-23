/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.domain;

import java.sql.SQLException;
import java.util.Locale;

/**
 * Why a database connection or a schema creation failed.
 *
 * <p>An administrator's next step differs completely between these: a rejected password is theirs
 * to correct, a missing privilege belongs to whoever administers the database server, and an
 * unreachable host is usually neither. Reporting one message for all three, as this plugin first
 * did, leaves them guessing.
 *
 * <p>Classification reads the {@code SQLState} rather than the driver's message text. States are
 * standardised by class - {@code 08} is connection, {@code 28} is authorization - so the common
 * cases need no vendor knowledge. The two that do are called out below, because PostgreSQL and
 * MySQL disagree about which state covers a missing database.
 *
 * <p>The classification is all that leaves the server. The driver's own message is not: those
 * routinely echo the JDBC URL and user back, so they stay in the log.
 */
public enum ConnectionFailureKind {

    /** The server answered and refused the username or password. */
    CREDENTIALS_REJECTED,

    /** The credentials were accepted, but the user may not do this. */
    INSUFFICIENT_PRIVILEGE,

    /** The server answered, but has no database of that name. */
    SCHEMA_ABSENT,

    /** The server could not be reached at all, or failed in a way worth no finer claim. */
    UNREACHABLE;

    /** MySQL and MariaDB: access denied for this user to this database. */
    private static final int MYSQL_ACCESS_DENIED_FOR_DATABASE = 1044;

    /** MySQL and MariaDB: unknown database. */
    private static final int MYSQL_UNKNOWN_DATABASE = 1049;

    /** PostgreSQL: the role lacks a privilege the statement needs. */
    private static final String POSTGRES_INSUFFICIENT_PRIVILEGE = "42501";

    /** PostgreSQL: no database of that name. */
    private static final String POSTGRES_INVALID_CATALOG_NAME = "3D000";

    /**
     * Classifies a driver failure, walking the whole chain.
     *
     * <p>Both chains are followed. Drivers wrap through {@link Throwable#getCause()}, and JDBC has
     * a second chain of its own through {@link SQLException#getNextException()} that {@code
     * getCause} does not reach. The first link that says something definite wins; a chain that says
     * nothing definite is {@link #UNREACHABLE}, which is the same conservative answer this plugin
     * gave before it classified anything.
     */
    public static ConnectionFailureKind from(final Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                for (SQLException link = sqlException;
                        link != null;
                        link = link.getNextException()) {
                    final ConnectionFailureKind kind = fromOne(link);
                    if (kind != UNREACHABLE) {
                        return kind;
                    }
                    if (link == link.getNextException()) {
                        // A driver that points a link at itself would otherwise loop forever.
                        break;
                    }
                }
            }
        }
        return UNREACHABLE;
    }

    private static ConnectionFailureKind fromOne(final SQLException failure) {
        final int vendorCode = failure.getErrorCode();
        if (vendorCode == MYSQL_UNKNOWN_DATABASE) {
            return SCHEMA_ABSENT;
        }
        if (vendorCode == MYSQL_ACCESS_DENIED_FOR_DATABASE) {
            return INSUFFICIENT_PRIVILEGE;
        }

        final String state = failure.getSQLState();
        if (state == null || state.length() < 2) {
            return UNREACHABLE;
        }
        final String normalised = state.toUpperCase(Locale.ROOT);
        if (POSTGRES_INVALID_CATALOG_NAME.equals(normalised)) {
            return SCHEMA_ABSENT;
        }
        if (POSTGRES_INSUFFICIENT_PRIVILEGE.equals(normalised)) {
            return INSUFFICIENT_PRIVILEGE;
        }
        return switch (normalised.substring(0, 2)) {
            // 28 is "invalid authorization specification" - PostgreSQL's 28P01 for a bad
            // password and MySQL's 28000 for access denied both land here.
            case "28" -> CREDENTIALS_REJECTED;
            // 08 is "connection exception": host down, port closed, link lost mid-connect.
            case "08" -> UNREACHABLE;
            default -> UNREACHABLE;
        };
    }
}
