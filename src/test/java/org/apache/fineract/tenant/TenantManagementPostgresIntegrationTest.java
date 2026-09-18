/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.tenant.data.TenantData;
import org.apache.fineract.tenant.domain.TenantStatus;
import org.apache.fineract.tenant.exception.TenantNotFoundException;
import org.apache.fineract.tenant.security.TenantMasterAccess;
import org.apache.fineract.tenant.security.TenantMasterUserBootstrap;
import org.apache.fineract.tenant.security.TenantMasterUserStore;
import org.apache.fineract.tenant.service.TenantManagementReadService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Exercises the tenant registry against a real PostgreSQL tenant store.
 *
 * <p>Unit tests cover the rules; this covers what only a database can answer - that the Liquibase
 * changesets apply, that the SQL is valid PostgreSQL, and that the read side sees what the registry
 * holds.
 *
 * <p><strong>Scope.</strong> Tenants are seeded with SQL rather than through an API, because this
 * slice adds only the read side; the write path arrives with tenant creation. The HTTP surface and
 * the security chain are covered by {@code TenantManagementApiIntegrationTest}, which boots a real
 * Fineract.
 */
@Testcontainers
class TenantManagementPostgresIntegrationTest {

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;

    private TenantManagementReadService readService;
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startDatabase() throws Exception {
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
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);

