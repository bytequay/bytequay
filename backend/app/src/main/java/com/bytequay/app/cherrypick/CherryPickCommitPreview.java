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
package com.bytequay.app.cherrypick;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * One source commit in preview order.
 *
 * @param position one-based cherry-pick order
 * @param patchEquivalent advisory only; such a commit stays selectable
 */
public record CherryPickCommitPreview(
        int position,
        String sourceSha,
        String sourceParentSha,
        String title,
        String author,
        Instant committedAt,
        List<String> changedPaths,
        boolean originallyEmpty,
        Optional<TargetMatch> targetMatch,
        boolean patchEquivalent,
        ConflictPreview conflict,
        Optional<PullRequestAssociation> pullRequest)
{
    public CherryPickCommitPreview
    {
        requireNonNull(sourceSha, "sourceSha is null");
        requireNonNull(sourceParentSha, "sourceParentSha is null");
        requireNonNull(title, "title is null");
        requireNonNull(author, "author is null");
        requireNonNull(committedAt, "committedAt is null");
        changedPaths = ImmutableList.copyOf(requireNonNull(changedPaths, "changedPaths is null"));
        requireNonNull(targetMatch, "targetMatch is null");
        requireNonNull(conflict, "conflict is null");
        requireNonNull(pullRequest, "pullRequest is null");
    }

    /** Derived from evidence, never from a title or a patch-similarity guess. */
    public boolean alreadyOnTarget()
    {
        return targetMatch.isPresent();
    }

    /**
     * Proof that a source commit is already represented on the target.
     *
     * <p>Only these two kinds exist. A matching title, a similar patch, or
     * {@code range-diff} output is never evidence.
     *
     * @param kind how the commit was found
     * @param targetCommitSha the target commit carrying the evidence
     */
    public record TargetMatch(Kind kind, String targetCommitSha)
    {
        public TargetMatch
        {
            requireNonNull(kind, "kind is null");
            requireNonNull(targetCommitSha, "targetCommitSha is null");
        }

        public enum Kind
        {
            DIRECT_ANCESTOR,
            CHERRY_PICK_ORIGIN
        }
    }
}
