/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.domain;

/**
 * The tenant administration actions recorded in the audit trail.
 *
 * <p>One constant per endpoint that changes the registry. The rest of the vocabulary arrives with
 * the endpoints that record it, so the enum never names an action nothing can perform.
 */
public enum TenantAdministrationAction {
    CREATE
}
