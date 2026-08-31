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
package com.bytequay.app.web.ci;

/** Workspace-owned replacement for the current renderer-selected CI worktree request. */
final class ManagedCiAutofixHttpContract
{
    private ManagedCiAutofixHttpContract() {}

    record StartRequest(
            String workspaceId,
            int pullRequestNumber,
            String laneId,
            int maxAttempts,
            boolean allowFailedJobReruns,
            boolean allowHistoryRewrites,
            boolean allowExactLeasePush) {}

    record BranchFacts(
            String remoteHeadRef,
            String generatedLocalBranch) {}
}
