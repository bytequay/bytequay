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
package com.bytequay.app.domain;

import java.time.Instant;

/**
 * A safe-to-display view of a stored credential. The raw secret never leaves
 * the backend; this is what the Settings UI shows. Identified by the triple
 * (type, name, instanceName) — see {@link CredentialType} for the conventions
 * on {@code name}. {@code instanceName} lets multiple keys coexist for the
 * same provider (e.g. two DeepSeek keys, one personal and one work);
 * defaults to {@code "default api"} for callers that don't pick.
 */
public record Credential(
        long id,
        CredentialType type,
        String name,
        String instanceName,
        String label,
        String preview,
        String notes,
        /** True when this is the resolved default for its
         *  (type, name) group. Exactly one row per group carries
         *  this; an unnamed scope resolves to it. */
        boolean isDefault,
        Instant createdAt,
        Instant updatedAt,
        Instant lastUsedAt) {}
