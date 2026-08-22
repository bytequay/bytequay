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
package com.bytequay.app.web.cherrypick;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Execution start body.
 *
 * <p>Carries the exact commits the user chose to skip and the object IDs they
 * were chosen against. It cannot supply a different order or an extra commit.
 */
public record CherryPickExecutionRequest(
        String previewId,
        String expectedSourceBaseSha,
        String expectedSourceTipSha,
        String expectedTargetSha,
        List<String> userSkippedSourceShas,
        String laneId,
        String idempotencyKey,
        String pullRequestTitle,
        String pullRequestBody)
{
    public CherryPickExecutionRequest
    {
        requireNonNull(previewId, "previewId is null");
        requireNonNull(expectedSourceBaseSha, "expectedSourceBaseSha is null");
        requireNonNull(expectedSourceTipSha, "expectedSourceTipSha is null");
        requireNonNull(expectedTargetSha, "expectedTargetSha is null");
        userSkippedSourceShas = ImmutableList.copyOf(requireNonNull(
                userSkippedSourceShas, "userSkippedSourceShas is null"));
        requireNonNull(laneId, "laneId is null");
        requireNonNull(idempotencyKey, "idempotencyKey is null");
        requireNonNull(pullRequestTitle, "pullRequestTitle is null");
        requireNonNull(pullRequestBody, "pullRequestBody is null");
    }
}
