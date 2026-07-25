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

/**
 * A claimed local-review (roots-closed) validation finished. Carries
 * the claim's full identity so the acceptance command can re-verify
 * the task epoch and root set still match before consuming it —
 * distinct from the generic {@link ValidationPassFinishedEvent} the
 * VALIDATING-phase machine listens for.
 */
public record LocalReviewValidationFinishedEvent(
        String taskId,
        String claimKey,
        long throughSequence,
        String rootSetDigest,
        String codeFingerprint,
        boolean passed)
{
}
