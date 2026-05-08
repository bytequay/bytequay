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
 * detail (current branch, dirty file count) is null when the repo is
 * unmapped or git itself is unavailable.
 */
public record LocalRepoStatus(
        String owner,
        String repo,
        /** Filesystem path of the user's working copy, or null when the
         *  repo is watched but no clone has been mapped to it yet. */
        String localClonePath,
        State state,
        /** Currently checked-out branch name. Null when the repo is
         *  unmapped or git couldn't read HEAD (e.g. detached, broken). */
        String currentBranch,
        /** Count of changed files reported by `git status --porcelain`.
         *  Null when the repo is unmapped. Zero on a CLEAN repo. */
        Integer dirtyFileCount,
        /** Human-readable error from the last git operation, surfaced
         *  when state is {@link State#ERROR}. */
        String errorMessage,
        /** Name of the git remote that points at the watched repo.
         *  Null when origin is the watched repo (direct clone) or the
         *  repo isn't mapped. Drives the "Base: trinodb/trino" hint
         *  on the page header and the default base for Create-PR. */
        String upstreamRemoteName,
        /** Repo's default branch as the local clone sees it
         *  ({@code git symbolic-ref refs/remotes/origin/HEAD}). Used
         *  to pre-fill the Base field in the Create-PR modal so a fork
         *  whose upstream defaults to {@code master} (Trino, etc.)
         *  doesn't surprise the user with {@code main}. Null when
         *  origin/HEAD isn't set or the repo is unmapped. */
        String defaultBranch)
{
    public enum State
    {
        /** Watched but no local-clone path is set. UI shows the
         *  "Map clone…" call-to-action. */
        UNMAPPED,
        /** Working tree exists and matches HEAD — nothing modified,
         *  added, or untracked. */
        CLEAN,
        /** Working tree has uncommitted changes, untracked files, or
         *  staged-but-unsubmitted hunks. */
        MODIFIED,
        /** Path is set but doesn't resolve to a git working tree
         *  anymore — user moved or deleted the directory. UI offers
         *  to re-map or unset. */
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
        return new LocalRepoStatus(owner, repo, null, State.UNMAPPED, null, null, null, null, null);
    }
}
