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
package com.bytequay.app.service.checks;

import java.util.List;

/**
 * Fired when a ValidationPass finishes. The {@code TaskPhaseMachine}
 * listens: {@code passed} drives VALIDATING ▶ INTERNAL_REVIEW; a failed
 * check drives VALIDATING ▶ NEEDS_ATTENTION with the failures attached.
 */
public record ValidationPassFinishedEvent(
        String taskId,
        boolean passed,
        List<ValidationFailure> failures,
        String claimKey,
        String codeFingerprint,
        Long validationEpoch)
{
    /** Compatibility shape for the legacy, unclaimed validation path. */
    public ValidationPassFinishedEvent(
            String taskId, boolean passed, List<ValidationFailure> failures)
    {
        this(taskId, passed, failures, null, null, null);
    }
}
