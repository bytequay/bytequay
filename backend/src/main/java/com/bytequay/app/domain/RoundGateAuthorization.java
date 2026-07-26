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

/** One immutable human authorization for an exact external-review payload. */
public record RoundGateAuthorization(
        String token,
        String taskId,
        String roundId,
        int gateRevision,
        int attempt,
        Actor actor,
        String codeFingerprint,
        String payloadJson,
        String payloadDigest,
        String effectKeysJson,
        Instant approvedAt,
        Instant revokedAt,
        Instant consumedAt,
        String outcome)
{
    public static final String OUTCOME_POSTED = "posted";

    public boolean active()
    {
        return revokedAt == null && consumedAt == null;
    }
}
