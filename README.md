# mifos-x-tenantmanagement-plugin

An Apache Fineract plugin that lets master users list, create, update, activate, deactivate, suspend
and remove the tenants of a Mifos X installation through a REST API, without forking Fineract.

[![License](https://img.shields.io/badge/License-MPL--2.0-blue.svg)](LICENSE)

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Build](#build)
- [Deploy with Fineract](#deploy-with-fineract)
- [First master user](#first-master-user)
- [Quick start](#quick-start)
- [API](#api)
- [Testing](#testing)
- [Branches](#branches)
- [Contributing](#contributing)

## Features

- **Tenant lifecycle API** under `/v1/admin/tenants`: list with search and paging, create, partial
  update, activate / deactivate / suspend, remove, and a database connection test.
- **Schema provisioning**: creating a tenant creates its database and migrates it with the same
  changelogs Fineract and its installed plugins apply at startup, so it is usable immediately.
- **Status enforcement**: a tenant that is not `ACTIVE` is refused with `503` on every endpoint of
  the platform, not only the ones this plugin adds.
- **Master context**: tenant administration authenticates against master users with the
  `SUPER_MASTER` role, stored in the tenant store — never against any tenant's own users.
- **Audit trail** of every change in the tenant store, with no credentials recorded.
- **Safe removal**: `DELETE` removes the registry entry only and never drops tenant data.

Full design, configuration and behaviour: **[TENANT_MANAGEMENT.md](TENANT_MANAGEMENT.md)**, which
also lists the [limitations](TENANT_MANAGEMENT.md#limitations) of this first version.

Tracked as [MX-406](https://mifosforge.jira.com/browse/MX-406). The web-app UI for these endpoints
is WEB-1242 in [openMF/web-app](https://github.com/openMF/web-app).

## Requirements

- Java 25
- Spring Boot 4.1
- Apache Fineract `develop`. The build compiles against the `develop` snapshot published to the
  Mifos Artifactory, pinned by the `fineract.version` property in `pom.xml`
- PostgreSQL or MariaDB/MySQL tenant store
- Docker, to run the integration tests

## Build

```bash
./mvnw clean package
```

The plugin jar is written to `target/tenantmanagement-plugin-1.16.0-SNAPSHOT.jar`. Fineract is a
`provided` dependency, so the jar holds only this plugin's classes and changelogs.

## Deploy with Fineract

Put the jar on Fineract's classpath. For the `apache/fineract` Docker image:

```bash
docker run ... \
  -v "$PWD/target/tenantmanagement-plugin-1.16.0-SNAPSHOT.jar:/app/plugins/tenantmanagement-plugin.jar" \
  -e FINERACT_TENANT_MANAGEMENT_BOOTSTRAP_MASTER_USERNAME=master \
  -e FINERACT_TENANT_MANAGEMENT_BOOTSTRAP_MASTER_PASSWORD='a long, unique password' \
  --entrypoint sh apache/fineract:develop -c \
  'CLASSPATH=$(cat /app/jib-classpath-file) && exec java $JAVA_TOOL_OPTIONS -cp /app/plugins/tenantmanagement-plugin.jar:$CLASSPATH org.apache.fineract.ServerApplication'
```

For Tomcat, copy the jar into Fineract's library directory and restart:

```bash
cp target/tenantmanagement-plugin-*.jar $TOMCAT_HOME/webapps/fineract-provider/WEB-INF/lib/
```

On startup the plugin applies its own changelog to the tenant store (a `status` column on `tenants`,
plus the audit, master-user and retained-schema tables). Existing tenants default to `ACTIVE`, so an
existing installation behaves exactly as before.

The plugin runs alongside other Mifos X plugins such as the self-service and savings plugins.
Tenants it creates also receive those plugins' tables when they are installed.

## First master user

No API can create the first master user without already requiring one, so it comes from
configuration and is created once, at startup:

| Environment variable | Meaning |
|---|---|
| `FINERACT_TENANT_MANAGEMENT_BOOTSTRAP_MASTER_USERNAME` | Master user to create if it does not exist |
| `FINERACT_TENANT_MANAGEMENT_BOOTSTRAP_MASTER_PASSWORD` | Its password, at least 12 characters; never reset afterwards |

Without a master user every `/v1/admin/tenants` request is refused. The remaining settings are
listed in [TENANT_MANAGEMENT.md § Configuration](TENANT_MANAGEMENT.md#configuration).

## Quick start

With the jar deployed and a master user bootstrapped, this is a tenant's whole life. Use your own
credentials; `$TENANT_DB_PASSWORD` is the password of the database user the new tenant connects as.

```bash
BASE=http://localhost:8080/fineract-provider/api/v1/admin/tenants
AUTH="$MASTER_USERNAME:$MASTER_PASSWORD"
JSON='Content-Type: application/json'

# What this installation already has
curl -u "$AUTH" "$BASE"

# Check the details before committing. Before the database exists `reachable` is false while
# `credentialsAccepted` is true - that is the healthy answer, and the field to read here.
curl -u "$AUTH" -H "$JSON" -X POST "$BASE/test-connection" -d '{
  "schemaName": "mifostenant_acme", "schemaServer": "localhost",
  "schemaServerPort": "5432", "schemaUsername": "postgres",
  "schemaPassword": "'"$TENANT_DB_PASSWORD"'" }'

# Register and provision it. The response carries the new tenant's id.
# The database user needs the right to create databases (CREATEDB on PostgreSQL), unless the
# database already exists - see TENANT_MANAGEMENT.md, Database privileges.
curl -u "$AUTH" -H "$JSON" -X POST "$BASE" -d '{
  "identifier": "acme", "name": "Acme Microfinance", "timezoneId": "Asia/Kolkata",
  "schemaName": "mifostenant_acme", "schemaServer": "localhost",
  "schemaServerPort": "5432", "schemaUsername": "postgres",
  "schemaPassword": "'"$TENANT_DB_PASSWORD"'" }'

# Migrated and usable straight away, through the administrator Fineract seeds into a new
# tenant (mifos / password on a stock installation - change it immediately)
curl -u mifos:password -H 'Fineract-Platform-TenantId: acme' \
  http://localhost:8080/fineract-provider/api/v1/offices

# Suspend it: every platform request for acme is now refused with 503
curl -u "$AUTH" -H "$JSON" -X POST "$BASE/1?command=suspend" -d '{}'

# Remove it. An ACTIVE tenant is refused, so deactivate first. The database is left intact.
curl -u "$AUTH" -H "$JSON" -X POST "$BASE/1?command=deactivate" -d '{}'
curl -u "$AUTH" -X DELETE "$BASE/1"
```

`1` above stands for the id the create call returned. Status changes are idempotent, and a removed
tenant's database stays bound to its identifier, so only a tenant created again under the same
identifier can reuse it.

## API

Authenticate as a master user with HTTP Basic, and send **no** `Fineract-Platform-TenantId` header.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/admin/tenants` | List, with `search`, `status`, `offset`, `limit` |
| `GET` | `/v1/admin/tenants/template` | Selectable timezones and statuses |
| `GET` | `/v1/admin/tenants/{id}` | One tenant |
| `POST` | `/v1/admin/tenants` | Register and provision a tenant |
| `PUT` | `/v1/admin/tenants/{id}` | Partial update |
| `POST` | `/v1/admin/tenants/{id}?command=activate\|deactivate\|suspend` | Change status |
| `DELETE` | `/v1/admin/tenants/{id}` | Remove the registry entry |
| `POST` | `/v1/admin/tenants/test-connection` | Probe a database before creating a tenant |

- OpenAPI 3:
  [`api-reference/openapi/tenant-management.yaml`](api-reference/openapi/tenant-management.yaml).
  Plugin endpoints do not appear in Fineract's built-in Swagger UI.
- Bruno collection: [`api-reference/bruno/TENANT MANAGEMENT PLUGIN`](api-reference/bruno). Set
  `master_username`, `master_password` and `tenant_db_password` in your Bruno environment.

## Testing

```bash
# Unit tests
./mvnw test

# Unit + integration tests (needs Docker)
./mvnw verify
```

The integration tests run the tenant store changelogs against a real PostgreSQL, and exercise the
API over HTTPS in an `apache/fineract:develop` container with this plugin loaded. Use another image
with `-Dfineract.it.image=<image>` or the `FINERACT_IT_IMAGE` environment variable.

After changing the API, regenerate the OpenAPI file — the build fails if it drifts from the
annotations:

```bash
./mvnw test -Dtest=TenantManagementOpenApiSpecTest -Dopenapi.update=true
```

Formatting is enforced by Spotless (google-java-format, AOSP style). Fix violations with
`./mvnw spotless:apply`.

## Branches

- `dev` is the active development branch — all PRs should target `dev`.
- `main` holds released versions.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) and our [Code of Conduct](CODE_OF_CONDUCT.md).
