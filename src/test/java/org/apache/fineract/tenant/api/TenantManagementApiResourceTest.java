/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import org.apache.fineract.infrastructure.core.exception.UnrecognizedQueryParamException;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.tenant.data.TenantData;
import org.apache.fineract.tenant.domain.TenantStatus;
import org.apache.fineract.tenant.service.TenantManagementReadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class TenantManagementApiResourceTest {

    private static final TenantData A_TENANT =
            new TenantData(
                    1L,
                    "acme",
                    "Acme",
                    "Asia/Kolkata",
                    TenantStatus.ACTIVE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

    private TenantManagementReadService readService;
    private TenantManagementApiResource resource;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        readService = mock(TenantManagementReadService.class);
        when(readService.retrieveAll(any(), any(), any(), any()))
                .thenReturn(new Page<>(List.of(A_TENANT), 1));
        when(readService.retrieveOne(anyLong())).thenReturn(A_TENANT);

        resource =
                new TenantManagementApiResource(
                        readService, mock(DefaultToApiJsonSerializer.class));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(final String username, final String... roles) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                username,
                                null,
                                Arrays.stream(roles)
                                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                                        .toList()));
    }

    // ---------------------------------------------------------------
    // Every endpoint requires the super master role, independently of the chain
    // ---------------------------------------------------------------

    @Test
    void everyEndpointServesASuperMaster() {
        authenticateAs("master", "SUPER_MASTER");

        resource.retrieveAll(null, null, null, null);
        resource.retrieveTemplate();
        resource.retrieveOne(1L);

        verify(readService).retrieveAll(null, null, null, null);
        verify(readService).retrieveTemplate();
        verify(readService).retrieveOne(1L);
    }

    @Test
    void anUnauthenticatedCallIsRefusedBeforeAnyWork() {
        assertThrows(
                NoAuthorizationException.class, () -> resource.retrieveAll(null, null, null, null));
        assertThrows(NoAuthorizationException.class, () -> resource.retrieveOne(1L));

        verify(readService, never()).retrieveAll(any(), any(), any(), any());
        verify(readService, never()).retrieveOne(anyLong());
    }

    @Test
    void anAuthenticatedUserWithoutTheSuperMasterRoleIsRefused() {
        // A tenant user who reached this code by any route - even one holding every
        // permission inside their own tenant - is not a master user.
        authenticateAs("mifos", "ALL_FUNCTIONS");

        assertThrows(NoAuthorizationException.class, () -> resource.retrieveTemplate());
        assertThrows(NoAuthorizationException.class, () -> resource.retrieveOne(1L));
        assertThrows(
                NoAuthorizationException.class, () -> resource.retrieveAll(null, null, null, null));

        verify(readService, never()).retrieveTemplate();
        verify(readService, never()).retrieveOne(anyLong());
    }

    // ---------------------------------------------------------------
    // Filters
    // ---------------------------------------------------------------

    @Test
    void list_rejectsAnUnknownStatusFilter() {
        authenticateAs("master", "SUPER_MASTER");

        assertThrows(
                UnrecognizedQueryParamException.class,
                () -> resource.retrieveAll(null, "DELETED", null, null));
    }

    @Test
    void list_treatsABlankStatusFilterAsNoFilter() {
        authenticateAs("master", "SUPER_MASTER");

        resource.retrieveAll(null, "  ", null, null);

        verify(readService).retrieveAll(null, null, null, null);
    }
}
