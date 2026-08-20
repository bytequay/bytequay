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
package com.bytequay.app.cherrypick;

import com.bytequay.core.cherrypick.CherryPickInvalidRequestException;
import com.google.common.collect.ImmutableList;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Confirmation: the exact commits the user chose to skip, against the exact
 * object IDs they were looking at.
 *
 * <p>Group checkbox state is never sent. If any expected ID has moved the
 * preview is stale, and the user reviews a fresh one rather than a plan they
 * did not see.
 */
public record StartCherryPickExecutionCommand(
        String previewId,
        String expectedSourceBaseSha,
        String expectedSourceTipSha,
        String expectedTargetSha,
        List<String> userSkippedSourceShas,
        String laneId,
        String idempotencyKey)
{
    private static final int MAX_SKIPPED = 5000;

    public StartCherryPickExecutionCommand
    {
        requireNonNull(previewId, "previewId is null");
        requireNonNull(expectedSourceBaseSha, "expectedSourceBaseSha is null");
        requireNonNull(expectedSourceTipSha, "expectedSourceTipSha is null");
        requireNonNull(expectedTargetSha, "expectedTargetSha is null");
        userSkippedSourceShas = ImmutableList.copyOf(
                requireNonNull(userSkippedSourceShas, "userSkippedSourceShas is null"));
        if (userSkippedSourceShas.size() > MAX_SKIPPED) {
            throw new CherryPickInvalidRequestException("userSkippedSourceShas exceeds " + MAX_SKIPPED + " values");
        }
        requireNonNull(laneId, "laneId is null");
        requireNonNull(idempotencyKey, "idempotencyKey is null");
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new CherryPickInvalidRequestException("idempotencyKey must contain between 1 and 200 characters");
        }
    }
}
