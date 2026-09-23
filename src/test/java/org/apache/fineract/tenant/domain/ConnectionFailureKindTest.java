/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.domain;

import static org.apache.fineract.tenant.domain.ConnectionFailureKind.CREDENTIALS_REJECTED;
import static org.apache.fineract.tenant.domain.ConnectionFailureKind.INSUFFICIENT_PRIVILEGE;
import static org.apache.fineract.tenant.domain.ConnectionFailureKind.SCHEMA_ABSENT;
import static org.apache.fineract.tenant.domain.ConnectionFailureKind.UNREACHABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class ConnectionFailureKindTest {

    @Test
    void postgresReportsARejectedPassword() {
        // 28P01, invalid_password.
        assertEquals(
                CREDENTIALS_REJECTED,
                ConnectionFailureKind.from(
                        new SQLException(
                                "FATAL: password authentication failed for user \"postgres\"",
                                "28P01")));
    }

    @Test
    void mysqlReportsARejectedPassword() {
        // Connector/J maps "Access denied for user" to the same 28 class.
        assertEquals(
                CREDENTIALS_REJECTED,
                ConnectionFailureKind.from(
                        new SQLException("Access denied for user 'fineract'", "28000", 1045)));
    }

    @Test
    void postgresReportsAMissingPrivilege() {
        // 42501, insufficient_privilege - the role may not CREATE DATABASE.
        assertEquals(
                INSUFFICIENT_PRIVILEGE,
                ConnectionFailureKind.from(
                        new SQLException("ERROR: permission denied to create database", "42501")));
    }

    @Test
    void mysqlReportsAMissingPrivilegeByVendorCode() {
        // MySQL folds access-denied-to-database and unknown-database into one SQLState, so only
        // the vendor code tells them apart.
        assertEquals(
                INSUFFICIENT_PRIVILEGE,
                ConnectionFailureKind.from(
                        new SQLException(
                                "Access denied for user 'fineract' to database 'acme'",
                                "42000",
                                1044)));
    }

    @Test
    void postgresReportsAMissingDatabase() {
        // 3D000, invalid_catalog_name.
        assertEquals(
                SCHEMA_ABSENT,
                ConnectionFailureKind.from(
                        new SQLException("FATAL: database \"acme\" does not exist", "3D000")));
    }

    @Test
    void mysqlReportsAMissingDatabaseByVendorCode() {
        assertEquals(
                SCHEMA_ABSENT,
                ConnectionFailureKind.from(
                        new SQLException("Unknown database 'acme'", "42000", 1049)));
    }

    @Test
    void aConnectionClassFailureIsUnreachable() {
        assertEquals(
                UNREACHABLE,
                ConnectionFailureKind.from(
                        new SQLException("Connection to db:5432 refused", "08001")));
    }

    @Test
    void anUnrecognisedFailureClaimsNothingFiner() {
        // The conservative answer, and the one this plugin gave for everything before it
        // classified anything at all.
        assertEquals(UNREACHABLE, ConnectionFailureKind.from(new SQLException("something odd")));
        assertEquals(UNREACHABLE, ConnectionFailureKind.from(new SQLException("odd", "99999")));
        assertEquals(UNREACHABLE, ConnectionFailureKind.from(new IllegalStateException("not sql")));
        assertEquals(UNREACHABLE, ConnectionFailureKind.from(null));
    }

    @Test
    void itLooksThroughAWrappingException() {
        // Pools and Liquibase both wrap the driver's exception before it reaches us.
        final SQLException driverError =
                new SQLException("password authentication failed", "28P01");

        assertEquals(
                CREDENTIALS_REJECTED,
                ConnectionFailureKind.from(
                        new IllegalStateException("could not open", driverError)));
    }

    @Test
    void itFollowsTheJdbcNextExceptionChain() {
        // getNextException is a second chain that getCause does not reach.
        final SQLException first = new SQLException("connection error", "HY000");
        first.setNextException(new SQLException("password authentication failed", "28P01"));

        assertEquals(CREDENTIALS_REJECTED, ConnectionFailureKind.from(first));
    }

    @Test
    void aSelfReferencingChainDoesNotLoop() {
        // A driver that points a link at itself must not hang the request.
        final SQLException looping = new SQLException("odd", "HY000");
        looping.setNextException(looping);

        assertEquals(UNREACHABLE, ConnectionFailureKind.from(looping));
    }
}
