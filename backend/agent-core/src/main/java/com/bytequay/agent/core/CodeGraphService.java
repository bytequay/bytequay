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
package com.bytequay.agent.core;

import java.nio.file.Path;

/**
 * Optional structural code exploration for one managed task worktree.
 *
 * <p>Every task index is derived cache under the task runtime, cloned from a per-repository
 * baseline that holds the index of the remote default branch. Every operation binds to persisted
 * facts; nothing here accepts a model- or renderer-supplied path.
 */
public interface CodeGraphService
{
    /** Whether the helper is installed; when false every other operation is a no-op or an unavailable failure. */
    boolean available();

    /**
     * Brings the repository baseline to the fetched default-branch commit.
     *
     * <p>A no-op when the baseline already records that commit. Otherwise the baseline is rebuilt or
     * synchronized against a temporary detached checkout and replaced atomically; a failure leaves
     * the previous baseline untouched. Callers run this off every request and turn path.
     *
     * @param repositoryDirectory the managed repository that owns the commit
     * @throws CodeGraphException when the helper is unavailable or the refresh fails
     */
    void refreshBaseline(RepositoryIdentity repository, Path repositoryDirectory, String defaultBranchCommit);

    /**
     * Prepares the task index off the request path: waits for a running baseline refresh, clones the
     * baseline into the task runtime, and synchronizes the clone against the worktree. Without a
     * usable baseline the task index is built directly.
     *
     * <p>Best-effort: an expected failure is logged and leaves nothing behind, and the first tool
     * call then builds lazily. It never throws for an unavailable helper.
     */
    void prepare(TaskWorktree worktree, TaskResources resources);

    /**
     * Brings the task index up to date with the worktree, building it when absent, and runs one
     * bounded structural query. Waits a bounded time for a running {@link #prepare}.
     *
     * @return capped result text that says when it was truncated
     * @throws CodeGraphException when the helper is unavailable, the index is still being prepared,
     *         or the index cannot be brought current
     */
    String explore(TaskWorktree worktree, TaskResources resources, String query);

    /** Deletes the task's derived index. Best-effort: a missing index is not an error and a failure is logged, since retention removes leftovers. */
    void deleteIndex(TaskResources resources);

    /** Deletes the repository baseline. Best-effort: a missing baseline is not an error and a failure is logged. */
    void deleteBaseline(RepositoryIdentity repository);
}
