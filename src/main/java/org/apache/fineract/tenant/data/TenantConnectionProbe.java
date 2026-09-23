/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.data;

/**
 * What a probe of a tenant's database found.
 *
 * <p>The endpoint originally answered {@code reachable} alone, which could not be acted on before a
 * tenant existed: it connects to the target database, and during creation that database has not
 * been created yet, so the answer was {@code false} whether or not the credentials were right. The
 * three fields beside it separate the states that matters most to tell apart - a refused password
 * from a database that simply is not there yet.
 *
 * @param reachable the target database itself answered; unchanged in meaning, so a client written
 *     against the original response keeps working
 * @param serverReachable the database server answered at all
 * @param credentialsAccepted the server accepted the username and password
 * @param schemaPresent the server already has a database of the requested name, which creation will
 *     reuse rather than empty
 */
public record TenantConnectionProbe(
        boolean reachable,
        boolean serverReachable,
        boolean credentialsAccepted,
        boolean schemaPresent) {

    /** The target database answered, so everything before it did too. */
    public static TenantConnectionProbe usable() {
        return new TenantConnectionProbe(true, true, true, true);
    }

    /** The server answered and accepted the credentials; the target database did not answer. */
    public static TenantConnectionProbe serverOnly(final boolean schemaPresent) {
        return new TenantConnectionProbe(false, true, true, schemaPresent);
    }

    /** The server answered and refused the credentials. */
    public static TenantConnectionProbe credentialsRejected() {
        return new TenantConnectionProbe(false, true, false, false);
    }

    /** Nothing answered. */
    public static TenantConnectionProbe unreachable() {
        return new TenantConnectionProbe(false, false, false, false);
    }
}
