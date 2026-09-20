/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.config;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.migration.ExtendedSpringLiquibaseFactory;
import org.apache.fineract.tenant.service.CoreTenantLiquibaseFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * Wiring for tenant administration against the central tenant store.
 *
 * <p>Everything here is bound to the {@code hikariTenantDataSource} bean - the registry database -
 * rather than the per-tenant {@code routingDataSource} the rest of the platform uses.
 */
@Configuration
@Slf4j
public class TenantManagementConfig {

    /**
     * Adapts core's Liquibase factory to the one method this plugin uses.
     *
     * <p>Declared here, in the one class that already wires the plugin into the running platform,
     * so {@code TenantSchemaMigrationService} never names the core type. See {@link
     * CoreTenantLiquibaseFactory} for why that separation matters.
     */
    @Bean
    public CoreTenantLiquibaseFactory coreTenantLiquibaseFactory(
            final ExtendedSpringLiquibaseFactory liquibaseFactory) {
        return liquibaseFactory::create;
    }

    /**
     * Applies this plugin's migrations to the tenant store database.
     *
     * <p>Fineract core's tenant-store changelog is a flat, hard-coded list of parts with no module
     * extension point - unlike the per-tenant master, which has one. A plugin therefore cannot
     * append to it, so the plugin runs its own changelog here instead. This keeps the whole feature
     * inside the plugin: no fork of Apache Fineract is required to add the {@code status} column
     * MX-406 needs.
     *
     * <p>Runs after core's own upgrade so the {@code tenants} table it alters is guaranteed to
     * exist; on a fresh installation core creates the registry and this then extends it. The
     * changesets are additive and individually guarded by preconditions, so a repeat run is a
     * no-op.
     */
    @Bean
    @DependsOn("tenantDatabaseUpgradeService")
    public String runTenantManagementTenantStoreMigrations(
            @Qualifier("hikariTenantDataSource") final DataSource tenantStoreDataSource) {

        log.info("Applying tenant administration migrations to the tenant store");

        // Built and run locally rather than returned as a SpringLiquibase bean, as other
        // Fineract plugins do. Publishing a SpringLiquibase bean would enter the
        // pool that Spring Boot's Liquibase autoconfiguration and the platform's own
        // migration wiring select from, and this changelog must apply to the tenant store
        // and nothing else.
        final SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(tenantStoreDataSource);
        liquibase.setChangeLog(
                "classpath:/db/changelog/tenantstore/module/tenantmanagement/module-changelog-master.xml");
        liquibase.setShouldRun(true);

        try {
            liquibase.afterPropertiesSet();
        } catch (final Exception e) {
            // Fail fast and loudly: if the status column is missing, every tenant
            // administration query would fail later with an obscure SQL error instead.
            throw new IllegalStateException("Tenant administration migrations failed", e);
        }

        log.info("Tenant administration migrations completed");
        return "Tenant administration migrations completed";
    }
}
