/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.service;

import static org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection.toJdbcUrl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Predicate;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.tenant.data.TenantConnectionProbe;
import org.apache.fineract.tenant.domain.ConnectionFailureKind;
import org.apache.fineract.tenant.domain.TenantSchemaName;
import org.apache.fineract.tenant.exception.TenantConnectionFailedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Reaches the database behind a tenant: checks it is usable, and creates its schema.
 *
 * <p><strong>Scope.</strong> MX-406 asks for "automatic or semi-automatic" provisioning. This
 * service does the semi-automatic half - it creates the empty schema and verifies the credentials
 * work. Populating that schema with Fineract's tables is left to core's existing {@code
 * TenantDatabaseUpgradeService}, which already migrates every registered tenant with {@code
 * auto_update} set on startup. Reusing that path rather than re-running the full Fineract changelog
 * inline keeps one migration mechanism in the installation instead of two that can disagree, and
 * means a tenant created here is provisioned exactly like every tenant created before it.
 */
@Service
@Slf4j
public class TenantProvisioningService {

    /**
     * Re-asserted here even though {@code TenantManagementDataValidator} already enforces it.
     *
     * <p>A schema name cannot be bound as a JDBC parameter, so it is concatenated into DDL below.
     * Checking again at the point of concatenation means this class is safe on its own terms and
     * stays safe if it ever gains a second caller that forgets to validate first.
     */
    private static final Predicate<String> SAFE_SCHEMA_NAME = TenantSchemaName::isValid;

    /** Seconds to wait for a connection before calling the database unreachable. */
    private static final int CONNECTION_TIMEOUT_SECONDS = 10;

    /**
     * The tenant store's datasource, used only to learn which database engine this installation
     * runs on. Core derives the JDBC protocol from a datasource rather than from a driver name, and
     * every tenant on an installation sits on the same engine as the registry.
     */
    private final DataSource tenantStoreDataSource;

    public TenantProvisioningService(
            @Qualifier("hikariTenantDataSource") final DataSource tenantStoreDataSource) {
        this.tenantStoreDataSource = tenantStoreDataSource;
    }

    /** Resolved once, on first use; every tenant sits on the registry's engine. */
    private volatile String jdbcProtocol;

    /**
     * Returns the JDBC protocol of this installation, for example {@code jdbc:postgresql}.
     *
     * <p><strong>Why not core's helper.</strong> Fineract has changed this helper between versions:
     * the 1.15 artefact this plugin compiles against offers {@code toProtocol(DataSource)}, while
     * the 1.16 runtime in {@code apache/fineract:develop} has only {@code resolveProtocol(String)}
     * - so code linked against either fails with {@code NoSuchMethodError} on the other. Reading
     * the protocol off the tenant store's own JDBC URL depends on no core API at all, and yields
     * exactly the form {@code toJdbcUrl} expects: the text before {@code ://}.
     */
    private String jdbcProtocol() {
        String protocol = this.jdbcProtocol;
        if (protocol != null) {
            return protocol;
        }
        try (Connection connection = tenantStoreDataSource.getConnection()) {
            final String url = connection.getMetaData().getURL();
            final int separator = url == null ? -1 : url.indexOf("://");
            if (separator <= 0 || !url.startsWith("jdbc:")) {
                throw new IllegalStateException("Unrecognised tenant store JDBC URL shape");
            }
            protocol = url.substring(0, separator);
            this.jdbcProtocol = protocol;
            return protocol;
        } catch (final SQLException e) {
            throw new IllegalStateException("Could not read the tenant store JDBC URL", e);
        }
    }

    /**
     * Checks that a database can be reached with the supplied details.
     *
     * @param plainPassword the password as typed by the administrator, not the encrypted form
     * @throws TenantConnectionFailedException when the database cannot be reached
     */
    public void verifyReachable(
            final String schemaServer,
            final String schemaServerPort,
            final String schemaName,
            final String connectionParameters,
            final String schemaUsername,
            final String plainPassword) {

        final String protocol = jdbcProtocol();
        final String url =
                toJdbcUrl(
                        protocol, schemaServer, schemaServerPort, schemaName, connectionParameters);

        try (Connection connection = openConnection(url, schemaUsername, plainPassword)) {
            if (!connection.isValid(CONNECTION_TIMEOUT_SECONDS)) {
                throw new SQLException("Connection opened but did not validate");
            }
        } catch (final SQLException e) {
            // The driver's own message routinely echoes the JDBC URL and user back, so it is
            // logged rather than returned; only the classification leaves the server.
            log.warn(
                    "Tenant database at {}:{}/{} could not be reached",
                    schemaServer,
                    schemaServerPort,
                    schemaName,
                    e);
            throw TenantConnectionFailedException.from(
                    schemaServer, schemaServerPort, schemaName, e);
        }
    }

