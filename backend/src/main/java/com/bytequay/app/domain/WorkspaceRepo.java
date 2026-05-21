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
 * {@code defaultBaseBranch} carries the per-repo merge-target so
 * ship-and-continue cuts the next task's branch from the right base
 * — e.g. {@code upstream/master} for a fork of trino, {@code main}
 * for an owned repo. Null falls back to the local clone's default
 * branch as discovered by git.
 *
 * @param repoFullName GitHub's owner/repo form, e.g. "trinodb/trino".
 */
public record WorkspaceRepo(
        String workspaceId,
        String repoFullName,
        String defaultBaseBranch,
        Instant addedAt)
{
}
