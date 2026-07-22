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
package com.bytequay.app.service.stage;

import com.bytequay.app.domain.DiffSide;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestDetail.ActivityItem;
import com.bytequay.app.domain.PullRequestDetail.ReviewMessage;
import com.bytequay.app.domain.PullRequestDetail.ReviewThread;
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.repository.StageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Mirrors a PR's remote comments into the unified {@code review_comment}
 * table as {@code REMOTE_REVIEWER} rows, so the {@code code} operation can
 * read local and remote comments through one machinery. Driven from
 * {@code TaskLifecycleDriver}'s reconcile sweep, which already fetches the
 * {@link PullRequestDetail}.
 *
 * <p>Two remote sources feed it: {@link PullRequestDetail#reviewThreads()}
 * (diff-anchored review comments) and {@link PullRequestDetail#recentActivity()}
 * (plain top-level PR/Conversation-tab comments — GitHub "issue comments",
 * carried as {@code commented} activity events since they have no thread of
 * their own). Neither is the local {@code pr_review_draft} comments. Each row
 * is keyed by its github link so re-ingestion is idempotent.
 */
@Component
public class RemoteCommentIngestor
{
    private static final Logger log = LoggerFactory.getLogger(RemoteCommentIngestor.class);
    private static final String COMMENTED_EVENT = "commented";

    private final StageStore stageStore;

    public RemoteCommentIngestor(StageStore stageStore)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
    }

    /**
     * Ingest every not-yet-stored remote comment on the PR — both diff review
     * comments and plain top-level ones. A no-op when there's no detail.
     *
     * <p>No transaction wraps either loop — each {@code saveReviewComment}
     * call gets its own (it's {@code @Transactional} itself), and a failure
     * on one message is caught and skipped rather than left to abort the
     * batch: one bad row must never block every other comment on the same PR
     * (including a genuinely new one) from ever being seen, nor stop the
     * caller from reaching the round-reconcile step that follows this call.
     */
    public void ingest(String taskId, String repoFullName, int prNumber, PullRequestDetail detail)
    {
        ingest(taskId, repoFullName, prNumber, detail, null);
    }

    /**
     * @param currentLogin authenticated GitHub login for this task-origin PR;
     *                     null means unknown and therefore fails open
     */
    public void ingest(
            String taskId,
            String repoFullName,
            int prNumber,
            PullRequestDetail detail,
            String currentLogin)
    {
        if (detail == null) {
            return;
        }
        ingestReviewThreads(taskId, repoFullName, prNumber, detail.reviewThreads(), currentLogin);
        ingestIssueComments(taskId, repoFullName, prNumber, detail.recentActivity(), currentLogin);
    }

    /** Diff-anchored comments — a thread with no {@code filePath} is skipped
     *  (it isn't a line review comment; general comments arrive separately
     *  via {@link #ingestIssueComments}). */
    private void ingestReviewThreads(
            String taskId,
            String repoFullName,
            int prNumber,
            List<ReviewThread> threads,
            String currentLogin)
    {
        if (threads == null) {
            return;
        }
        for (ReviewThread thread : threads) {
            if (thread.filePath() == null) {
                continue;
            }
            boolean resolved = Boolean.TRUE.equals(thread.resolved());
            int line = thread.line() == null ? 0 : thread.line();
            List<ReviewMessage> messages = thread.messages();
            if (messages == null) {
                continue;
            }
            String side = DiffSide.normalize(thread.side());
            for (ReviewMessage message : messages) {
                if (isCurrentUser(message.author(), currentLogin)) {
                    continue;
                }
                saveOrRefresh(taskId, discussionLink(repoFullName, prNumber, message.githubId()),
                        new ReviewComment(
                                null,
                                taskId,
                                thread.filePath(),
                                line,
                                message.body() == null ? "" : message.body(),
                                message.createdAt() == null ? Instant.now() : message.createdAt(),
                                ReviewCommentSource.REMOTE_REVIEWER,
                                discussionLink(repoFullName, prNumber, message.githubId()),
                                resolved,
                                thread.rootGithubId(),
                                null,
                                null,
                                null,
                                side,
                                thread.startLine(),
                                thread.startLine() == null ? null : DiffSide.normalizeOptional(thread.startSide(), side)));
            }
        }
    }

    /** Plain top-level PR comments — GitHub has no "resolved" concept for
     *  these (there's no thread), so they always ingest as unresolved; the
     *  local {@code resolve_review_comment} tool is what clears them. */
    private void ingestIssueComments(
            String taskId,
            String repoFullName,
            int prNumber,
            List<ActivityItem> activity,
            String currentLogin)
    {
        if (activity == null) {
            return;
        }
        for (ActivityItem item : activity) {
            if (!COMMENTED_EVENT.equals(item.eventType()) || item.githubId() == null
                    || isCurrentUser(item.actor(), currentLogin)) {
                continue;
            }
            saveOrRefresh(taskId, issueCommentLink(repoFullName, prNumber, item.githubId()),
                    new ReviewComment(
                            null,
                            taskId,
                            /* file */ null,
                            /* line */ 0,
                            item.body() == null ? "" : item.body(),
                            item.timestamp() == null ? Instant.now() : item.timestamp(),
                            ReviewCommentSource.REMOTE_REVIEWER,
                            issueCommentLink(repoFullName, prNumber, item.githubId()),
                            /* resolved */ false,
                            item.githubId(),
                            null,
                            null,
                            null,
                            DiffSide.RIGHT,
                            /* startLine */ null,
                            /* startSide */ null));
        }
    }

    private void saveOrRefresh(String taskId, String remoteLink, ReviewComment incoming)
    {
        ReviewComment existing = stageStore.findReviewCommentByRemoteLink(remoteLink).orElse(null);
        if (existing != null) {
            boolean refreshResolved = existing.roundId() == null
                    || incoming.resolved()
                    || stageStore.isRemoteThreadResolutionPosted(existing.id());
            boolean resolved = refreshResolved ? incoming.resolved() : existing.resolved();
            if (resolved != existing.resolved()
                    || !incoming.remoteCommentId().equals(existing.remoteCommentId())) {
                try {
                    stageStore.saveReviewComment(existing.withRemoteState(
                            resolved, incoming.remoteCommentId()));
                }
                catch (RuntimeException e) {
                    log.warn("failed to refresh remote comment {} for task {}: {}",
                            remoteLink, taskId, e.getMessage());
                }
            }
            return;
        }
        if (stageStore.reviewCommentExistsByRemoteLink(remoteLink)) {
            return;
        }
        try {
            stageStore.saveReviewComment(incoming);
        }
        catch (RuntimeException e) {
            log.warn("failed to ingest remote comment {} for task {}: {}", remoteLink, taskId, e.getMessage());
        }
    }

    private static boolean isCurrentUser(String author, String currentLogin)
    {
        if (author == null || currentLogin == null) {
            return false;
        }
        String login = currentLogin.startsWith("@") ? currentLogin.substring(1) : currentLogin;
        return author.equalsIgnoreCase(login);
    }

    private static String discussionLink(String repoFullName, int prNumber, long commentGithubId)
    {
        return "https://github.com/" + repoFullName + "/pull/" + prNumber
                + "#discussion_r" + commentGithubId;
    }

    private static String issueCommentLink(String repoFullName, int prNumber, long commentGithubId)
    {
        return "https://github.com/" + repoFullName + "/pull/" + prNumber
                + "#issuecomment-" + commentGithubId;
    }
}
