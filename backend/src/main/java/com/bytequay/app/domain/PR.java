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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The unified PR aggregate — either a {@code task}-origin PR that starts in
 * its local phase (title, description, commits / timeline / checks / comments
 * child rows living entirely in ByteQuay before an explicit user push) or an
 * {@code external} PR opened outside the app and synced in from GitHub. Once
 * pushed, a task-origin PR carries the remote PR number / url and follows the
 * remote half of the state machine; an external PR is always remote-origin
 * and only ever occupies the remote/terminal statuses.
 *
 * <p>The status wire values match the TypeScript {@code PRStatus} union
 * (hyphenated), so — like {@link BacklogItem} — status is a {@code String}
 * with {@code STATUS_*} constants rather than a Java enum (enum names can't
 * carry hyphens). {@link #ALLOWED_TRANSITIONS} encodes the design's state
 * machine; every accepted flip is validated with {@link #canTransitionTo}.
 * Branch/push/merge runtime state is owned by the task row — {@code branchName}
 * is copied from the task at create time (task-origin only).
 */
public record PR(
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
        Instant localAddressedThroughAt,
        String origin,
        String repo,
        String author,
        Instant syncedAt,
        PRSyncSnapshot githubSync,
        Instant branchDeletedAt)
{
    public static final String STATUS_LOCAL_DRAFTED = "local-drafted";
    public static final String STATUS_LOCAL_OPEN = "local-open";
    public static final String STATUS_REMOTE_DRAFTED = "remote-drafted";
    public static final String STATUS_REMOTE_OPEN = "remote-open";
    public static final String STATUS_MERGED = "merged";
    public static final String STATUS_CLOSED = "closed";

    public static final String ORIGIN_TASK = "task";
    public static final String ORIGIN_EXTERNAL = "external";

    /** Statuses an {@code external} PR may occupy — never the local-only ones. */
    public static final Set<String> EXTERNAL_STATUSES =
            ImmutableSet.of(STATUS_REMOTE_DRAFTED, STATUS_REMOTE_OPEN, STATUS_MERGED, STATUS_CLOSED);

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
    public static PR create(
            String id,
            String taskId,
            String branchName,
            String baseBranch,
            String title,
            String description,
            Instant createdAt)
    {
        return new PR(
                id, taskId, branchName, baseBranch, title,
                description == null ? "" : description,
                STATUS_LOCAL_DRAFTED, createdAt,
                /* pushedAt */ null, /* remotePrNumber */ null, /* remotePrUrl */ null,
                /* mergedAt */ null, /* closedAt */ null, /* localAddressedThroughAt */ null,
                ORIGIN_TASK, /* repo */ null, /* author */ null, /* syncedAt */ null, /* githubSync */ null,
                /* branchDeletedAt */ null);
    }

    /** A PR discovered via the dashboard sync — already occupies whatever
     *  remote/terminal status GitHub reports today (never local-only).
     *  {@code mergedAt}/{@code closedAt} carry GitHub's real timestamps
     *  (null unless the status says otherwise). */
    public static PR createExternal(
            String id,
            String repo,
            int remotePrNumber,
            String remotePrUrl,
            String author,
            String branchName,
            String baseBranch,
            String title,
            String description,
            String status,
            Instant createdAt,
            Instant mergedAt,
            Instant closedAt)
    {
        return new PR(
                id, /* taskId */ null, branchName, baseBranch, title,
                description == null ? "" : description, status, createdAt,
                /* pushedAt */ null, remotePrNumber, remotePrUrl, mergedAt, closedAt,
                /* localAddressedThroughAt */ null,
                ORIGIN_EXTERNAL, repo, author, /* syncedAt */ null, /* githubSync */ null,
                /* branchDeletedAt */ null);
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
    public PR withDetails(String newTitle, String newDescription)
    {
        return new PR(
                id, taskId, branchName, baseBranch,
                newTitle == null ? title : newTitle,
                newDescription == null ? description : newDescription,
                status, createdAt, pushedAt, remotePrNumber, remotePrUrl, mergedAt, closedAt,
                localAddressedThroughAt, origin, repo, author, syncedAt, githubSync, branchDeletedAt);
    }

    /**
     * Copy at a new status, stamping the terminal / push timestamps the flip
     * implies. Callers must have checked {@link #canTransitionTo}; the
     * timestamps ({@code when}, plus push metadata for a push flip) are set
     * only for the states that own them.
     */
    public PR withStatus(String newStatus, Instant when)
    {
        return new PR(
                id, taskId, branchName, baseBranch, title, description, newStatus, createdAt,
                STATUS_REMOTE_DRAFTED.equals(newStatus) && pushedAt == null ? when : pushedAt,
                remotePrNumber, remotePrUrl,
                STATUS_MERGED.equals(newStatus) ? when : mergedAt,
                STATUS_CLOSED.equals(newStatus) ? when : closedAt,
                localAddressedThroughAt, origin, repo, author, syncedAt, githubSync, branchDeletedAt);
    }

    /** Pure read-model overlay for terminal state already observed by the
     *  repository PR sync. It does not mutate the Task-owned PR aggregate. */
    public PR withRemoteTerminalProjection(
            String terminalStatus, Instant remoteMergedAt, Instant remoteClosedAt)
    {
        if (!STATUS_MERGED.equals(terminalStatus)
                && !STATUS_CLOSED.equals(terminalStatus)) {
            throw new IllegalArgumentException(
                    "Remote terminal projection must be merged or closed");
        }
        return new PR(
                id, taskId, branchName, baseBranch, title, description,
                terminalStatus, createdAt, pushedAt, remotePrNumber, remotePrUrl,
                remoteMergedAt, remoteClosedAt, localAddressedThroughAt, origin,
                repo, author, syncedAt, githubSync, branchDeletedAt);
    }

    /** Copy recording the remote PR identity assigned on push — including the
     *  {@code owner/repo} slug, which a task-origin PR has no other way to
     *  learn (it starts {@code null} and nothing else ever backfills it). */
    public PR withRemote(String newRepo, int number, String url, Instant when)
    {
        return new PR(
                id, taskId, branchName, baseBranch, title, description, status, createdAt,
                pushedAt == null ? when : pushedAt, number, url, mergedAt, closedAt,
                localAddressedThroughAt, origin, newRepo, author, syncedAt, githubSync, branchDeletedAt);
    }

    /** Copy with the GitHub login that owns the remote PR. Task-origin PRs
     *  learn this once they are pushed, so the remote conversation can render
     *  the description as authored by the GitHub account rather than the local
     *  dev agent. */
    public PR withAuthor(String newAuthor)
    {
        return new PR(
                id, taskId, branchName, baseBranch, title, description, status, createdAt,
                pushedAt, remotePrNumber, remotePrUrl, mergedAt, closedAt, localAddressedThroughAt,
                origin, repo, newAuthor == null ? author : newAuthor, syncedAt, githubSync, branchDeletedAt);
    }

    /** Copy with the local-addressing marker advanced to {@code through} — the
     *  high-water mark past which every {@code pr_comment} has already
     *  triggered (or been folded into) an addressing turn. Mirrors {@code
     *  TaskReviewMarkerStore} for the remote loop, scoped here since a
     *  {@code PR} is already 1:1 with its task. */
    public PR withLocalAddressedThrough(Instant through)
    {
        return new PR(
                id, taskId, branchName, baseBranch, title, description, status, createdAt,
                pushedAt, remotePrNumber, remotePrUrl, mergedAt, closedAt, through,
                origin, repo, author, syncedAt, githubSync, branchDeletedAt);
    }

    /** Copy correcting the head/base branch names — {@code syncList}'s
     *  initial create for an external PR has no better guess than "unknown"/
     *  the default base (GitHub's search API never returns {@code head.ref}),
     *  so the first successful detail fetch backfills the real names here. */
    public PR withBranches(String newBranchName, String newBaseBranch)
    {
        return new PR(
                id, taskId,
                newBranchName == null ? branchName : newBranchName,
                newBaseBranch == null ? baseBranch : newBaseBranch,
                title, description, status, createdAt,
                pushedAt, remotePrNumber, remotePrUrl, mergedAt, closedAt, localAddressedThroughAt,
                origin, repo, author, syncedAt, githubSync, branchDeletedAt);
    }

    /** Copy stamping a successful GitHub sync — {@code PRSyncService} calls
     *  this once a {@code syncPR} pass completes, so the sync chip has a
     *  real "synced Xs ago" to show. */
    public PR withSynced(Instant when)
    {
        return new PR(
                id, taskId, branchName, baseBranch, title, description, status, createdAt,
                pushedAt, remotePrNumber, remotePrUrl, mergedAt, closedAt, localAddressedThroughAt,
                origin, repo, author, when, githubSync, branchDeletedAt);
    }

    /** Copy with a freshly-fetched dashboard sync snapshot — {@code syncList}
     *  calls this after every list/detail sweep. Kept as one nested field
     *  (rather than flattening ~14 columns into this record) so the dashboard
     *  sync surface stays isolated from every other {@code with*} caller. */
    public PR withGithubSync(PRSyncSnapshot snapshot)
    {
        return new PR(
                id, taskId, branchName, baseBranch, title, description, status, createdAt,
                pushedAt, remotePrNumber, remotePrUrl, mergedAt, closedAt, localAddressedThroughAt,
                origin, repo, author, syncedAt, snapshot, branchDeletedAt);
    }

    /** Copy recording that the app deleted the head branch on GitHub after a
     *  merge — drives the merge-box's "Delete branch" affordance (hidden
     *  once this is stamped). */
    public PR withBranchDeleted(Instant when)
    {
        return new PR(
                id, taskId, branchName, baseBranch, title, description, status, createdAt,
                pushedAt, remotePrNumber, remotePrUrl, mergedAt, closedAt, localAddressedThroughAt,
                origin, repo, author, syncedAt, githubSync, when);
    }

    /**
     * The dashboard-sync-derived fields {@code syncList} maintains — GitHub
     * search/detail data, never touched outside that pipeline. Absent
     * ({@code githubSync == null}) for a PR that has never appeared in the
     * dashboard's relevant-PR search (a task-origin PR still in its local
     * phase, or an external PR the search has never surfaced).
     */
    public record PRSyncSnapshot(
            PullRequest.Origin watchReason,
            Instant ghUpdatedAt,
            List<String> labels,
            Map<String, String> labelColors,
            boolean draft,
            PullRequestDetail.CiStatus ciStatus,
            int additions,
            int deletions,
            int commentCount,
            AttentionReason attentionReason,
            Boolean mergeable,
            String mergeableState,
            Instant headPushedAt,
            Map<String, String> reviewerVerdicts,
            List<String> requestedReviewers,
            boolean mergeQueueEnabled,
            String mergeQueueState)
    {
    }
}
