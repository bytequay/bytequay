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

import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.PullRequestListPage;
import com.bytequay.core.store.HostingPullRequestStore;
import com.bytequay.persistence.sqlite.entity.HostingAccountEntity;
import com.bytequay.persistence.sqlite.entity.HostingPullRequestSnapshotEntity;
import com.bytequay.persistence.sqlite.entity.HostingRepositoryEntity;
import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import com.bytequay.persistence.sqlite.repository.HostingAccountJpaRepository;
import com.bytequay.persistence.sqlite.repository.HostingPullRequestSnapshotJpaRepository;
import com.bytequay.persistence.sqlite.repository.HostingRepositoryJpaRepository;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.google.common.collect.ImmutableSet;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
@Transactional(readOnly = true)
public class SqliteHostingPullRequestStore
        implements HostingPullRequestStore
{
    private final HostingAccountJpaRepository accounts;
    private final HostingRepositoryJpaRepository repositories;
    private final PullRequestJpaRepository requests;
    private final HostingPullRequestSnapshotJpaRepository snapshots;

    public SqliteHostingPullRequestStore(HostingAccountJpaRepository accounts, HostingRepositoryJpaRepository repositories, PullRequestJpaRepository requests,
            HostingPullRequestSnapshotJpaRepository snapshots)
    {
        this.accounts = requireNonNull(accounts, "accounts is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.requests = requireNonNull(requests, "requests is null");
        this.snapshots = requireNonNull(snapshots, "snapshots is null");
    }

    @Override
    public Optional<HostingPullRequestSnapshot> find(long accountId, long repositoryId, int number)
    {
        checkArgument(number > 0, "number must be positive");
        target(accountId, repositoryId);
        return snapshots.findByAccountIdAndRequestHostingRepositoryIdAndRequestNumber(accountId, repositoryId, number).flatMap(HostingPullRequestSnapshotEntity::toDomain);
    }

    @Override
    @Transactional
    public HostingPullRequestSnapshot save(long accountId, long repositoryId, String credentialScope, FetchedPullRequest pullRequest, PrRawDetail detail,
            Optional<String> etag, Instant validatedAt)
    {
        requireNonNull(pullRequest, "pullRequest is null");
        requireNonNull(detail, "detail is null");
        checkOptionalText(etag, "etag");
        requireNonNull(validatedAt, "validatedAt is null");
        checkText(credentialScope, "credentialScope");
        Target target = target(accountId, repositoryId);
        PullRequestEntity request = request(target, pullRequest, validatedAt);
        HostingPullRequestSnapshotEntity snapshot = snapshots.findByAccountIdAndRequestHostingRepositoryIdAndRequestNumber(accountId, repositoryId, pullRequest.number())
                .orElseGet(() -> new HostingPullRequestSnapshotEntity(request, target.account()));
        snapshot.update(new HostingPullRequestSnapshot(request.hostingIdentity(), accountId, credentialScope, pullRequest, detail, etag, validatedAt));
        return snapshots.save(snapshot).toDomain().orElseThrow();
    }

    @Override
    public PullRequestListPage findPage(long accountId, long repositoryId, String credentialScope, int page, int pageSize)
    {
        target(accountId, repositoryId);
        checkText(credentialScope, "credentialScope");
        checkArgument(page > 0, "page must be positive");
        checkArgument(pageSize > 0 && pageSize <= 100, "pageSize must be between 1 and 100");
        var rows = snapshots.findByAccountIdAndRequestHostingRepositoryIdAndCredentialScope(accountId, repositoryId, credentialScope,
                PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "updatedAt", "id")));
        return new PullRequestListPage(rows.stream().map(HostingPullRequestSnapshotEntity::summary).collect(toImmutableList()), rows.hasNext());
    }

    @Override
    @Transactional
    public void savePage(long accountId, long repositoryId, String credentialScope, List<FetchedPullRequest> pullRequests)
    {
        Target target = target(accountId, repositoryId);
        checkText(credentialScope, "credentialScope");
        for (FetchedPullRequest pullRequest : pullRequests) {
            PullRequestEntity request = request(target, pullRequest, pullRequest.updatedAt());
            HostingPullRequestSnapshotEntity snapshot = snapshots.findByAccountIdAndRequestHostingRepositoryIdAndRequestNumber(accountId, repositoryId, pullRequest.number())
                    .orElseGet(() -> new HostingPullRequestSnapshotEntity(request, target.account()));
            snapshot.updateSummary(credentialScope, pullRequest);
            snapshots.save(snapshot);
        }
    }

    @Override
    @Transactional
    public void evict(long accountId, long repositoryId, int number, String credentialScope)
    {
        checkText(credentialScope, "credentialScope");
        checkArgument(number > 0, "number must be positive");
        target(accountId, repositoryId);
        snapshots.findByAccountIdAndRequestHostingRepositoryIdAndRequestNumber(accountId, repositoryId, number)
                .filter(snapshot -> snapshot.credentialScope().equals(credentialScope)).ifPresent(snapshots::delete);
    }

    @Override
    @Transactional
    public void evictRepository(long repositoryId, int number)
    {
        checkArgument(repositoryId > 0, "repositoryId must be positive");
        checkArgument(number > 0, "number must be positive");
        snapshots.deleteByRequestHostingRepositoryIdAndRequestNumber(repositoryId, number);
    }

    private PullRequestEntity request(Target target, FetchedPullRequest pullRequest, Instant observedAt)
    {
        checkArgument(pullRequest.number() > 0, "number must be positive");
        checkArgument(pullRequest.githubId() > 0, "remote request ID must be positive");
        String remoteId = pullRequest.githubId().toString();
        PullRequestEntity request = requests.findByHostingRepositoryIdAndNumber(target.repository().getId(), pullRequest.number()).orElseGet(() -> {
            PullRequestEntity created = PullRequestEntity.from(pullRequest, observedAt);
            created.setHostingRepository(target.repository());
            created.setRemoteId(remoteId);
            created.setDashboardVisible(false);
            created.setWorkspaceVisible(false);
            return requests.save(created);
        });
        if (!request.getRemoteId().equals(remoteId)) {
            throw new HostingFailure(INVALID_RESPONSE, "The remote request identity changed for this repository and number");
        }
        if (request.getUpdatedAt() == null || !pullRequest.updatedAt().isBefore(request.getUpdatedAt())) {
            PullRequestEntity updated = PullRequestEntity.from(pullRequest, observedAt);
            updated.preserveEnrichmentFrom(request);
            boolean changed = !Objects.equals(request.getUpdatedAt(), updated.getUpdatedAt()) || !Objects.equals(request.getTitle(), updated.getTitle())
                    || !Objects.equals(request.getState(), updated.getState()) || request.isDraft() != updated.isDraft()
                    || !ImmutableSet.copyOf(request.getLabels()).equals(ImmutableSet.copyOf(updated.getLabels()));
            request.updateSummary(pullRequest, observedAt);
            request.preserveEnrichmentFrom(updated);
            if (changed) {
                request.setActivityAt(observedAt);
            }
            if ("merged".equalsIgnoreCase(request.getState())) {
                request.setMonitoredAt(null);
            }
        }
        return request;
    }

    private Target target(long accountId, long repositoryId)
    {
        checkArgument(accountId > 0, "accountId must be positive");
        checkArgument(repositoryId > 0, "repositoryId must be positive");
        HostingAccountEntity account = accounts.findById(accountId).orElseThrow(() -> new IllegalArgumentException("Hosting account does not exist"));
        HostingRepositoryEntity repository = repositories.findById(repositoryId).orElseThrow(() -> new IllegalArgumentException("Hosting repository does not exist"));
        checkArgument(account.toDomain().instanceId() == repository.toDomain().instanceId(), "Hosting account and repository belong to different instances");
        return new Target(account, repository);
    }

    private record Target(HostingAccountEntity account, HostingRepositoryEntity repository) {}
}
