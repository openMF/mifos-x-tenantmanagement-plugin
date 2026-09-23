/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.apache.fineract.tenant.data.TenantConnectionProbe;
import org.apache.fineract.tenant.domain.ConnectionFailureKind;
import org.apache.fineract.tenant.exception.TenantConnectionFailedException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What a probe reports, against a real PostgreSQL rather than a mocked driver.
 *
 * <p>The classification in {@link ConnectionFailureKind} is unit tested on hand-built {@code
 * SQLException}s, which proves the mapping but not the states the driver actually reports. These
 * cases close that gap: an absent database and a rejected password have to be told apart by what
 * PostgreSQL really answers, and before MX-421 they were not told apart at all.
 *
 * <p>The case that matters most is {@link #anAbsentDatabaseIsReportedAsAbsentNotAsBadCredentials}.
 * It is the ordinary state while an administrator is filling in the create form, and it used to be
 * indistinguishable from getting the password wrong.
 */
@Testcontainers
class TenantProvisioningProbePostgresIntegrationTest {

    private static PostgreSQLContainer postgres;
    private static HikariDataSource tenantStore;
    private static TenantProvisioningService provisioningService;

    private static final String PRESENT_DATABASE = "fineract_present";
    private static final String ABSENT_DATABASE = "fineract_absent";

    @BeforeAll
    static void startDatabase() throws SQLException {
        postgres =
                new PostgreSQLContainer("postgres:15-alpine")
                        .withDatabaseName("fineract_tenants")
                        .withUsername("postgres")
                        .withPassword("postgres");
        postgres.start();

        final HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(2);
        tenantStore = new HikariDataSource(config);

        try (Connection connection = tenantStore.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + PRESENT_DATABASE);
        }

        provisioningService = new TenantProvisioningService(tenantStore);
    }

    @AfterAll
    static void stopDatabase() {
        if (tenantStore != null) {
            tenantStore.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void aDatabaseThatExistsAnswersEverything() {
        final TenantConnectionProbe probe = probe(PRESENT_DATABASE, "postgres");

        assertTrue(probe.reachable());
        assertTrue(probe.serverReachable());
        assertTrue(probe.credentialsAccepted());
        assertTrue(probe.schemaPresent());
    }

    @Test
    void anAbsentDatabaseIsReportedAsAbsentNotAsBadCredentials() {
        // The state an administrator is in while filling in the create form. Before MX-421 the
        // endpoint answered `reachable: false` here and said nothing more, which is the same
        // answer it gave for a wrong password.
        final TenantConnectionProbe probe = probe(ABSENT_DATABASE, "postgres");

        assertFalse(probe.reachable());
        assertTrue(probe.serverReachable());
        assertTrue(probe.credentialsAccepted(), "the credentials were good and should say so");
        assertFalse(probe.schemaPresent());
    }

    @Test
    void aRejectedPasswordIsReportedAsRejected() {
        final TenantConnectionProbe probe = probe(PRESENT_DATABASE, "not-the-password");

        assertFalse(probe.reachable());
        assertTrue(probe.serverReachable(), "the server answered, it just refused us");
        assertFalse(probe.credentialsAccepted());
        assertFalse(probe.schemaPresent());
    }

    @Test
    void aWrongPasswordForAnAbsentDatabaseIsStillReportedAsRejected() {
        // Both things are wrong at once; the credentials are the one to report, since nothing can
        // be learned about a database on a server that will not let us in.
        final TenantConnectionProbe probe = probe(ABSENT_DATABASE, "not-the-password");

        assertFalse(probe.credentialsAccepted());
    }

    @Test
    void anUnreachableServerIsReportedAsUnreachable() {
        final TenantConnectionProbe probe =
                provisioningService.probe(
                        postgres.getHost(),
                        // A port nothing listens on.
                        "1",
                        PRESENT_DATABASE,
                        null,
                        "postgres",
                        "postgres");

        assertFalse(probe.reachable());
        assertFalse(probe.serverReachable());
        assertFalse(probe.credentialsAccepted());
    }

    @Test
    void creatingAgainstARejectedPasswordSaysSoRatherThanBlamingTheConnection() {
        // The create path: the same classification has to reach the exception a caller sees.
        final TenantConnectionFailedException failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        TenantConnectionFailedException.class,
                        () ->
                                provisioningService.createSchemaIfAbsent(
                                        postgres.getHost(),
                                        String.valueOf(postgres.getFirstMappedPort()),
                                        "fineract_never_created",
                                        null,
                                        "postgres",
                                        "not-the-password"));

        assertEquals(ConnectionFailureKind.CREDENTIALS_REJECTED, failure.getKind());
        assertFalse(
                failure.getMessage().contains("not-the-password"),
                "the credentials must never reach the message");
    }

    private static TenantConnectionProbe probe(final String schemaName, final String password) {
        return provisioningService.probe(
                postgres.getHost(),
                String.valueOf(postgres.getFirstMappedPort()),
                schemaName,
                null,
                "postgres",
                password);
    }
}
