# mifos-x-tenantmanagement-plugin

An Apache Fineract plugin that lets master users list, create, update, activate, deactivate, suspend and remove the tenants of a Mifos X installation through a REST API, without forking Fineract.

[![License](https://img.shields.io/badge/License-MPL--2.0-blue.svg)](LICENSE)

## Contents

- [Requirements](#requirements)
- [Build](#build)
- [Deploy with Fineract](#deploy-with-fineract)
- [Testing](#testing)
- [Branches](#branches)
- [Contributing](#contributing)

## Requirements

- Java 25
- Spring Boot 4.1
- Apache Fineract `1.16.0-SNAPSHOT` (`develop` branch); the plugin compiles against Fineract
  builds published to the Mifos Artifactory
- PostgreSQL or MariaDB/MySQL tenant store
- Docker, to run the integration tests

## Build

```bash
./mvnw clean package
```

The plugin jar is written to `target/tenantmanagement-plugin-1.16.0-SNAPSHOT.jar`. Fineract is a
`provided` dependency, so the jar holds only this plugin's own classes and resources.

## Deploy with Fineract

Put the jar on Fineract's classpath. For the `apache/fineract` Docker image:

```bash
docker run ... \
  -v "$PWD/target/tenantmanagement-plugin-1.16.0-SNAPSHOT.jar:/app/plugins/tenantmanagement-plugin.jar" \
  --entrypoint sh apache/fineract:develop -c \
  'CLASSPATH=$(cat /app/jib-classpath-file) && exec java $JAVA_TOOL_OPTIONS -cp /app/plugins/tenantmanagement-plugin.jar:$CLASSPATH org.apache.fineract.ServerApplication'
```

For Tomcat, copy the jar into Fineract's library directory and restart:

```bash
cp target/tenantmanagement-plugin-*.jar $TOMCAT_HOME/webapps/fineract-provider/WEB-INF/lib/
```

The plugin runs alongside other Mifos X plugins such as the self-service and savings plugins.

## Testing

```bash
# Unit tests
./mvnw test

# Unit + integration tests (needs Docker)
./mvnw verify
```

Unit tests are named `*Test.java`; tests that need Docker are named `*IntegrationTest.java` and run
in the `verify` phase.

Formatting is enforced by Spotless (google-java-format, AOSP style). Fix violations with
`./mvnw spotless:apply`.

## Branches

- `dev` is the active development branch — all PRs should target `dev`.
- `main` holds released versions.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) and our [Code of Conduct](CODE_OF_CONDUCT.md).
