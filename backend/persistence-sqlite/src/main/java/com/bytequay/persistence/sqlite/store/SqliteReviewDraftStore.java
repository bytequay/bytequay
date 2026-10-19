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

import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.review.ReviewDraftStore;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.persistence.sqlite.entity.PrReviewDraftEntity;
import com.bytequay.persistence.sqlite.repository.PrReviewDraftJpaRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.bytequay.persistence.sqlite.store.SqliteStoreExceptions.mapReviewConstraint;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteReviewDraftStore
        implements ReviewDraftStore
{
    private final PrReviewDraftJpaRepository repository;
    private final AgentReviewStore reviews;

    public SqliteReviewDraftStore(PrReviewDraftJpaRepository repository, AgentReviewStore reviews)
    {
        this.repository = requireNonNull(repository, "repository is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewDraft> find(String repo, int number)
    {
        return repository.findById(repo.toLowerCase(Locale.ROOT) + "#" + number).map(PrReviewDraftEntity::toDomain);
    }

    @Override
    @Transactional
    public ReviewDraft savePublished(ReviewDraft draft, List<ReviewFinding> findings)
    {
        ReviewDraft saved = save(draft);
        findings.forEach(reviews::updateFinding);
        return saved;
    }

    @Override
    @Transactional
    public ReviewDraft save(ReviewDraft draft)
    {
        requireNonNull(draft, "draft is null");
        Optional<PrReviewDraftEntity> stored = repository.findById(draft.id());
        try {
            if (stored.isEmpty()) {
                if (draft.version() != 0) {
                    throw new ReviewConflictException("Review draft version changed: " + draft.id());
                }
                return repository.saveAndFlush(PrReviewDraftEntity.fromDomain(draft)).toDomain();
            }
            PrReviewDraftEntity entity = stored.orElseThrow();
            if (entity.getVersion() != draft.version()) {
                throw new ReviewConflictException("Review draft version changed: " + draft.id());
            }
            entity.updateFrom(draft);
            return repository.saveAndFlush(entity).toDomain();
        }
        catch (OptimisticLockingFailureException e) {
            throw new ReviewConflictException("Review draft version changed: " + draft.id(), e);
        }
        catch (DataAccessException e) {
            throw mapReviewConstraint("Cannot save review draft " + draft.id(), e);
        }
    }
}
