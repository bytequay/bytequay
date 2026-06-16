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
package com.bytequay.app.beans.trace;

/**
 * Live state of the task's linked PR, used to render the parallel
 * sub-status block (CI / Reviewers / PR state / Approvals) under a
 * wait-state bucket or node. Populated only while the phase is a
 * wait-state; null otherwise (and on any PR-fetch failure).
 *
 * @param ciStatus PASSING | FAILING | PENDING | NONE
 */
public record LinkedActivePr(
        int prNumber,
        String ciStatus,
        boolean draft,
        int approvalCount,
        int changesRequestedCount,
        int pendingReviewerCount,
        int requestedReviewerCount)
{
}
