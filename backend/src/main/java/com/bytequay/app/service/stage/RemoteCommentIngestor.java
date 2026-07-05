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

import com.bytequay.app.domain.PullRequestDetail;
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
 * Mirrors a PR's remote review comments into the unified
 * {@code review_comment} table as {@code REMOTE_REVIEWER} rows, so the
 * {@code code} operation can read local and remote comments through one
 * machinery. Driven from {@code TaskLifecycleDriver}'s reconcile sweep,
 * which already fetches the {@link PullRequestDetail}.
 *
 * <p>The remote source of truth is {@link PullRequestDetail#reviewThreads()}
 * (the github.com review threads), not the local {@code pr_review_draft}
 * comments. Each row is keyed by its github discussion link so re-ingestion
 * is idempotent.
 */
@Component
public class RemoteCommentIngestor
{
    private static final Logger log = LoggerFactory.getLogger(RemoteCommentIngestor.class);

    private final StageStore stageStore;

    public RemoteCommentIngestor(StageStore stageStore)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
    }

    /**
     * Ingest every not-yet-stored remote review comment on the PR. A no-op
     * when there's no detail or no threads. Comments without an anchored
     * file are skipped (they aren't line review comments).
     *
     * <p>No transaction wraps the whole loop — each {@code saveReviewComment}
     * call gets its own (it's {@code @Transactional} itself), and a failure
     * on one message is caught and skipped rather than left to abort the
     * batch: one bad row must never block every other comment on the same PR
     * (including a genuinely new one) from ever being seen, nor stop the
     * caller from reaching the round-reconcile step that follows this call.
     */
    public void ingest(String taskId, String repoFullName, int prNumber, PullRequestDetail detail)
    {
        if (detail == null || detail.reviewThreads() == null) {
            return;
        }
        for (ReviewThread thread : detail.reviewThreads()) {
            if (thread.filePath() == null) {
                continue;
            }
            boolean resolved = Boolean.TRUE.equals(thread.resolved());
            int line = thread.line() == null ? 0 : thread.line();
            List<ReviewMessage> messages = thread.messages();
            if (messages == null) {
                continue;
            }
            for (ReviewMessage message : messages) {
                String remoteLink = discussionLink(repoFullName, prNumber, message.githubId());
                if (stageStore.reviewCommentExistsByRemoteLink(remoteLink)) {
                    continue;
                }
                try {
                    stageStore.saveReviewComment(new ReviewComment(
                            null,
                            taskId,
                            thread.filePath(),
                            line,
                            message.body() == null ? "" : message.body(),
                            message.createdAt() == null ? Instant.now() : message.createdAt(),
                            ReviewCommentSource.REMOTE_REVIEWER,
                            remoteLink,
                            resolved,
                            message.githubId(),
                            null,
                            null,
                            null));
                }
                catch (RuntimeException e) {
                    log.warn("failed to ingest remote comment {} for task {}: {}", remoteLink, taskId, e.getMessage());
                }
            }
        }
    }

    private static String discussionLink(String repoFullName, int prNumber, long commentGithubId)
    {
        return "https://github.com/" + repoFullName + "/pull/" + prNumber
                + "#discussion_r" + commentGithubId;
    }
}
