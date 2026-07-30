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
package com.bytequay.app.domain;

import java.time.Instant;

/**
 * One repo attached to a {@link Workspace}. The
 * {@code defaultBaseBranch} carries the bare merge-target branch so
 * task creation and eventual PR publication use the right base — e.g.
 * {@code master} for Trino or {@code main} for an owned repo. A planning
 * operation separately records the remote-qualified tracking ref and exact
 * SHA. Null is retained only for historical rows awaiting startup repair;
 * V2 Task creation fails closed instead of guessing.
 *
 * @param repoFullName GitHub's owner/repo form, e.g. "trinodb/trino".
 */
public record WorkspaceRepo(
        String workspaceId,
        String repoFullName,
        String defaultBaseBranch,
        /** Opt-in for the headless auto-fix runner. Off by default
         *  per CLAUDE.md — only when the user explicitly enables
         *  this will AutomationCoordinator spawn a CLI agent against
         *  free worktrees when their linked PRs flip to failing CI. */
        boolean autoFixEnabled,
        Instant addedAt)
{
}
