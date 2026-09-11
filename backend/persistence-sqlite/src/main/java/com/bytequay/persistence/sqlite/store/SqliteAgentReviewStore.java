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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.persistence.sqlite.entity.AgentReviewEntity;
import com.bytequay.persistence.sqlite.entity.AgentReviewFindingEntity;
import com.bytequay.persistence.sqlite.entity.AgentReviewMessageEntity;
import com.bytequay.persistence.sqlite.entity.AgentReviewRunEntity;
import com.bytequay.persistence.sqlite.repository.AgentReviewFindingJpaRepository;
import com.bytequay.persistence.sqlite.repository.AgentReviewJpaRepository;
import com.bytequay.persistence.sqlite.repository.AgentReviewMessageJpaRepository;
import com.bytequay.persistence.sqlite.repository.AgentReviewRunJpaRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import static com.bytequay.persistence.sqlite.store.StoreWrites.REVIEW;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteAgentReviewStore
        implements AgentReviewStore
{
    private final AgentReviewJpaRepository reviews;
    private final AgentReviewRunJpaRepository runs;
    private final AgentReviewFindingJpaRepository findings;
    private final AgentReviewMessageJpaRepository messages;

    public SqliteAgentReviewStore(
            AgentReviewJpaRepository reviews,
            AgentReviewRunJpaRepository runs,
            AgentReviewFindingJpaRepository findings,
            AgentReviewMessageJpaRepository messages)
    {
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.runs = requireNonNull(runs, "runs is null");
        this.findings = requireNonNull(findings, "findings is null");
        this.messages = requireNonNull(messages, "messages is null");
    }

    @Override
    @Transactional
    public AgentReview insertReview(AgentReview review)
    {
        return insert(reviews, review.id(), "Review", () -> AgentReviewEntity.fromDomain(review), AgentReviewEntity::toDomain);
    }

    @Override
    @Transactional
    public AgentReview updateReview(AgentReview review)
    {
        return update(reviews, review.id(), "Review", review.version(), AgentReviewEntity::getVersion, entity -> entity.updateFrom(review), AgentReviewEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentReview> findReview(String reviewId)
    {
        return reviews.findById(reviewId).map(AgentReviewEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentReview> findOpenReview(String workspaceId, String repo, int number)
    {
        return reviews.findFirstByWorkspaceIdAndRepoAndNumberAndStateOrderByCreatedAtDesc(workspaceId, repo.toLowerCase(Locale.ROOT), number, AgentReview.State.OPEN)
                .map(AgentReviewEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentReview> findReviewByTask(String taskId)
    {
        return reviews.findByTaskId(taskId).map(AgentReviewEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentReview> listOpenReviews()
    {
        return reviews.findByStateOrderByCreatedAtAsc(AgentReview.State.OPEN).stream().map(AgentReviewEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentReview> listReviews(String workspaceId)
    {
        return reviews.findByWorkspaceIdOrderByUpdatedAtDescIdAsc(workspaceId).stream().map(AgentReviewEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public ReviewRun insertRun(ReviewRun run)
    {
        return insert(runs, run.id(), "Review run", () -> AgentReviewRunEntity.fromDomain(run), AgentReviewRunEntity::toDomain);
    }

    @Override
    @Transactional
    public ReviewRun updateRun(ReviewRun run)
    {
        return update(runs, run.id(), "Review run", run.version(), AgentReviewRunEntity::getVersion, entity -> entity.updateFrom(run), AgentReviewRunEntity::toDomain);
    }

    @Override
    @Transactional
    public void acceptRun(AgentReview review, ReviewRun run)
    {
        updateRun(run);
        updateReview(review);
    }

    @Override
    @Transactional
    public ReviewFinding saveFinding(ReviewFinding finding, boolean insert, ReviewRun run)
    {
        ReviewFinding saved = insert ? insertFinding(finding) : updateFinding(finding);
        updateRun(run);
        return saved;
    }

    @Override
    @Transactional
    public ReviewMessage insertMessage(ReviewMessage message, ReviewRun run)
    {
        ReviewMessage saved = insertMessage(message);
        updateRun(run);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewRun> findRun(String runId)
    {
        return runs.findById(runId).map(AgentReviewRunEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewRun> listRuns(String reviewId)
    {
        return runs.findByReviewIdOrderByOrdinalAsc(reviewId).stream().map(AgentReviewRunEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public ReviewFinding insertFinding(ReviewFinding finding)
    {
        return insert(findings, finding.id(), "Review finding", () -> AgentReviewFindingEntity.fromDomain(finding), AgentReviewFindingEntity::toDomain);
    }

    @Override
    @Transactional
    public ReviewFinding updateFinding(ReviewFinding finding)
    {
        return update(
                findings,
                finding.id(),
                "Review finding",
                finding.version(),
                AgentReviewFindingEntity::getVersion,
                entity -> entity.updateFrom(finding),
                AgentReviewFindingEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewFinding> findFinding(String findingId)
    {
        return findings.findById(findingId).map(AgentReviewFindingEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewFinding> listFindings(String reviewId)
    {
        return findings.findByReviewIdOrderByOrdinalAsc(reviewId).stream().map(AgentReviewFindingEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public ReviewMessage insertMessage(ReviewMessage message)
    {
        return insert(messages, message.id(), "Review message", () -> AgentReviewMessageEntity.fromDomain(message), AgentReviewMessageEntity::toDomain);
    }

    @Override
    @Transactional
    public ReviewMessage updateMessage(ReviewMessage message)
    {
        return update(
                messages,
                message.id(),
                "Review message",
                message.version(),
                AgentReviewMessageEntity::getVersion,
                entity -> entity.updateFrom(message),
                AgentReviewMessageEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewMessage> listMessages(String reviewId)
    {
        return messages.findByReviewIdOrderByCreatedAtAsc(reviewId).stream().map(AgentReviewMessageEntity::toDomain).collect(toImmutableList());
    }

    private static <E, D> D insert(JpaRepository<E, String> repository, String id, String label, Supplier<E> entity, Function<E, D> toDomain)
    {
        return toDomain.apply(StoreWrites.insert(repository, id, label, entity, REVIEW));
    }

    private static <E, D> D update(
            JpaRepository<E, String> repository,
            String id,
            String label,
            long expectedVersion,
            ToLongFunction<E> storedVersion,
            Consumer<E> apply,
            Function<E, D> toDomain)
    {
        Supplier<RuntimeException> absent = () -> new ReviewNotFoundException(label + " is absent: " + id);
        return toDomain.apply(StoreWrites.update(repository, id, label, expectedVersion, storedVersion, apply, absent, REVIEW));
    }
}
