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
 * One immutable permission to publish an exact reviewed worktree state.
 * The frozen payload is what retries use after a restart; no retry silently
 * adopts edited PR text, a new HEAD, or a different target repository.
 */
public record TaskPushAuthorization(
        String token,
        String taskId,
        String prId,
        String runId,
        String headSha,
        String codeFingerprint,
        Actor actor,
        String basisKind,
        String basisId,
        String overrideReason,
        String payloadJson,
        String payloadDigest,
        String effectKeysJson,
        Instant createdAt,
        Instant revokedAt,
        Instant consumedAt,
        String outcome)
{
    public static final String BASIS_BRAIN_REVIEW = "brain_review";
    public static final String BASIS_LEGACY_REMOTE = "legacy_remote_adoption";
    public static final String OUTCOME_PUSHED = "pushed";
    public static final String OUTCOME_REVOKED = "revoked";

    public boolean active()
    {
        return revokedAt == null && consumedAt == null;
    }
}