        applyRegistryBaseline(dataSource);
        applyTenantManagementChangelog(dataSource);
    }

    /**
     * Builds the registry the way Fineract ships it.
     *
     * <p>Runs core's own initial-switch changelog straight out of the {@code fineract-provider}
     * jar, so the {@code tenants} and {@code tenant_server_connections} tables under test are
     * genuinely core's and not a hand-written approximation that could drift.
     */
    private static void applyRegistryBaseline(final DataSource dataSource) throws Exception {
        final SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(
                "classpath:/db/changelog/tenant-store/initial-switch-changelog-tenant-store.xml");
        liquibase.setContexts("initial_switch,tenant_store_db");
        // Core's initial-data changeset seeds the 'default' tenant from these placeholders,
        // which the platform normally supplies from fineract.tenant.* configuration. Left
        // unset they are inserted literally and overflow the column.
        liquibase.setChangeLogParameters(
                Map.ofEntries(
                        Map.entry("fineract.tenant.identifier", "default"),
                        Map.entry("fineract.tenant.description", "Default Demo Tenant"),
                        Map.entry("fineract.tenant.timezone", "Asia/Kolkata"),
                        Map.entry("fineract.tenant.host", "localhost"),
                        Map.entry("fineract.tenant.port", "5432"),
                        Map.entry("fineract.tenant.schema-name", "fineract_default"),
                        Map.entry("fineract.tenant.username", "postgres"),
                        Map.entry("fineract.tenant.password", "postgres"),
                        Map.entry("fineract.tenant.parameters", "")));
        liquibase.setShouldRun(true);
        liquibase.afterPropertiesSet();

        // Core seeds the default tenant with explicit ids, which leaves PostgreSQL's
        // identity sequences pointing at 1. Core corrects that in part 0003; without it
        // the first generated id collides with the seeded row. Run separately because the
        // master changelog that normally carries it also pulls in Spring-injected
        // customChange tasks (parts 0007-0009) that cannot run standalone.
        final SpringLiquibase sequences = new SpringLiquibase();
        sequences.setDataSource(dataSource);
        sequences.setChangeLog(
                "classpath:/db/changelog/tenant-store/parts/0003_reset_postgresql_sequences.xml");
        sequences.setContexts("postgresql,tenant_store_db");
        sequences.setShouldRun(true);
        sequences.afterPropertiesSet();

        // Stands in for core changeset 0007, which adds this column. That changelog part
        // cannot run here: it also carries Spring-injected customChange tasks that encrypt
        // existing passwords, which need a Fineract application context. The column itself
        // is all this feature needs, and the write service sets it.
        new JdbcTemplate(dataSource)
                .execute(
                        "alter table tenant_server_connections add column if not exists"
                                + " master_password_hash varchar(255)");
    }

    /** Applies the changelog this feature adds - the thing actually under test. */
    private static void applyTenantManagementChangelog(final DataSource dataSource)
            throws Exception {
        final SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(
                "classpath:/db/changelog/tenantstore/module/tenantmanagement/module-changelog-master.xml");
        liquibase.setShouldRun(true);
        liquibase.afterPropertiesSet();
    }

    @AfterAll
    static void stopDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        // Start each test from a known registry. The baseline inserts a 'default' tenant.
        jdbcTemplate.update("delete from tenants where identifier <> 'default'");
        jdbcTemplate.update(
                "delete from tenant_server_connections where id not in (select oltp_id from"
                        + " tenants)");

        readService = new TenantManagementReadService(dataSource);
    }

    /**
     * Inserts a tenant and its connection the way the registry holds them.
     *
     * <p>Written with SQL rather than through a service: the write path is not part of this slice,
     * and seeding this way also proves the read projection works against rows it did not produce.
     */
    private long seedTenant(
            final String identifier,
            final String name,
            final String status,
            final String schemaName) {
        final Long connectionId =
                jdbcTemplate.queryForObject(
                        "insert into tenant_server_connections (schema_server, schema_name,"
                            + " schema_server_port, schema_username, schema_password,"
                            + " schema_connection_parameters, auto_update, master_password_hash)"
                            + " values (?, ?, ?, ?, ?, ?, ?, ?) returning id",
                        Long.class,
                        "db.example.org",
                        schemaName,
                        "5432",
                        "fineract",
                        "enc:s3cret",
                        null,
                        1,
                        "test-master-hash");

        return jdbcTemplate.queryForObject(
                "insert into tenants (identifier, name, timezone_id, status, description,"
                    + " contact_email, joined_date, created_date, oltp_id, report_id) values (?, ?,"
                    + " ?, ?, ?, ?, ?, ?, ?, ?) returning id",
                Long.class,
                identifier,
                name,
                "Asia/Kolkata",
                status,
                "a description",
                "ops@example.org",
                LocalDate.now(ZoneOffset.UTC),
                LocalDateTime.now(ZoneOffset.UTC),
                connectionId,
                connectionId);
    }

    private long seedTenant(final String identifier) {
        return seedTenant(identifier, "Acme Microfinance", "ACTIVE", "mifostenant_" + identifier);
    }

    // ---------------------------------------------------------------
    // The changesets themselves
    // ---------------------------------------------------------------

    @Test
    void theStatusColumnIsAddedAndExistingTenantsDefaultToActive() {
        // The migration must not change what an existing installation means: every
        // tenant already in the registry is by definition live.
        final String status =
                jdbcTemplate.queryForObject(
                        "select status from tenants where identifier = 'default'", String.class);

        assertEquals("ACTIVE", status);
    }

    @Test
    void theMetadataColumnsAreAddedToTheRegistry() {
        final long id = seedTenant("acme");

        final TenantData tenant = readService.retrieveOne(id);

        assertEquals("a description", tenant.description());
        assertEquals("ops@example.org", tenant.contactEmail());
        assertNull(tenant.lastModifiedDate());
    }

    @Test
    void theMasterUserTableIsCreated() {
        assertEquals(
                0,
                jdbcTemplate.queryForObject(
                        "select count(*) from tenant_master_user", Integer.class));
    }

    // ---------------------------------------------------------------
    // Reading the registry
    // ---------------------------------------------------------------

    @Test
    void aTenantIsReadBackWithItsConnectionButNeverItsPassword() {
        final long id = seedTenant("acme");

        final TenantData tenant = readService.retrieveOne(id);
        final Page<TenantData> listed = readService.retrieveAll(null, null, null, null);

        assertEquals("acme", tenant.identifier());
        assertEquals(TenantStatus.ACTIVE, tenant.status());
        assertEquals("mifostenant_acme", tenant.connection().schemaName());
        // The projection has no password column at all, so there is nothing to leak.
        assertFalse(tenant.toString().contains("s3cret"));
        assertFalse(listed.getPageItems().toString().contains("enc:"));
    }

    @Test
    void listingFiltersByStatus() {
        seedTenant("acme", "Acme Microfinance", "SUSPENDED", "mifostenant_acme");
        seedTenant("beta");

        final Page<TenantData> suspended =
                readService.retrieveAll(null, TenantStatus.SUSPENDED, null, null);

        assertEquals(1, suspended.getTotalFilteredRecords());
        assertEquals("acme", suspended.getPageItems().get(0).identifier());
    }

    @Test
    void searchMatchesIdentifierAndNameCaseInsensitively() {
        seedTenant("acme");

        assertEquals(
                1, readService.retrieveAll("ACME", null, null, null).getTotalFilteredRecords());
        assertEquals(
                1,
                readService
                        .retrieveAll("microfinance", null, null, null)
                        .getTotalFilteredRecords());
        assertEquals(
                0,
                readService
                        .retrieveAll("nothing-matches", null, null, null)
                        .getTotalFilteredRecords());
    }

    @Test
    void aSearchTermContainingWildcardsIsMatchedLiterally() {
        // Bound as a parameter, so % does not become "match everything".
        seedTenant("acme");

        assertEquals(0, readService.retrieveAll("%", null, null, null).getTotalFilteredRecords());
    }

    @Test
    void pagingReturnsAPageAndTheUnpagedTotal() {
        seedTenant("acme");
        seedTenant("beta");
        seedTenant("gamma");

        final Page<TenantData> firstPage = readService.retrieveAll(null, null, 0, 2);

        assertEquals(2, firstPage.getPageItems().size());
        // 3 seeded plus the baseline 'default' tenant.
        assertEquals(4, firstPage.getTotalFilteredRecords());
    }

    @Test
    void retrievingAnUnknownTenantIsReportedAsNotFound() {
        assertThrows(TenantNotFoundException.class, () -> readService.retrieveOne(999_999L));
    }

    @Test
    void theTemplateOffersTimezonesAndStatuses() {
        final var template = readService.retrieveTemplate();

        assertFalse(template.timezones().isEmpty());
        assertEquals(List.of("ACTIVE", "INACTIVE", "SUSPENDED"), template.statuses());
    }

    @Test
    void anUnrecognisedStoredStatusIsReportedAsNullNotActive() {
        // Showing such a tenant as ACTIVE would tell administrators the opposite of what
        // the platform does with it. One bad row must not fail the listing either.
        final long id = seedTenant("acme", "Acme Microfinance", "DELETED", "mifostenant_acme");

        assertNull(readService.retrieveOne(id).status());
        assertEquals(2, readService.retrieveAll(null, null, null, null).getTotalFilteredRecords());
    }

    // ---------------------------------------------------------------
    // Master users - the super master context
    // ---------------------------------------------------------------

    @Test
    void aBootstrappedMasterUserIsStoredHashedAndCreatedOnlyOnce() {
        jdbcTemplate.update("delete from tenant_master_user");
        final TenantMasterUserStore store = new TenantMasterUserStore(dataSource);

        new TenantMasterUserBootstrap(store, "master", "a-long-enough-password")
                .afterPropertiesSet();
        // A changed configured password must not silently reset an existing master user.
        new TenantMasterUserBootstrap(store, "master", "a-different-long-password")
                .afterPropertiesSet();

        assertEquals(1, store.count());
        final TenantMasterUserStore.MasterUser user = store.findByUsername("master").orElseThrow();
        assertEquals("SUPER_MASTER", user.role());
        assertTrue(user.enabled());
        assertFalse(user.passwordHash().contains("a-long-enough-password"));
        assertTrue(
                PasswordEncoderFactories.createDelegatingPasswordEncoder()
                        .matches("a-long-enough-password", user.passwordHash()));
    }

    @Test
    void aBootstrapPasswordThatIsTooShortCreatesNoMasterUser() {
        jdbcTemplate.update("delete from tenant_master_user");
        final TenantMasterUserStore store = new TenantMasterUserStore(dataSource);

        new TenantMasterUserBootstrap(store, "master", "short").afterPropertiesSet();

        assertEquals(0, store.count());
    }

    @Test
    void noBootstrapConfigurationCreatesNoMasterUser() {
        jdbcTemplate.update("delete from tenant_master_user");
        final TenantMasterUserStore store = new TenantMasterUserStore(dataSource);

        new TenantMasterUserBootstrap(store, "", "").afterPropertiesSet();

        assertEquals(0, store.count());
        assertTrue(store.findByUsername("master").isEmpty());
    }

    @Test
    void losingTheFirstDeploymentRaceForTheMasterUserDoesNotFailStartup() {
        jdbcTemplate.update("delete from tenant_master_user");
        final TenantMasterUserStore store = new TenantMasterUserStore(dataSource);
        // Another node inserts the user after this node has found it absent.
        final TenantMasterUserStore racing =
                new TenantMasterUserStore(dataSource) {
                    private boolean firstLookup = true;

                    @Override
                    public Optional<TenantMasterUserStore.MasterUser> findByUsername(
                            final String username) {
                        if (firstLookup) {
                            firstLookup = false;
                            store.create(
                                    username,
                                    "{noop}other-node",
                                    TenantMasterAccess.SUPER_MASTER_ROLE);
                            return Optional.empty();
                        }
                        return super.findByUsername(username);
                    }
                };

        assertDoesNotThrow(
                () ->
                        new TenantMasterUserBootstrap(racing, "master", "a-long-enough-password")
                                .afterPropertiesSet());
        assertEquals(1, store.count());
    }
}
