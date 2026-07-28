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
package com.bytequay.app.developmentflow;

import static java.util.Objects.requireNonNull;

/**
 * Exact subject of asynchronous work. Aggregate version is intentionally not
 * part of the fence: additive commands may change a version without changing
 * the operation's subject.
 */
public record ResultFence(
        long taskEpoch,
        String stageId,
        long stageGeneration,
        String operationId,
        int attempt,
        String expectedCodeFingerprint,
        String expectedHeadSha,
        String expectedBaseSha)
{
    public ResultFence
    {
        if (taskEpoch < 1) {
            throw new IllegalArgumentException("taskEpoch must be positive");
        }
        requireNonNull(operationId, "operationId is null");
        if (operationId.isBlank()) {
            throw new IllegalArgumentException("operationId is blank");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        if (stageId == null && stageGeneration != 0) {
            throw new IllegalArgumentException("stageGeneration requires stageId");
        }
        if (stageId != null && (stageId.isBlank() || stageGeneration < 1)) {
            throw new IllegalArgumentException("stage identity is invalid");
        }
    }
}
