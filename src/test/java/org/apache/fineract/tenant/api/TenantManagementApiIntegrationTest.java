/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import org.apache.fineract.tenant.testing.support.TenantManagementIntegrationTestBase;
import org.apache.fineract.tenant.testing.support.TenantManagementTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@code /v1/admin/tenants} over HTTP against a real Fineract with this plugin loaded.
 *
 * <p>Tenant management runs in the master context: requests authenticate as a master user (created
 * at container start by {@code TenantMasterUserBootstrap} from the test base's environment) and
 * carry no tenant header. Tenant users - including a tenant's own {@code mifos} super user - are
 * refused.
 *
 * <p>Creating a tenant runs a complete schema migration, so the lifecycle is one test rather than a
 * chain of order-dependent ones. The shared {@code default} tenant is never mutated: other
 * integration test classes run against it in the same containers.
 */
class TenantManagementApiIntegrationTest extends TenantManagementIntegrationTestBase {

    private static final String TENANTS_PATH =
            TenantManagementTestUtils.CONTEXT_PATH + "/api/v1/admin/tenants";
    private static final String OFFICES_PATH =
            TenantManagementTestUtils.CONTEXT_PATH + "/api/v1/offices";

    private static final String MASTER_USERNAME = "master";
    private static final String MASTER_PASSWORD = "master-password-for-tests";

    private static RequestSpecification base() {
        return given().relaxedHTTPSValidation()
                .baseUri("https://" + getFineractHost())
                .port(getFineractPort())
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON);
    }

    /** A master user's request: no tenant header, because the master context has none. */
    private static RequestSpecification asMaster() {
        return base().header(
                        "Authorization",
                        TenantManagementTestUtils.basicAuthHeader(
                                MASTER_USERNAME, MASTER_PASSWORD));
    }

    /** A tenant user's request, authenticated inside the given tenant. */
    private static RequestSpecification asTenantUser(
            final String tenant, final String username, final String password) {
        return base().header("Fineract-Platform-TenantId", tenant)
                .header(
                        "Authorization",
                        TenantManagementTestUtils.basicAuthHeader(username, password));
    }

    // ---------------------------------------------------------------
    // The master context
    // ---------------------------------------------------------------

    @Test
    @DisplayName("GET /v1/admin/tenants without credentials is rejected with 401")
    void listWithoutCredentials_isRejected() {
        base().when().get(TENANTS_PATH).then().statusCode(401);
    }

    @Test
    @DisplayName("A tenant's own super user is not a master user and is rejected with 401")
    void listAsTenantSuperUser_isRejected() {
        // mifos holds ALL_FUNCTIONS inside the default tenant. That is a tenant's permission, not
        // the super master role, so the master context does not recognise the user at all.
        asTenantUser(TenantManagementTestUtils.DEFAULT_TENANT, "mifos", "password")
                .when()
                .get(TENANTS_PATH)
                .then()
                .statusCode(401);
    }

    @Test
    @DisplayName("A master user with a wrong password is rejected with 401")
    void listWithAWrongMasterPassword_isRejected() {
        base().header(
                        "Authorization",
                        TenantManagementTestUtils.basicAuthHeader(MASTER_USERNAME, "wrong"))
                .when()
                .get(TENANTS_PATH)
                .then()
                .statusCode(401);
    }

    @Test
    @DisplayName("Core's /v1/tenants/{tenantId}/oidc-config stays on Fineract's own security chain")
    void coreOidcConfigEndpoint_isNotCapturedByTenantAdministration() {
        // Core Fineract serves this path. While tenant administration claimed /v1/tenants/**,
        // the master chain answered this tenant user's request with 401 before core saw it.
        // Reaching core's resource is proved by its own 404 for a tenant without OIDC setup.
        asTenantUser(TenantManagementTestUtils.DEFAULT_TENANT, "mifos", "password")
                .when()
                .get(TenantManagementTestUtils.CONTEXT_PATH + "/api/v1/tenants/default/oidc-config")
                .then()
                .statusCode(404)
                .body("errors[0].developerMessage", containsString("No OIDC configuration found"));
    }

    // ---------------------------------------------------------------
    // Reading the registry
    // ---------------------------------------------------------------

    @Test
    @DisplayName("GET /v1/admin/tenants as a master user lists tenants without credentials")
    void listAsMaster_returnsTenantsWithoutCredentials() {
        final Response response =
                asMaster().when().get(TENANTS_PATH).then().statusCode(200).extract().response();

        assertThat(response.jsonPath().getList("pageItems.identifier", String.class))
                .contains("default");
        assertThat(response.asString()).doesNotContainIgnoringCase("password");
    }

    @Test
    @DisplayName("GET /v1/admin/tenants/template lists the lifecycle statuses")
    void template_listsStatuses() {
        asMaster()
                .when()
                .get(TENANTS_PATH + "/template")
                .then()
                .statusCode(200)
                .body("statuses", equalTo(List.of("ACTIVE", "INACTIVE", "SUSPENDED")));
    }

    @Test
    @DisplayName("GET /v1/admin/tenants/{id} for an unknown tenant returns 404")
    void retrieveUnknownTenant_returns404() {
        asMaster().when().get(TENANTS_PATH + "/999999").then().statusCode(404);
    }

    @Test
    @DisplayName("GET /v1/admin/tenants with an unknown status filter returns 400")
    void list_withAnUnknownStatusFilter_returns400() {
        asMaster().when().get(TENANTS_PATH + "?status=DELETED").then().statusCode(400);
    }
}
