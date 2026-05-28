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
 * One permission grant at a single scope in the cascade. The
 * PermissionResolver walks global → workspace → thread → task and
 * applies these to tighten the caller's role-derived base set.
 *
 * @param scopeKind  'global' / 'workspace' / 'thread' / 'task'
 * @param scopeId    null for global; the workspace / thread / task id
 *                   for the narrower scopes
 * @param capability a {@link com.bytequay.app.service.tools.SecurityType}
 *                   name
 * @param mode       'allow' / 'deny' / 'inherit' — only 'deny'
 *                   subtracts under the tighten-only model
 * @param paramsJson optional structured policy for the capability;
 *                   stored now, interpreted by future policy code
 */
public record PermissionGrant(
        long id,
        String scopeKind,
        String scopeId,
        String capability,
        String mode,
        String paramsJson,
        Instant createdAt,
        Instant updatedAt) {}
