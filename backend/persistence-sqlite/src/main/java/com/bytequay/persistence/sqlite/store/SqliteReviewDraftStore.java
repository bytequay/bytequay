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

import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.review.ReviewDraftStore;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.store.HostingIdentityStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.persistence.sqlite.entity.PrReviewDraftEntity;
import com.bytequay.persistence.sqlite.repository.PrReviewDraftJpaRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.bytequay.persistence.sqlite.store.SqliteStoreExceptions.mapReviewConstraint;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteReviewDraftStore
        implements ReviewDraftStore
{
    private static final HostingServer LEGACY_GITHUB = new HostingServer(GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"));

    private final PrReviewDraftJpaRepository repository;
    private final AgentReviewStore reviews;
    private final HostingIdentityStore identities;
    private final WorkspaceStore workspaces;

    public SqliteReviewDraftStore(PrReviewDraftJpaRepository repository, AgentReviewStore reviews, HostingIdentityStore identities, WorkspaceStore workspaces)
    {
        this.repository = requireNonNull(repository, "repository is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.identities = requireNonNull(identities, "identities is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewDraft> find(String id)
    {
        return repository.findById(id).map(PrReviewDraftEntity::toDomain);
    }

    @Override
    @Transactional
    public Optional<ReviewDraft> find(long repositoryId, int number)
    {
        checkArgument(repositoryId > 0, "repositoryId must be positive");
        checkArgument(number > 0, "number must be positive");
        String id = repositoryId + "#" + number;
        return find(id).or(() -> identities.findRepository(repositoryId)
                .filter(hosted -> identities.findInstance(hosted.instanceId())
                        .filter(instance -> instance.provider() == LEGACY_GITHUB.provider()
                                && instance.webBaseUrl().equals(LEGACY_GITHUB.webBaseUrl()) && instance.apiBaseUrl().equals(LEGACY_GITHUB.apiBaseUrl()))
                        .isPresent())
                .flatMap(hosted -> repository.findById(hosted.fullPath().toLowerCase(Locale.ROOT) + "#" + number).map(retained -> {
                    requireRetainedIdentity(hosted);
                    // Preserve receipts and optimistic versions; moving the key and removing the old row commit together.
                    ReviewDraft draft = retained.toDomain().withId(id);
                    try {
                        repository.delete(retained);
                        return repository.saveAndFlush(PrReviewDraftEntity.fromDomain(draft)).toDomain();
                    }
                    catch (OptimisticLockingFailureException e) {
                        throw new ReviewConflictException("Review draft version changed: " + retained.getId(), e);
                    }
                    catch (DataAccessException e) {
                        throw mapReviewConstraint("Cannot adopt review draft " + retained.getId(), e);
                    }
                })));
    }

    private void requireRetainedIdentity(HostingRepository hosted)
    {
        // Retained global drafts have only paths; without workspace IDs they keep that original identity contract.
        boolean conflict = identities.findRepositories(LEGACY_GITHUB, hosted.fullPath()).size() != 1
                || workspaces.findAllRetained().stream()
                        .filter(workspace -> workspace.hostingServer().map(LEGACY_GITHUB::equals).orElse(true))
                        .anyMatch(workspace -> (workspace.selectedRepository().fullName().equalsIgnoreCase(hosted.fullPath())
                                && !Long.toString(workspace.selectedRepositoryId()).equals(hosted.remoteId()))
                                || (workspace.monitoredRepository().fullName().equalsIgnoreCase(hosted.fullPath())
                                && !Long.toString(workspace.monitoredRepositoryId()).equals(hosted.remoteId())));
        if (conflict) {
            throw new ReviewConflictException("A saved review has a conflicting repository identity. Its text is preserved; resolve the repository mismatch before continuing.");
        }
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
