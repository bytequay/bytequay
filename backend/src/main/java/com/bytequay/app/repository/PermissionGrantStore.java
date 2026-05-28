/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app.repository;

import com.bytequay.app.domain.PermissionGrant;

import java.util.List;

/**
 * Read/write access to {@code permission_grant} rows. The
 * PermissionResolver only reads (per-scope lookups while walking the
 * cascade); create / delete exist so a settings surface can manage
 * grants later.
 */
public interface PermissionGrantStore
{
    /** Grants attached to the global scope (scope_id is null). */
    List<PermissionGrant> findGlobal();

    /** Grants attached to a narrower scope, keyed by its id. */
    List<PermissionGrant> findForScope(String scopeKind, String scopeId);

    /** Insert a grant. Returns the persisted row. */
    PermissionGrant create(
            String scopeKind,
            String scopeId,
            String capability,
            String mode,
            String paramsJson);

    /** Hard-delete by id. No-op when the id doesn't exist. */
    void delete(long id);
}
