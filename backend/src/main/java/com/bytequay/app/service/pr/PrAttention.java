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
package com.bytequay.app.service.pr;

import com.bytequay.app.domain.AttentionReason;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.StoredPrDetail;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Promotes a PR into the "Needs attention" Kanban column. Called per PR
 * after the detail sync has refreshed StoredPrDetail.
 *
 * <p>v1 rules — see docs/design/settings-redesign.md §6.5:
 * <ul>
 *   <li>{@link AttentionReason#CI_FAILING} — aggregate CI is FAILING.</li>
 *   <li>{@link AttentionReason#MENTIONED} — a comment body contains
 *       {@code @currentLogin} that arrived after the user last viewed the
 *       PR (or any mention if the PR has never been viewed).</li>
 *   <li>{@link AttentionReason#BLOCKING} — any label contains "block"
 *       (covers "blocking", "priority/blocker", etc.).</li>
 *   <li>{@link AttentionReason#STALE} — updatedAt is older than 7 days.</li>
 * </ul>
 *
 * <p>Order of precedence is the order above; first match wins.
 */
public final class PrAttention
{
    private static final Duration STALE_THRESHOLD = Duration.ofDays(7);

    private PrAttention() {}

    public static AttentionReason promoteReason(
            PullRequest pr,
            StoredPrDetail detail,
            String currentLogin,
            Instant viewedAt,
            Instant now)
    {
        if (detail == null) {
            return null;
        }
        boolean mine = pr.origin() == PullRequest.Origin.AUTHORED;
        PullRequestDetail.CiStatus ciStatus = aggregateCiStatus(detail);
        if (ciStatus == PullRequestDetail.CiStatus.FAILING) {
            return AttentionReason.CI_FAILING;
        }
        // Merge conflict only fires for PRs the user has to fix — i.e.
        // their own authored PRs. Reviewers can't resolve someone else's
        // conflict, so promoting it on review-requested PRs would be noise.
        if (mine && hasMergeConflict(detail)) {
            return AttentionReason.MERGE_CONFLICT;
        }
        if (hasUnseenMention(detail.timeline(), currentLogin, viewedAt)) {
            return AttentionReason.MENTIONED;
        }
        if (mine && hasUnseenActivity(detail.timeline(), currentLogin, viewedAt)) {
            return AttentionReason.NEW_COMMENT;
        }
        if (hasBlockingLabel(pr.labels())) {
            return AttentionReason.BLOCKING;
        }
        if (pr.updatedAt() != null && Duration.between(pr.updatedAt(), now).compareTo(STALE_THRESHOLD) >= 0) {
            return AttentionReason.STALE;
        }
        // MINE is the catch-all: every authored PR ends up promoted, even
        // when nothing more specific is wrong. Keeps "open PRs I haven't
        // shipped yet" one click away from the attention column.
        if (mine && pr.handledAction() == null) {
            return AttentionReason.MINE;
        }
        return null;
    }

    /**
     * GitHub's mergeable signals come in pairs: {@code mergeable} (Boolean —
     * null means "not yet computed"), and {@code mergeableState} (string —
     * "dirty" specifically means a merge conflict). We require both to be
     * present to avoid false positives when the value is still transient.
     */
    static boolean hasMergeConflict(StoredPrDetail detail)
    {
        if (detail == null || detail.raw() == null) {
            return false;
        }
        Boolean mergeable = detail.raw().mergeable();
        String state = detail.raw().mergeableState();
        return Boolean.FALSE.equals(mergeable) && "dirty".equalsIgnoreCase(state);
    }

    /**
     * True if any commented or reviewed event was authored by someone other
     * than {@code currentLogin} after {@code viewedAt} (or any such event when
     * the PR has never been viewed). Mirrors {@link #hasUnseenMention}'s
     * shape but matches all comment/review activity, not just @-mentions.
     */
    static boolean hasUnseenActivity(List<PrTimelineEvent> timeline, String currentLogin, Instant viewedAt)
    {
        if (timeline == null) {
            return false;
        }
        for (PrTimelineEvent event : timeline) {
            if (event == null) {
                continue;
            }
            String type = event.event();
            if (!"commented".equals(type) && !"reviewed".equals(type)) {
                continue;
            }
            if (currentLogin != null && event.actor() != null
                    && currentLogin.equalsIgnoreCase(event.actor())) {
                continue;
            }
            if (viewedAt != null && event.timestamp() != null
                    && !event.timestamp().isAfter(viewedAt)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * True if any timeline comment authored by someone other than
     * {@code currentLogin} mentions {@code @currentLogin} after the user last
     * viewed the PR. {@code viewedAt == null} means "never viewed" — every
     * mention counts. Self-mentions are ignored so an author writing
     * "@me will follow up" doesn't promote their own PR.
     */
    static boolean hasUnseenMention(List<PrTimelineEvent> timeline, String currentLogin, Instant viewedAt)
    {
        if (currentLogin == null || currentLogin.isBlank() || timeline == null) {
            return false;
        }
        Pattern mention = Pattern.compile(
                "(?i)(?<![A-Za-z0-9_-])@" + Pattern.quote(currentLogin) + "(?![A-Za-z0-9_-])");
        for (PrTimelineEvent event : timeline) {
            if (event == null || event.body() == null || event.body().isBlank()) {
                continue;
            }
            if (event.actor() != null && currentLogin.equalsIgnoreCase(event.actor())) {
                continue;
            }
            if (viewedAt != null && event.timestamp() != null && !event.timestamp().isAfter(viewedAt)) {
                continue;
            }
            if (mention.matcher(event.body()).find()) {
                return true;
            }
        }
        return false;
    }

    static boolean hasBlockingLabel(List<String> labels)
    {
        if (labels == null) {
            return false;
        }
        return labels.stream().anyMatch(label -> label != null && label.toLowerCase(Locale.ROOT).contains("block"));
    }

    /** Reduces the per-check-run states into one PASSING / FAILING / PENDING / NONE marker. */
    static PullRequestDetail.CiStatus aggregateCiStatus(StoredPrDetail detail)
    {
        if (detail == null || detail.checkRuns() == null) {
            return PullRequestDetail.CiStatus.NONE;
        }
        return PullRequestDetailMapper.aggregateCiStatus(detail.checkRuns());
    }

    /** Approximation: count "commented" events in the stored timeline window. */
    public static int countComments(StoredPrDetail detail)
    {
        if (detail == null || detail.timeline() == null) {
            return 0;
        }
        return (int) detail.timeline().stream()
                .filter(event -> "commented".equals(event.event()))
                .count();
    }
}
