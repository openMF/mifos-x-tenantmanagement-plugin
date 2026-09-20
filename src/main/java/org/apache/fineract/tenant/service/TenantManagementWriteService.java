/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.database.DatabasePasswordEncryptor;
import org.apache.fineract.tenant.data.TenantCreateRequest;
import org.apache.fineract.tenant.data.TenantData;
import org.apache.fineract.tenant.data.TenantManagementDataValidator;
import org.apache.fineract.tenant.domain.TenantAdministrationAction;
import org.apache.fineract.tenant.exception.TenantIdentifierAlreadyExistsException;
import org.apache.fineract.tenant.exception.TenantSchemaUnavailableException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates tenants in the central registry.
 *
 * <p>Writes go through a {@link TransactionTemplate} bound to the tenant store rather than
 * {@code @Transactional}: the platform's primary transaction manager governs the per-tenant
 * datasource, so an annotation here would open a transaction against the wrong database and leave
 * these statements committing one by one.
 *
 * <p>That template is built here from the registry's own datasource rather than injected by bean
 * name. Core has published a tenant-store transaction template under different names across
 * versions and current Fineract publishes none, so asking for one by name fails at startup - which
 * is exactly what happened here: the whole plugin refused to wire. A manager over the one
 * datasource this class writes to needs no agreement with core at all.
 *
 * <p>Credentials are encrypted with core's {@link DatabasePasswordEncryptor} before they are
 * stored, so a tenant created through this API is protected exactly like one created by hand, and
 * is readable by the same platform that reads every other tenant.
 */
@Service
@Slf4j
public class TenantManagementWriteService {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource tenantStoreDataSource;

    /** Name of the tenant store's own database, read once on first use. */
    private volatile String tenantStoreCatalog;

    private final TransactionTemplate transactionTemplate;
    private final DatabasePasswordEncryptor databasePasswordEncryptor;
    private final TenantProvisioningService provisioningService;
    private final TenantManagementReadService readService;
    private final TenantSchemaMigrationService schemaMigrationService;
    private final TenantAdministrationAuditService auditService;
    private final ObjectProvider<CacheManager> cacheManagerProvider;

    /**
     * Whether a newly created tenant's schema is migrated immediately.
     *
     * <p>On by default, because a tenant whose tables only appear at the next restart is not really
     * created. Operators who would rather migrate on their own schedule - a large installation
     * where a migration is a planned event - can turn it off, and core's startup migration will
     * pick the tenant up as it always has.
     */
    private final boolean migrateOnCreate;

