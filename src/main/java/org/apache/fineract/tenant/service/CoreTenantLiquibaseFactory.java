/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.service;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;

/**
 * Builds a Liquibase configured the way core configures its own tenant migrations.
 *
 * <p>Core's {@code ExtendedSpringLiquibaseFactory} is the implementation; {@code
 * TenantManagementConfig} adapts it to this interface. {@link TenantSchemaMigrationService} depends
 * on the interface rather than the core class so the plugin holds only the one method it actually
 * calls.
 *
 * <p>That indirection is not decoration. Core's factory declares a constructor parameter from the
 * Spring Boot line it was compiled against ({@code LiquibaseProperties}, which Spring Boot 4 moved
 * to another module), so on a build compiling against a differently-built Fineract the class cannot
 * be subclassed or instrumented at all - it cannot even be stood in for in a test. The plugin has
 * already been caught once by core changing a migration helper between versions; see {@code
 * TenantProvisioningService#jdbcProtocol}.
 */
@FunctionalInterface
public interface CoreTenantLiquibaseFactory {

    /**
     * @param dataSource the tenant's datasource
     * @param contexts the Liquibase contexts to run under, ending with the tenant identifier
     * @return a Liquibase ready to be run with {@code afterPropertiesSet()}
     */
    SpringLiquibase create(DataSource dataSource, String... contexts);
}
