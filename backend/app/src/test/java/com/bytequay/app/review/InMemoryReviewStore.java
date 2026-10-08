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
package com.bytequay.app.review;

import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewRun;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

/** Durable review storage with the optimistic-version contract the SQLite store implements. */
class InMemoryReviewStore
        implements AgentReviewStore
{
    private int runUpdates;
    private final Map<String, AgentReview> reviews = new LinkedHashMap<>();
    private final Map<String, ReviewRun> runs = new LinkedHashMap<>();
    private final Map<String, ReviewFinding> findings = new LinkedHashMap<>();
    private final Map<String, ReviewMessage> messages = new LinkedHashMap<>();

    @Override
    public AgentReview insertReview(AgentReview review)
    {
        reviews.put(review.id(), review);
        return review;
    }

    @Override
    public AgentReview updateReview(AgentReview review)
    {
        AgentReview stored = reviews.get(review.id());
        checkUpdate(stored == null ? OptionalLong.empty() : OptionalLong.of(stored.version()), review.version(), "Review", review.id());
        AgentReview next = review.withVersion(review.version() + 1);
        reviews.put(next.id(), next);
        return next;
    }

    @Override
    public Optional<AgentReview> findReview(String reviewId)
    {
        return Optional.ofNullable(reviews.get(reviewId));
    }

    @Override
    public Optional<AgentReview> findOpenReview(String workspaceId, String repo, int number)
    {
        return reviews.values().stream()
                .filter(review -> review.state() == AgentReview.State.OPEN)
                .filter(review -> review.workspaceId().equals(workspaceId) && review.repo().equals(repo) && review.number().stream().anyMatch(value -> value == number))
                .findFirst();
    }

    @Override
    public Optional<AgentReview> findReviewByTask(String taskId)
    {
        return reviews.values().stream().filter(review -> review.taskId().equals(taskId)).findFirst();
    }

    @Override
    public List<AgentReview> listOpenReviews()
    {
        return reviews.values().stream().filter(review -> review.state() == AgentReview.State.OPEN).collect(toImmutableList());
    }

    @Override
    public List<AgentReview> listReviews(String workspaceId)
    {
        return reviews.values().stream()
                .filter(review -> review.workspaceId().equals(workspaceId))
                .sorted(Comparator.comparing(AgentReview::updatedAt).reversed().thenComparing(AgentReview::id))
                .collect(toImmutableList());
    }

    @Override
    public ReviewRun insertRun(ReviewRun run)
    {
        runs.put(run.id(), run);
        return run;
    }

    @Override
    public ReviewRun updateRun(ReviewRun run)
    {
        ReviewRun stored = runs.get(run.id());
        checkUpdate(stored == null ? OptionalLong.empty() : OptionalLong.of(stored.version()), run.version(), "Review run", run.id());
        runUpdates++;
        ReviewRun next = run.withVersion(run.version() + 1);
        runs.put(next.id(), next);
        return next;
    }

    @Override
    public void acceptRun(AgentReview review, ReviewRun run)
    {
        updateRun(run);
        updateReview(review);
    }

    @Override
    public ReviewFinding saveFinding(ReviewFinding finding, boolean insert, ReviewRun run)
    {
        checkArgument(insert != findings.containsKey(finding.id()), "insert disagrees with the stored findings");
        ReviewFinding saved = insert ? insertFinding(finding) : updateFinding(finding);
        updateRun(run);
        return saved;
    }

    @Override
    public ReviewMessage insertMessage(ReviewMessage message, ReviewRun run)
    {
        ReviewMessage saved = insertMessage(message);
        updateRun(run);
        return saved;
    }

    @Override
    public Optional<ReviewRun> findRun(String runId)
    {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public List<ReviewRun> listRuns(String reviewId)
    {
        return runs.values().stream()
                .filter(run -> run.reviewId().equals(reviewId))
                .sorted(Comparator.comparingInt(ReviewRun::ordinal))
                .collect(toImmutableList());
    }

    @Override
    public List<ReviewRun> listRecentRuns(String workspaceId, String repo, int limit)
    {
        checkArgument(limit >= 1 && limit <= 10, "Recent run limit must be between 1 and 10");
        return runs.values().stream()
                .filter(run -> findReview(run.reviewId())
                        .filter(review -> review.workspaceId().equals(workspaceId) && review.repo().equalsIgnoreCase(repo)).isPresent())
                .sorted(Comparator.comparing(ReviewRun::updatedAt).reversed().thenComparing(ReviewRun::id))
                .limit(limit)
                .collect(toImmutableList());
    }

    @Override
    public ReviewFinding insertFinding(ReviewFinding finding)
    {
        findings.put(finding.id(), finding);
        return finding;
    }

    @Override
    public ReviewFinding updateFinding(ReviewFinding finding)
    {
        ReviewFinding stored = findings.get(finding.id());
        checkUpdate(stored == null ? OptionalLong.empty() : OptionalLong.of(stored.version()), finding.version(), "Review finding", finding.id());
        ReviewFinding next = finding.withVersion(finding.version() + 1);
        findings.put(next.id(), next);
        return next;
    }

    @Override
    public Optional<ReviewFinding> findFinding(String findingId)
    {
        return Optional.ofNullable(findings.get(findingId));
    }

    @Override
    public List<ReviewFinding> listFindings(String reviewId)
    {
        return findings.values().stream()
                .filter(finding -> finding.reviewId().equals(reviewId))
                .sorted(Comparator.comparingInt(ReviewFinding::ordinal))
                .collect(toImmutableList());
    }

    @Override
    public ReviewMessage insertMessage(ReviewMessage message)
    {
        messages.put(message.id(), message);
        return message;
    }

    @Override
    public ReviewMessage updateMessage(ReviewMessage message)
    {
        ReviewMessage stored = messages.get(message.id());
        checkUpdate(stored == null ? OptionalLong.empty() : OptionalLong.of(stored.version()), message.version(), "Review message", message.id());
        ReviewMessage next = new ReviewMessage(message.id(), message.reviewId(), message.findingId(), message.cardId(), message.author(),
                message.body(), message.targetRevision(), message.runId(), message.turnId(), message.queuedInputId(), message.version() + 1, message.createdAt());
        messages.put(next.id(), next);
        return next;
    }

    @Override
    public void deleteMessage(String messageId)
    {
        messages.remove(messageId);
    }

    @Override
    public Optional<ReviewMessage> findMessage(String messageId)
    {
        return Optional.ofNullable(messages.get(messageId));
    }

    @Override
    public List<ReviewMessage> listEarlierMessages(String reviewId, String runId, Optional<String> findingId, Optional<String> cardId,
            int targetRevision, Instant createdAt, String messageId, int limit)
    {
        return messages.values().stream()
                .filter(message -> message.reviewId().equals(reviewId) && message.runId().equals(runId))
                .filter(message -> message.findingId().equals(findingId) && message.cardId().equals(cardId))
                .filter(message -> message.targetRevision() == targetRevision)
                .filter(message -> message.createdAt().isBefore(createdAt) || (message.createdAt().equals(createdAt) && message.id().compareTo(messageId) < 0))
                .sorted(Comparator.comparing(ReviewMessage::createdAt).thenComparing(ReviewMessage::id).reversed())
                .limit(limit)
                .collect(toImmutableList());
    }

    @Override
    public List<ReviewMessage> listMessages(String reviewId)
    {
        return messages.values().stream()
                .filter(message -> message.reviewId().equals(reviewId))
                .sorted(Comparator.comparing(ReviewMessage::createdAt))
                .collect(toImmutableList());
    }

    int runUpdates()
    {
        return runUpdates;
    }

    private static void checkUpdate(OptionalLong storedVersion, long expectedVersion, String label, String id)
    {
        if (storedVersion.isEmpty()) {
            throw new ReviewNotFoundException(label + " is absent: " + id);
        }
        if (storedVersion.orElseThrow() != expectedVersion) {
            throw new ReviewConflictException(label + " version changed: " + id);
        }
    }
}
