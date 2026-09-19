/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.exception.UnrecognizedQueryParamException;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.tenant.data.TenantData;
import org.apache.fineract.tenant.domain.TenantStatus;
import org.apache.fineract.tenant.security.TenantMasterAccess;
import org.apache.fineract.tenant.service.TenantManagementReadService;
import org.springframework.stereotype.Component;

/**
 * Administration of the tenants on this installation, under {@code /v1/admin/tenants}.
 *
 * <p>Served in the master context: requests authenticate as a master user from the tenant store and
 * must hold the {@code SUPER_MASTER} role - see {@code TenantManagementSecurityConfiguration}. No
 * tenant user, however privileged inside its own tenant, can call these endpoints, and no {@code
 * Fineract-Platform-TenantId} header is needed.
 *
 * <p>The chain enforces the role before a request arrives here. Each method checks it again through
 * {@link TenantMasterAccess#requireSuperMaster()}, so a mistake in the chain's path matching fails
 * closed instead of exposing tenant administration.
 */
@Path("/v1/admin/tenants")
@Component
@Tag(
        name = "Tenant Management",
        description =
                "Administration of the tenants on this installation. Requires a master user with"
                        + " the SUPER_MASTER role; database credentials are never returned.")
@RequiredArgsConstructor
public class TenantManagementApiResource {

    private final TenantManagementReadService readService;
    private final DefaultToApiJsonSerializer<TenantData> toApiJsonSerializer;

    /** Lists tenants, optionally filtered and paged. */
    @GET
    @Produces({MediaType.APPLICATION_JSON})
    @Operation(
            summary = "List Tenants",
            description =
                    "Returns the tenants on this installation, newest registry entries last.\n\n"
                        + "Optional `search` matches the identifier and the name, case"
                        + " insensitively and literally. Optional `status` restricts to ACTIVE,"
                        + " INACTIVE or SUSPENDED. `offset` and `limit` page the result; `limit` is"
                        + " capped so one request cannot return an entire large registry.\n\n"
                        + "Database credentials are never included.")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content =
                    @Content(
                            schema =
                                    @Schema(
                                            implementation =
                                                    TenantManagementApiResourceSwagger
                                                            .GetTenantsResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "401", description = "Not authenticated as a master user")
    public String retrieveAll(
            @QueryParam("search")
                    @Parameter(
                            description =
                                    "Matches identifier and name, literally and case-insensitively")
                    final String search,
            @QueryParam("status") @Parameter(description = "ACTIVE, INACTIVE or SUSPENDED")
                    final String status,
            @QueryParam("offset") final Integer offset,
            @QueryParam("limit") final Integer limit) {

        TenantMasterAccess.requireSuperMaster();

        final TenantStatus statusFilter =
                status == null || status.isBlank()
                        ? null
                        : TenantStatus.fromString(status)
                                .orElseThrow(
                                        () ->
                                                new UnrecognizedQueryParamException(
                                                        "status",
                                                        status,
                                                        TenantStatus.names().toArray()));

        final Page<TenantData> tenants =
                readService.retrieveAll(search, statusFilter, offset, limit);
        return toApiJsonSerializer.serialize(tenants);
    }

    /** Options an administration client needs to build its create and edit forms. */
    @GET
    @Path("/template")
    @Produces({MediaType.APPLICATION_JSON})
    @Operation(
            summary = "Retrieve Tenant Template",
            description =
                    "Returns the selectable time zones and lifecycle statuses, so a client never"
                            + " hardcodes a list the backend owns.")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content =
                    @Content(
                            schema =
                                    @Schema(
                                            implementation =
                                                    TenantManagementApiResourceSwagger
                                                            .GetTenantsTemplateResponse.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated as a master user")
    public String retrieveTemplate() {
        TenantMasterAccess.requireSuperMaster();
        return toApiJsonSerializer.serialize(readService.retrieveTemplate());
    }

    /** Retrieves a single tenant. */
    @GET
    @Path("/{id}")
    @Produces({MediaType.APPLICATION_JSON})
    @Operation(
            summary = "Retrieve a Tenant",
            description = "Returns one tenant. Database credentials are never included.")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content =
                    @Content(
                            schema =
                                    @Schema(
                                            implementation =
                                                    TenantManagementApiResourceSwagger
                                                            .GetTenantResponse.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated as a master user")
    @ApiResponse(responseCode = "404", description = "No tenant has this id")
    public String retrieveOne(@PathParam("id") final Long id) {
        TenantMasterAccess.requireSuperMaster();
        return toApiJsonSerializer.serialize(readService.retrieveOne(id));
    }
}
