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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * A pull request in its local phase — the full PR artifact (title,
 * description, and its commits / timeline / checks / comments child rows)
 * living entirely in ByteQuay before it ever reaches GitHub. It is pushed
 * to GitHub only on explicit user approval, after which it carries the
 * remote PR number / url and follows the remote half of the state machine.
 *
 * <p>The status wire values match the TypeScript {@code LocalPRStatus} union
 * (hyphenated), so — like {@link BacklogItem} — status is a {@code String}
 * with {@code STATUS_*} constants rather than a Java enum (enum names can't
 * carry hyphens). {@link #ALLOWED_TRANSITIONS} encodes the design's state
 * machine; every accepted flip is validated with {@link #canTransitionTo}.
 * Branch/push/merge runtime state is owned by the task row — {@code branchName}
 * is copied from the task at create time.
 */
public record LocalPR(
        String id,
        String taskId,
        String branchName,
        String baseBranch,
        String title,
        String description,
        String status,
        Instant createdAt,
        Instant pushedAt,
        Integer remotePrNumber,
        String remotePrUrl,
        Instant mergedAt,
        Instant closedAt,
        Instant localAddressedThroughAt)
{
    public static final String STATUS_LOCAL_DRAFTED = "local-drafted";
    public static final String STATUS_LOCAL_OPEN = "local-open";
    public static final String STATUS_REMOTE_DRAFTED = "remote-drafted";
    public static final String STATUS_REMOTE_OPEN = "remote-open";
    public static final String STATUS_MERGED = "merged";
    public static final String STATUS_CLOSED = "closed";

    /**
     * Legal status flips (design #45). A task can be abandoned from any
     * non-terminal state, so every one also permits {@code closed}. {@code
     * merged} and {@code closed} are terminal (no outgoing edges).
     */
    public static final Map<String, Set<String>> ALLOWED_TRANSITIONS = ImmutableMap.of(
            STATUS_LOCAL_DRAFTED, ImmutableSet.of(STATUS_LOCAL_OPEN, STATUS_CLOSED),
            STATUS_LOCAL_OPEN, ImmutableSet.of(STATUS_REMOTE_DRAFTED, STATUS_CLOSED),
            STATUS_REMOTE_DRAFTED, ImmutableSet.of(STATUS_REMOTE_OPEN, STATUS_MERGED, STATUS_CLOSED),
            STATUS_REMOTE_OPEN, ImmutableSet.of(STATUS_MERGED, STATUS_CLOSED),
            STATUS_MERGED, ImmutableSet.of(),
            STATUS_CLOSED, ImmutableSet.of());

    /** A freshly-created local PR: {@code local-drafted}, no push/merge stamps. */
    public static LocalPR create(
            String id,
            String taskId,
            String branchName,
            String baseBranch,
            String title,
            String description,
            Instant createdAt)
    {
        return new LocalPR(
                id, taskId, branchName, baseBranch, title,
                description == null ? "" : description,
                STATUS_LOCAL_DRAFTED, createdAt,
                /* pushedAt */ null, /* remotePrNumber */ null, /* remotePrUrl */ null,
                /* mergedAt */ null, /* closedAt */ null, /* localAddressedThroughAt */ null);
    }

    /** True iff {@code target} is a legal next status from the current one. */
    public boolean canTransitionTo(String target)
    {
        return ALLOWED_TRANSITIONS.getOrDefault(status, Set.of()).contains(target);
    }

    /** Whether this PR has reached a terminal state (merged / closed). */
    public boolean isTerminal()
    {
        return STATUS_MERGED.equals(status) || STATUS_CLOSED.equals(status);
    }

    /** Copy with an edited title / description (the {@code record_pr_description}
     *  tool + the PATCH endpoint). A null argument leaves that field unchanged. */
    public LocalPR withDetails(String newTitle, String newDescription)
    {
        return new LocalPR(
                id, taskId, branchName, baseBranch,
                newTitle == null ? title : newTitle,
                newDescription == null ? description : newDescription,
                status, createdAt, pushedAt, remotePrNumber, remotePrUrl, mergedAt, closedAt,
                localAddressedThroughAt);
    }

    /**
     * Copy at a new status, stamping the terminal / push timestamps the flip
     * implies. Callers must have checked {@link #canTransitionTo}; the
     * timestamps ({@code when}, plus push metadata for a push flip) are set
     * only for the states that own them.
     */
    public LocalPR withStatus(String newStatus, Instant when)
    {
        return new LocalPR(
                id, taskId, branchName, baseBranch, title, description, newStatus, createdAt,
                STATUS_REMOTE_DRAFTED.equals(newStatus) && pushedAt == null ? when : pushedAt,
                remotePrNumber, remotePrUrl,
                STATUS_MERGED.equals(newStatus) ? when : mergedAt,
                STATUS_CLOSED.equals(newStatus) ? when : closedAt,
                localAddressedThroughAt);
    }

    /** Copy recording the remote PR identity assigned on push. */
    public LocalPR withRemote(int number, String url, Instant when)
    {
        return new LocalPR(
                id, taskId, branchName, baseBranch, title, description, status, createdAt,
                pushedAt == null ? when : pushedAt, number, url, mergedAt, closedAt,
                localAddressedThroughAt);
    }

    /** Copy with the local-addressing marker advanced to {@code through} — the
     *  high-water mark past which every {@code local_pr_comment} has already
     *  triggered (or been folded into) an addressing turn. Mirrors {@code
     *  TaskReviewMarkerStore} for the remote loop, scoped here since a
     *  {@code LocalPR} is already 1:1 with its task. */
    public LocalPR withLocalAddressedThrough(Instant through)
    {
        return new LocalPR(
                id, taskId, branchName, baseBranch, title, description, status, createdAt,
                pushedAt, remotePrNumber, remotePrUrl, mergedAt, closedAt, through);
    }
}
