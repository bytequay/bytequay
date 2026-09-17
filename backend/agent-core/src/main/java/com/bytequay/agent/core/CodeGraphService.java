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
 * facts. The only caller-supplied path is {@link #fileSymbols}'s repository-relative file, which the
 * caller has already validated; nothing here accepts a model-supplied path.
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

    /**
     * Searches the task index for symbols by name, for a read-only UI.
     *
     * <p>Read contract, unlike {@link #explore}: the task's graph lock is tried for a short bounded
     * time and {@link CodeSymbolResult.Status#BUSY} is returned when it is held. The index is never
     * built, rebuilt, or deleted on the caller's thread. When it is usable and records the worktree
     * HEAD, a plain sync runs first so saved edits appear, whatever their size; a failed sync
     * leaves the index alone and reports {@link CodeSymbolResult.Status#UNAVAILABLE}. A missing
     * index or a moved HEAD queues {@link #prepare} in the background and returns
     * {@link CodeSymbolResult.Status#UPDATING}. Expected failures never throw.
     *
     * @param limit maximum symbols, 1 to 200
     */
    CodeSymbolResult searchSymbols(TaskWorktree worktree, TaskResources resources, String query, int limit);

    /**
     * Lists the symbols the task index records for one file, under the {@link #searchSymbols} read contract.
     *
     * @param path repository-relative file path the caller already validated
     */
    CodeSymbolResult fileSymbols(TaskWorktree worktree, TaskResources resources, String path);

    /** Deletes the task's derived index. Best-effort: a missing index is not an error and a failure is logged, since retention removes leftovers. */
    void deleteIndex(TaskResources resources);

    /** Deletes the repository baseline; throws {@link CodeGraphException} if cleanup cannot finish. Missing files are harmless. */
    void deleteBaseline(RepositoryIdentity repository);
}