    /**
     * Non-throwing form of {@link #verifyReachable}, for the test-connection endpoint.
     *
     * @return true when the database answered
     */
    public boolean isReachable(
            final String schemaServer,
            final String schemaServerPort,
            final String schemaName,
            final String connectionParameters,
            final String schemaUsername,
            final String plainPassword) {
        return probe(
                        schemaServer,
                        schemaServerPort,
                        schemaName,
                        connectionParameters,
                        schemaUsername,
                        plainPassword)
                .reachable();
    }

    /**
     * Probes a database and reports what was found, without throwing.
     *
     * <p>The target database is tried first, which is the check this plugin has always made. When
     * it answers there is nothing left to ask. When it does not, the server itself is asked - the
     * same maintenance-database connection {@link #createSchemaIfAbsent} already opens to issue
     * {@code CREATE DATABASE} - so the answer can separate a refused password from a database that
     * has simply not been created yet.
     *
     * <p>That second question is what makes the endpoint usable before a tenant exists. Until it
     * was asked, an administrator checking details for a tenant they were about to create always
     * got {@code false}, because the database they were about to create did not exist yet.
     *
     * @param plainPassword the password as typed by the administrator, not the encrypted form
     */
    public TenantConnectionProbe probe(
            final String schemaServer,
            final String schemaServerPort,
            final String schemaName,
            final String connectionParameters,
            final String schemaUsername,
            final String plainPassword) {

        try {
            verifyReachable(
                    schemaServer,
                    schemaServerPort,
                    schemaName,
                    connectionParameters,
                    schemaUsername,
                    plainPassword);
            return TenantConnectionProbe.usable();
        } catch (final TenantConnectionFailedException targetFailure) {
            return askTheServer(
                    schemaServer,
                    schemaServerPort,
                    schemaName,
                    connectionParameters,
                    schemaUsername,
                    plainPassword);
        }
    }

    /**
     * Asks the server what it thinks of these credentials, and whether it holds the database.
     *
     * <p>Reached only when the target database did not answer. A server that accepts the
     * credentials but does not hold the database is the ordinary state before a tenant is created;
     * a server that accepts them and does hold it means the target refused for a reason of its own,
     * most often a privilege on that one database.
     */
    private TenantConnectionProbe askTheServer(
            final String schemaServer,
            final String schemaServerPort,
            final String schemaName,
            final String connectionParameters,
            final String schemaUsername,
            final String plainPassword) {

        final boolean postgres = isPostgres();
        final String maintenanceUrl =
                maintenanceUrl(schemaServer, schemaServerPort, connectionParameters, postgres);

        try (Connection connection =
                openConnection(maintenanceUrl, schemaUsername, plainPassword)) {
            return TenantConnectionProbe.serverOnly(schemaExists(connection, schemaName, postgres));
        } catch (final SQLException serverFailure) {
            log.warn(
                    "Database server at {}:{} could not be probed for {}",
                    schemaServer,
                    schemaServerPort,
                    schemaName,
                    serverFailure);
            return switch (ConnectionFailureKind.from(serverFailure)) {
                case CREDENTIALS_REJECTED -> TenantConnectionProbe.credentialsRejected();
                // The server answered and knows this user; it just will not let them into the
                // maintenance database, so whether the target exists stays unknown.
                case INSUFFICIENT_PRIVILEGE -> TenantConnectionProbe.serverOnly(false);
                default -> TenantConnectionProbe.unreachable();
            };
        }
    }

    /** True when this installation runs on PostgreSQL. */
    private boolean isPostgres() {
        return jdbcProtocol().toLowerCase(Locale.ROOT).contains("postgres");
    }

    /**
     * A URL for some database on the server other than the target.
     *
     * <p>{@code CREATE DATABASE} needs one, and so does a probe of a database that does not exist
     * yet. PostgreSQL always has {@code postgres}; MySQL and MariaDB accept a connection with no
     * database selected at all.
     */
    private String maintenanceUrl(
            final String schemaServer,
            final String schemaServerPort,
            final String connectionParameters,
            final boolean postgres) {
        return toJdbcUrl(
                jdbcProtocol(),
                schemaServer,
                schemaServerPort,
                postgres ? "postgres" : "",
                connectionParameters);
    }

