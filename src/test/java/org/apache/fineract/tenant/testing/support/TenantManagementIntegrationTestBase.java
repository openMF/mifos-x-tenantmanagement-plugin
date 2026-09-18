/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.testing.support;

import java.io.File;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Boots a real Fineract, with this plugin on its classpath, against a PostgreSQL container.
 *
 * <p>The containers are started once per JVM and shared by every subclass. The plugin jar is the
 * one Maven packaged before the integration-test phase; its path arrives as the {@code plugin.jar}
 * system property set by Failsafe.
 */
public abstract class TenantManagementIntegrationTestBase {

    private static final Logger LOG =
            LoggerFactory.getLogger(TenantManagementIntegrationTestBase.class);

    private static final Network network = Network.newNetwork();

    protected static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:15-alpine")
                    .withNetwork(network)
                    .withNetworkAliases("db")
                    .withDatabaseName("fineract_default")
                    .withUsername("postgres")
                    .withPassword("postgres");

    protected static final GenericContainer<?> fineract;

    static {
        postgres.start();

        try {
            postgres.execInContainer(
                    "psql", "-U", "postgres", "-c", "CREATE DATABASE fineract_tenants;");
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to initialize fineract_tenants database in Testcontainers", e);
        }

        final String fromEnv = System.getenv("FINERACT_IT_IMAGE");
        final String fromProperty = System.getProperty("fineract.it.image");
        final String defaultImage = "apache/fineract:develop";
        final String fineractImage =
                fromProperty != null && !fromProperty.isBlank()
                        ? fromProperty
                        : (fromEnv != null && !fromEnv.isBlank() ? fromEnv : defaultImage);
        final DockerImageName dockerImageName =
                DockerImageName.parse(fineractImage).asCompatibleSubstituteFor("apache/fineract");

        final String pluginJar = System.getProperty("plugin.jar");
        if (pluginJar == null || !new File(pluginJar).isFile()) {
            throw new IllegalStateException(
                    "Plugin jar not found at '"
                            + pluginJar
                            + "'. Run the integration tests through Maven (./mvnw verify), which"
                            + " packages the jar first and passes its path as -Dplugin.jar.");
        }

        fineract =
                new GenericContainer<>(dockerImageName)
                        .withNetwork(network)
                        .withExposedPorts(8443)
                        .withEnv(
                                "FINERACT_HIKARI_JDBC_URL",
                                "jdbc:postgresql://db:5432/fineract_tenants")
                        .withEnv("FINERACT_HIKARI_USERNAME", "postgres")
                        .withEnv("FINERACT_HIKARI_PASSWORD", "postgres")
                        .withEnv(
                                "FINERACT_HIKARI_DRIVER_SOURCE_CLASS_NAME", "org.postgresql.Driver")
                        .withEnv("FINERACT_DEFAULT_TENANTDB_HOSTNAME", "db")
                        .withEnv("FINERACT_DEFAULT_TENANTDB_PORT", "5432")
                        .withEnv("FINERACT_DEFAULT_TENANTDB_UID", "postgres")
                        .withEnv("FINERACT_DEFAULT_TENANTDB_PWD", "postgres")
                        .withEnv("FINERACT_DEFAULT_TENANTDB_CONN_PARAMS", "")
                        // First master user; see TenantMasterUserBootstrap.
                        .withEnv("FINERACT_TENANT_MANAGEMENT_BOOTSTRAP_MASTER_USERNAME", "master")
                        .withEnv(
                                "FINERACT_TENANT_MANAGEMENT_BOOTSTRAP_MASTER_PASSWORD",
                                "master-password-for-tests")
                        .withEnv("TZ", "UTC")
                        .withEnv("JAVA_TOOL_OPTIONS", "-Xmx2G")
                        .withEnv("FINERACT_SERVER_SSL_ENABLED", "true")
                        .withEnv("FINERACT_SERVER_PORT", "8443")
                        .withCopyFileToContainer(
                                MountableFile.forHostPath(pluginJar),
                                "/app/plugins/tenantmanagement-plugin.jar")
                        .withCreateContainerCmdModifier(
                                cmd -> {
                                    cmd.withEntrypoint(
                                            "sh",
                                            "-c",
                                            "CLASSPATH=$(cat /app/jib-classpath-file) && exec java"
                                                + " $JAVA_TOOL_OPTIONS -Duser.home=/tmp"
                                                + " -Dfile.encoding=UTF-8 -Duser.timezone=UTC"
                                                + " -Djava.security.egd=file:/dev/./urandom -cp"
                                                + " /app/plugins/tenantmanagement-plugin.jar:$CLASSPATH"
                                                + " org.apache.fineract.ServerApplication");
                                    cmd.withCmd();
                                })
                        .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("fineract"))
                        .waitingFor(
                                Wait.forHttps("/fineract-provider/actuator/health")
                                        .allowInsecure()
                                        .forStatusCode(200)
                                        .withStartupTimeout(Duration.ofMinutes(15)));

        try {
            fineract.start();
        } catch (Exception e) {
            String containerLogs;
            try {
                containerLogs = fineract.getLogs();
            } catch (Exception logEx) {
                containerLogs = "Failed to retrieve container logs: " + logEx.getMessage();
            }
            LOG.error(
                    "Fineract container failed to start. Image: {}. Container logs:\n{}",
                    fineract.getDockerImageName(),
                    containerLogs);
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * The host the platform is reachable on.
     *
     * <p>Asked of the container rather than assumed to be {@code localhost}: when the build itself
     * runs in a container against the host's Docker daemon, the platform's published port belongs
     * to the host, not to this JVM's loopback.
     */
    protected static String getFineractHost() {
        return fineract.getHost();
    }

    protected static int getFineractPort() {
        return fineract.getMappedPort(8443);
    }
}
