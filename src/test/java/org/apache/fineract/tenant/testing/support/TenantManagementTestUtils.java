/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.testing.support;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Shared HTTP helpers for the integration tests. */
public final class TenantManagementTestUtils {

    private TenantManagementTestUtils() {}

    public static final String CONTEXT_PATH = "/fineract-provider";

    /** The tenant Fineract creates on first start. */
    public static final String DEFAULT_TENANT = "default";

    /** Builds a base-64 Basic Auth header value from username and password. */
    public static String basicAuthHeader(final String username, final String password) {
        final String raw = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