    /**
     * Creates the tenant's schema if it does not already exist.
     *
     * <p>An existing schema is left untouched and reported as success: an administrator who
     * pre-created the schema, or who is retrying a half-finished create, should not be blocked.
     * This never drops or empties anything.
     *
     * @param plainPassword password of a user permitted to create schemas on that server
     * @throws TenantConnectionFailedException when the server cannot be reached or refuses the DDL
     */
    public void createSchemaIfAbsent(
            final String schemaServer,
            final String schemaServerPort,
            final String schemaName,
            final String connectionParameters,
            final String schemaUsername,
            final String plainPassword) {

        if (!SAFE_SCHEMA_NAME.test(schemaName)) {
            // Defensive: an unvalidated name must never reach the concatenation below.
            throw new IllegalArgumentException("Unsafe schema name rejected before DDL");
        }

        final boolean postgres = isPostgres();
        final String adminUrl =
                maintenanceUrl(schemaServer, schemaServerPort, connectionParameters, postgres);

        try (Connection connection = openConnection(adminUrl, schemaUsername, plainPassword)) {
            if (schemaExists(connection, schemaName, postgres)) {
                log.info("Schema {} already exists; leaving it untouched", schemaName);
                return;
            }
            try (Statement statement = connection.createStatement()) {
                // PostgreSQL has no IF NOT EXISTS for CREATE DATABASE, which is why existence is
                // checked separately above rather than delegated to the database.
                //
                // The name is concatenated because no driver can bind an identifier as a parameter.
                // That is safe only because SAFE_SCHEMA_NAME has admitted nothing but letters,
                // digits and underscore, starting with a letter or underscore.
                statement.executeUpdate("CREATE DATABASE " + schemaName);
                log.info("Created schema {} for a new tenant", schemaName);
            } catch (final SQLException ddlFailure) {
                // Two creates for the same schema can both pass the existence check above; the
                // database then accepts one CREATE and rejects the other as a duplicate. If the
                // schema exists now, the outcome this call was asked for has happened, so the
                // loser of that race is not an error. Anything else is rethrown untouched.
                if (schemaExists(connection, schemaName, postgres)) {
                    log.info(
                            "Schema {} was created concurrently; treating it as present",
                            schemaName);
                    return;
                }
                throw ddlFailure;
            }
        } catch (final SQLException e) {
            log.warn(
                    "Could not create schema {} on {}:{}",
                    schemaName,
                    schemaServer,
                    schemaServerPort,
                    e);
            throw TenantConnectionFailedException.from(
                    schemaServer, schemaServerPort, schemaName, e);
        }
    }

    /**
     * @return true when the server already has a database of this name
     */
    private boolean schemaExists(
            final Connection connection, final String schemaName, final boolean postgres)
            throws SQLException {
        final String sql =
                postgres
                        ? "select 1 from pg_database where datname = ?"
                        : "select 1 from information_schema.schemata where schema_name = ?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schemaName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Opens a connection with a bounded login and connect timeout.
     *
     * <p>The timeout is passed to the driver as a property of this one connection. {@link
     * DriverManager#setLoginTimeout} is process-wide static state: setting and restoring it around
     * a call races with every other thread in the JVM that opens a connection through {@code
     * DriverManager}, and can leave them with an unintended timeout.
     *
     * <p>Units differ by driver: PostgreSQL's {@code loginTimeout} and {@code connectTimeout} are
     * seconds, while MariaDB and MySQL Connector/J take {@code connectTimeout} in milliseconds. A
     * timeout an administrator sets in the connection parameters is part of the URL and takes
     * precedence.
     */
    private Connection openConnection(
            final String url, final String username, final String password) throws SQLException {
        final Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        if (url.startsWith("jdbc:postgresql")) {
            properties.setProperty("loginTimeout", String.valueOf(CONNECTION_TIMEOUT_SECONDS));
            properties.setProperty("connectTimeout", String.valueOf(CONNECTION_TIMEOUT_SECONDS));
        } else {
            properties.setProperty(
                    "connectTimeout", String.valueOf(CONNECTION_TIMEOUT_SECONDS * 1000));
        }
        return DriverManager.getConnection(url, properties);
    }
}
