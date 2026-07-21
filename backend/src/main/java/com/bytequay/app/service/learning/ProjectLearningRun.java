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
package com.bytequay.app.service.learning;

/**
 * One durable, resumable project-learning run — a {@code
 * repo_learning_run} row. Owns its own catalog cursor and counts so a
 * restart resumes incomplete work rather than restarting the repository
 * from page one.
 */
public record ProjectLearningRun(
        String id,
        String workspaceId,
        String repo,
        String triggerKind,
        String state,
        String snapshotSha,
        String catalogCursor,
        String countsJson,
        int extractorVersion,
        long startedAtMs,
        long updatedAtMs,
        Long completedAtMs,
        String lastError)
{
    /** Live states a restart re-launches (mirrors the migration's live
     *  partial-unique index). */
    public boolean isLive()
    {
        return "queued".equals(state)
                || "indexing".equals(state)
                || "cataloging".equals(state);
    }
}
