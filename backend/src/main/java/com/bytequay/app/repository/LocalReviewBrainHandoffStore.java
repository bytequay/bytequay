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

import java.time.Instant;
import java.util.List;

/**
 * Durable owed-Brain markers for green local-review validations: the
 * INTERNAL_REVIEW move and the marker commit together, and the marker
 * is consumed only once the Brain entry durably succeeded — a crash in
 * between can never lose the owed review. P3b's {@code openBrain}
 * consumes these atomically; until then a compatibility listener and
 * sweep deliver them.
 */
public interface LocalReviewBrainHandoffStore
{
    void insert(
            String validationClaimKey, String taskId, long throughSequence,
            String codeFingerprint, Instant createdAt);

    /** Unconsumed markers for one task, oldest first. */
    List<Handoff> listUnconsumedByTask(String taskId);

    /** Unconsumed markers across tasks — the recovery sweep's input. */
    List<Handoff> listUnconsumed(int limit);

    void markConsumed(String validationClaimKey, Instant at);

    void incrementDeliveryFailures(String validationClaimKey);

    record Handoff(
            String validationClaimKey,
            String taskId,
            long throughSequence,
            String codeFingerprint,
            int deliveryFailures)
    {
    }
}
