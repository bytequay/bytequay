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

import java.util.List;

/**
 * Execution start body.
 *
 * <p>Carries the exact commits the user chose to skip and the object IDs they
 * were chosen against. It cannot supply a different order or an extra commit.
 */
public record CherryPickExecutionRequest(
        String repository,
        String worktreeParent,
        String previewId,
        String expectedSourceBaseSha,
        String expectedSourceTipSha,
        String expectedTargetSha,
        List<String> userSkippedSourceShas,
        String laneId,
        String idempotencyKey) {}
