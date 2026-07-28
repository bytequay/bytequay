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
package com.bytequay.app.developmentflow.task;

import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageKind;

import static java.util.Objects.requireNonNull;

/** Exact Stage-owner boundary for rearming one parked resume checkpoint. */
public interface TaskResumeOwner
{
    StageKind kind();

    /**
     * Persists the exact owner-specific rearm intent durably and idempotently
     * before acknowledging it. The intent becomes runnable after the Task is
     * ACTIVE; acceptance must not create a live turn or dispatch ticket while
     * the Task still owns the stopped RESUMING boundary. Replaying the same
     * handoff id must return the same owner proof.
     */
    Acceptance accept(Request request);

    record Request(
            String handoffId,
            String taskId,
            long taskEpoch,
            long taskVersion,
            String stageId,
            StageKind stageKind,
            long stageGeneration,
            long stageVersion,
            StageCheckpoint restoreCheckpoint,
            String reconciliationId,
            String codeFingerprint,
            String headSha,
            String baseSha)
    {
        public Request
        {
            requireText(handoffId, "handoffId");
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireNonNull(stageKind, "stageKind is null");
            requireNonNull(restoreCheckpoint, "restoreCheckpoint is null");
            requireText(reconciliationId, "reconciliationId");
            requireText(codeFingerprint, "codeFingerprint");
            requireText(headSha, "headSha");
            requireText(baseSha, "baseSha");
            if (taskEpoch < 1 || taskVersion < 1
                    || stageGeneration < 1 || stageVersion < 0) {
                throw new IllegalArgumentException("resume owner fence is invalid");
            }
            if (stageKind == StageKind.CLEANUP) {
                throw new IllegalArgumentException(
                        "Cleanup Stage cannot own a Task resume");
            }
        }
    }

    record Acceptance(String handoffId, String ownerProofId, String acceptedBy)
    {
        public Acceptance
        {
            requireText(handoffId, "handoffId");
            requireText(ownerProofId, "ownerProofId");
            requireText(acceptedBy, "acceptedBy");
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