    public TenantManagementWriteService(
            @Qualifier("hikariTenantDataSource") final DataSource tenantStoreDataSource,
            final DatabasePasswordEncryptor databasePasswordEncryptor,
            final TenantProvisioningService provisioningService,
            final TenantManagementReadService readService,
            final TenantSchemaMigrationService schemaMigrationService,
            final TenantAdministrationAuditService auditService,
            final ObjectProvider<CacheManager> cacheManagerProvider,
            @Value("${fineract.tenant-management.migrate-on-create:true}")
                    final boolean migrateOnCreate) {
        this.jdbcTemplate = new JdbcTemplate(tenantStoreDataSource);
        this.tenantStoreDataSource = tenantStoreDataSource;
        this.transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(tenantStoreDataSource));
        this.databasePasswordEncryptor = databasePasswordEncryptor;
        this.provisioningService = provisioningService;
        this.readService = readService;
        this.schemaMigrationService = schemaMigrationService;
        this.auditService = auditService;
        this.cacheManagerProvider = cacheManagerProvider;
        this.migrateOnCreate = migrateOnCreate;
    }

    /**
     * Drops every cached view of a tenant after it has been registered.
     *
     * <p>Core caches {@code JdbcTenantDetailsService.loadTenantById} under {@code tenantsById}, and
     * that cache holds the connection details the platform routes on. An identifier that was in use
     * before - a tenant removed from the registry and created again - can therefore still be
     * cached, and the new tenant would be routed to the old tenant's database until the entry
     * expired or the platform restarted.
     *
     * <p>Core's cache is reached through the {@link CacheManager} beans rather than with
     * {@code @CacheEvict}: the annotation would need this class to know the cache's key layout and
     * which of the platform's several managers holds it.
     */
    private void evictCachedViewsOf(final String identifier) {
        // Best effort, and never allowed to escape. This runs after the registry write has
        // committed, so a throw here turns a completed change into a 500 - and on create it
        // also skips the migration, stranding a registered tenant with an empty schema. That
        // is exactly what happened against a real Fineract before this guard existed. A stale
        // cache entry expires; a stranded tenant does not.
        try {
            // Every manager, not "the" manager. A running Fineract registers several
            // (runtimeDelegatingCacheManager, defaultCacheManager, ehCacheManager, cacheManager),
            // and asking Spring for a single one throws NoUniqueBeanDefinitionException.
            // Evicting in each is a no-op where the cache is absent and correct wherever it lives.
            cacheManagerProvider
                    .orderedStream()
                    .forEach(cacheManager -> evictFrom(cacheManager, identifier));
        } catch (final RuntimeException e) {
            log.warn("Could not enumerate cache managers to evict tenant {}", identifier, e);
        }
    }

    private static void evictFrom(final CacheManager cacheManager, final String identifier) {
        try {
            final Cache tenantsById = cacheManager.getCache("tenantsById");
            if (tenantsById != null) {
                // Keyed by the single method argument, the tenant identifier.
                tenantsById.evict(identifier);
            }
        } catch (final RuntimeException e) {
            log.warn(
                    "Could not evict tenant {} from cache manager {}", identifier, cacheManager, e);
        }
    }

    /**
     * Registers a new tenant and provisions its schema.
     *
     * <p>Ordering is deliberate. The schema is created and proved reachable <em>before</em>
     * anything is written to the registry, so a tenant that could never have worked leaves no row
     * behind. The reverse order would publish a tenant into the registry that the platform then
     * fails to route to on its next startup.
     *
     * @return the tenant as stored, without credentials
     * @throws TenantIdentifierAlreadyExistsException when the identifier is taken
     */
    public TenantData create(final TenantCreateRequest request) {
        final String identifier =
                TenantManagementDataValidator.normaliseIdentifier(request.identifier());

        if (readService.existsByIdentifier(identifier)) {
            throw new TenantIdentifierAlreadyExistsException(identifier);
        }

        // Before anything is created: the schema step below reuses an existing database of this
        // name, so ownership has to be settled first.
        assertSchemaAvailable(
                request.schemaServer(), request.schemaServerPort(), request.schemaName());

        provisioningService.createSchemaIfAbsent(
                request.schemaServer(),
                request.schemaServerPort(),
                request.schemaName(),
                request.schemaConnectionParameters(),
                request.schemaUsername(),
                request.schemaPassword());

        provisioningService.verifyReachable(
                request.schemaServer(),
                request.schemaServerPort(),
                request.schemaName(),
                request.schemaConnectionParameters(),
                request.schemaUsername(),
                request.schemaPassword());

        final Long tenantId =
                transactionTemplate.execute(
                        status -> {
                            final Long connectionId = insertConnection(request);
                            return insertTenant(request, identifier, connectionId);
                        });

        evictCachedViewsOf(identifier);

        if (migrateOnCreate) {
            migrateOrUndoRegistration(identifier, tenantId);
        }

        auditService.recordSuccess(
                TenantAdministrationAction.CREATE,
                identifier,
                tenantId,
                "schemaName=" + request.schemaName() + ", status=" + request.status().name());

        log.info("Registered tenant {} with schema {}", identifier, request.schemaName());
        return readService.retrieveOne(tenantId);
    }

    /**
     * Inserts the connection row.
     *
     * <p>{@code master_password_hash} is stamped with the running platform's hash. Core's {@code
     * TenantDataSourceFactory} refuses to build a datasource for a tenant whose hash does not match
     * its own, so omitting this would produce a tenant the platform cannot open - failing only
     * later, at startup, with a bare "Invalid master password".
     */
    private Long insertConnection(final TenantCreateRequest request) {
        final String sql =
                "insert into tenant_server_connections (schema_server, schema_name,"
                    + " schema_server_port, schema_username, schema_password,"
                    + " schema_connection_parameters, auto_update, master_password_hash) values (?,"
                    + " ?, ?, ?, ?, ?, ?, ?)";

        final KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                connection -> {
                    // The generated column is named rather than asking for all generated keys.
                    // PostgreSQL answers the blanket form with the whole inserted row, so
                    // KeyHolder sees many "keys" and getKey() throws; MySQL answers with just
                    // the id, so the bug would only ever have appeared on PostgreSQL. Naming
                    // the column returns one value on both engines.
                    final PreparedStatement ps =
                            connection.prepareStatement(sql, new String[] {"id"});
                    ps.setString(1, request.schemaServer());
                    ps.setString(2, request.schemaName());
                    ps.setString(3, request.schemaServerPort());
                    ps.setString(4, request.schemaUsername());
                    ps.setString(5, databasePasswordEncryptor.encrypt(request.schemaPassword()));
                    ps.setString(6, request.schemaConnectionParameters());
                    // Bound as an int, not a boolean. `auto_update` is declared TINYINT, which
                    // PostgreSQL maps to smallint and which rejects a boolean parameter outright
                    // ("column is of type smallint but expression is of type boolean"). MySQL and
                    // MariaDB accept either, so an int is the form that works on both engines.
                    ps.setInt(7, request.autoUpdate() ? 1 : 0);
                    ps.setString(8, databasePasswordEncryptor.getMasterPasswordHash());
                    return ps;
                },
                keyHolder);

        return requireKey(keyHolder, "tenant_server_connections");
    }

    /**
     * Inserts the tenant row.
     *
     * <p>{@code oltp_id} and {@code report_id} both point at the one connection just created. The
     * registry allows a separate reporting connection, but MX-406's UI collects a single set of
     * connection details, and a tenant whose reporting connection is its operational one is the
     * shape Fineract's own default tenant ships in.
     */
    private Long insertTenant(
            final TenantCreateRequest request, final String identifier, final Long connectionId) {
        final String sql =
                "insert into tenants (identifier, name, timezone_id, status, description,"
                    + " contact_email, joined_date, created_date, oltp_id, report_id) values (?, ?,"
                    + " ?, ?, ?, ?, ?, ?, ?, ?)";

        final KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(
                    connection -> {
                        // Named generated column, for the same portability reason as above.
                        final PreparedStatement ps =
                                connection.prepareStatement(sql, new String[] {"id"});
                        ps.setString(1, identifier);
                        ps.setString(2, request.name());
                        ps.setString(3, request.timezoneId());
                        ps.setString(4, request.status().name());
                        ps.setString(5, request.description());
                        ps.setString(6, request.contactEmail());
                        // The registry has carried joined_date since before this API existed, and
                        // TenantData returns it. Setting it here stops a tenant created through the
                        // API from being the only one with the field blank.
                        ps.setObject(7, LocalDate.now(ZoneOffset.UTC));
                        // Registry timestamps are written as zone-less UTC wall-clock values.
                        // setTimestamp converts through the JVM's default time zone, so nodes
                        // configured differently would store different values for the same instant.
                        ps.setObject(8, LocalDateTime.now(ZoneOffset.UTC));
                        ps.setLong(9, connectionId);
                        ps.setLong(10, connectionId);
                        return ps;
                    },
                    keyHolder);
        } catch (final DuplicateKeyException e) {
            // The pre-check above is not a guarantee: two administrators can create the same
            // identifier concurrently, and only the unique constraint settles it. Reported as
            // the same clear conflict rather than a raw constraint violation.
            throw new TenantIdentifierAlreadyExistsException(identifier);
        }

        return requireKey(keyHolder, "tenants");
    }

    /**
     * Migrates the new tenant, and unregisters it again if that fails.
     *
     * <p>The registry rows are already committed by this point - the migration needs them, because
     * it re-reads the tenant through core's own services to get the connection and its decrypted
     * credentials. So the failure path compensates rather than rolls back: the tenant is removed
     * from the registry, leaving the installation as it was before the request.
     *
     * <p>The schema itself is left alone. It may hold a partially applied migration, and an
     * administrator who retries will have it completed rather than restarted - Liquibase resumes
     * from its own changelog table. Dropping it here would mean this API destroying a database as
     * part of handling an error, which is precisely the capability it should not have.
     */
    private void migrateOrUndoRegistration(final String identifier, final Long tenantId) {
        try {
            schemaMigrationService.migrate(identifier);
        } catch (final RuntimeException e) {
            log.error(
                    "Migration failed for new tenant {}; removing its registry entry. "
                            + "The schema was left in place and was not dropped.",
                    identifier,
                    e);
            auditService.recordFailure(
                    TenantAdministrationAction.CREATE,
                    identifier,
                    tenantId,
                    "schema migration failed");
            try {
                final TenantData registered = readService.retrieveOne(tenantId);
                removeRegistryRows(
                        tenantId,
                        registered.connection() == null ? null : registered.connection().id());
                evictCachedViewsOf(identifier);
            } catch (final RuntimeException cleanupFailure) {
                // Reported but not rethrown: the migration failure is the real error and
                // must reach the caller. A stranded row is recoverable by hand; swapping
                // the exception would hide why the request failed at all.
                log.error(
                        "Could not remove the registry entry for tenant {}",
                        identifier,
                        cleanupFailure);
            }
            throw e;
        }
    }

    /**
     * Deletes a tenant's registry rows.
     *
     * <p>The tenant row goes first: {@code oltp_id} and {@code report_id} reference the connection
     * with ON DELETE RESTRICT, so removing the connection first would be refused.
     */
    private void removeRegistryRows(final Long tenantId, final Long connectionId) {
        transactionTemplate.executeWithoutResult(
                status -> {
                    jdbcTemplate.update("delete from tenants where id = ?", tenantId);
                    if (connectionId != null) {
                        jdbcTemplate.update(
                                "delete from tenant_server_connections where id = ?", connectionId);
                    }
                });
    }

    /**
     * Refuses a database this tenant must not be bound to.
     *
     * <p>Creating a tenant reuses an existing database of the requested name, so without these
     * checks a new identifier could be routed to the registry itself or to another tenant's live
     * data. Database names are compared case-insensitively, as PostgreSQL folds unquoted names and
     * MySQL is commonly case-insensitive; servers are compared as written, so {@code localhost} and
     * {@code 127.0.0.1} count as different servers.
     *
     * @throws TenantSchemaUnavailableException when the database belongs elsewhere
     */
    private void assertSchemaAvailable(
            final String schemaServer, final String schemaServerPort, final String schemaName) {

        if (schemaName.equalsIgnoreCase(tenantStoreCatalog())) {
            throw TenantSchemaUnavailableException.tenantStore(schemaName);
        }

        final List<String> owners =
                jdbcTemplate.queryForList(
                        "select t.identifier from tenants t join tenant_server_connections ts on"
                                + " ts.id = t.oltp_id or ts.id = t.report_id where"
                                + " lower(ts.schema_name) = lower(?) and lower(ts.schema_server) ="
                                + " lower(?) and ts.schema_server_port = ?",
                        String.class,
                        schemaName,
                        schemaServer,
                        schemaServerPort);
        if (!owners.isEmpty()) {
            throw TenantSchemaUnavailableException.inUse(schemaName, owners.get(0));
        }
    }

    /**
     * @return the tenant store's own database name, or an empty string if the driver reports none
     */
    private String tenantStoreCatalog() {
        String catalog = this.tenantStoreCatalog;
        if (catalog != null) {
            return catalog;
        }
        try (Connection connection = tenantStoreDataSource.getConnection()) {
            catalog = connection.getCatalog();
        } catch (final SQLException e) {
            throw new IllegalStateException("Could not read the tenant store database name", e);
        }
        this.tenantStoreCatalog = catalog == null ? "" : catalog;
        return this.tenantStoreCatalog;
    }

    private static Long requireKey(final KeyHolder keyHolder, final String table) {
        final Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException(
                    "No generated key returned when inserting into " + table);
        }
        return key.longValue();
    }
}
