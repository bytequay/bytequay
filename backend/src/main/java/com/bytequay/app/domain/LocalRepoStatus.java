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

/**
 * One row of the Repos page — a watched repo plus its local-clone state.
 * The state pill the UI renders is derived directly from {@link #state};
 * detail (current branch, dirty file count) is null when the repo has
 * no managed clone or git itself is unavailable.
 *
 * @param localClonePath filesystem path of the user's working copy, or null
 * when the repo is watched but no managed clone exists yet.
 * @param currentBranch currently checked-out branch name. Null when the repo is
 * not cloned or git could not read HEAD.
 * @param dirtyFileCount count of changed files reported by
 * {@code git status --porcelain}. Null when the repo is not cloned.
 * @param errorMessage human-readable error from the last git operation,
 * surfaced when state is {@link State#ERROR}.
 * @param upstreamRemoteName name of the git remote that points at the watched
 * repo. Null when origin is the watched repo or the repo is not cloned.
 * @param defaultBranch repo's default branch as the local clone sees it.
 * @param viewFocus resolved focus for the repo detail page's commits tab:
 * {@code "fork"} or {@code "upstream"}.
 */
public record LocalRepoStatus(
        String owner,
        String repo,
        String localClonePath,
        State state,
        String currentBranch,
        Integer dirtyFileCount,
        String errorMessage,
        String upstreamRemoteName,
        String defaultBranch,
        String viewFocus)
{
    public enum State
    {
        /** Watched but no managed-clone path is set. */
        UNMAPPED,
        /** Working tree exists and matches HEAD — nothing modified,
         *  added, or untracked. */
        CLEAN,
        /** Working tree has uncommitted changes, untracked files, or
         *  staged-but-unsubmitted hunks. */
        MODIFIED,
        /** Path is set but doesn't resolve to a git working tree
         *  anymore — user moved or deleted the directory. */
        MISSING,
        /** git itself isn't available on the host. Most desktop Macs
         *  ship it via Xcode CLI tools; if that hasn't been installed
         *  the entire local-repo surface is degraded. */
        GIT_UNAVAILABLE,
        /** Catch-all for unexpected git failures — surfaced with the
         *  raw stderr for debugging. */
        ERROR
    }

    public static LocalRepoStatus unmapped(String owner, String repo)
    {
        return new LocalRepoStatus(owner, repo, null, State.UNMAPPED, null, null, null, null, null, "fork");
    }

    /** Resolves the effective view focus for a status row given the
     *  user's persisted choice (may be null) and whether the repo has
     *  an upstream remote configured. Returns {@code "upstream"} for
     *  forks with no explicit choice, {@code "fork"} otherwise. */
    public static String resolveViewFocus(String stored, String upstreamRemoteName)
    {
        if ("fork".equals(stored) || "upstream".equals(stored)) {
            return stored;
        }
        return upstreamRemoteName != null ? "upstream" : "fork";
    }
}
