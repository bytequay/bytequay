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

import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.persistence.sqlite.entity.RepositoryIssueEntity;
import com.bytequay.persistence.sqlite.entity.RepositoryIssueSyncEntity;
import com.bytequay.persistence.sqlite.repository.RepositoryIssueRowJpaRepository;
import com.bytequay.persistence.sqlite.repository.RepositoryIssueSyncJpaRepository;
import com.google.common.collect.ImmutableList;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

@Repository
public class SqliteRepositoryIssueStore
        implements RepositoryIssueStore
{
    private final RepositoryIssueSyncJpaRepository repository;
    private final RepositoryIssueRowJpaRepository issueRows;

    public SqliteRepositoryIssueStore(
            RepositoryIssueSyncJpaRepository repository,
            RepositoryIssueRowJpaRepository issueRows)
    {
        this.repository = requireNonNull(repository, "repository is null");
        this.issueRows = requireNonNull(issueRows, "issueRows is null");
    }

    @Override
    @Transactional
    public RepositoryIssuePage findPage(long repositoryId, RepoRef repositoryRef, int page, int pageSize)
    {
        requireNonNull(repositoryRef, "repositoryRef is null");
        RepositoryIssueSyncEntity sync = repository.findById(repositoryId).orElse(null);
        var rows = issueRows.findByRepositoryId(
                repositoryId,
                PageRequest.of(page, pageSize, Sort.by(
                        Sort.Order.desc("updatedAt"),
                        Sort.Order.desc("number"))));
        return new RepositoryIssuePage(
                repositoryId,
                repositoryRef,
                rows.stream().map(RepositoryIssueEntity::toDomain).collect(ImmutableList.toImmutableList()),
                page,
                pageSize,
                rows.getTotalElements(),
                rows.hasNext(),
                java.util.Optional.ofNullable(sync).map(RepositoryIssueSyncEntity::getSyncedAt));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RepositoryIssue> find(long repositoryId, int issueNumber)
    {
        return issueRows.findByRepositoryIdAndNumber(repositoryId, issueNumber)
                .map(RepositoryIssueEntity::toDomain);
    }

    @Override
    @Transactional
    public void saveSyncPage(long repositoryId, List<RepositoryIssue> issues, String syncMarker)
    {
        List<RepositoryIssue> fresh = ImmutableList.copyOf(requireNonNull(issues, "issues is null"));
        requireNonNull(syncMarker, "syncMarker is null");
        Map<Long, RepositoryIssueEntity> stored = issueRows.findAllById(
                        fresh.stream().map(RepositoryIssue::id).toList()).stream()
                .collect(toMap(RepositoryIssueEntity::getId, identity()));
        issueRows.saveAll(fresh.stream()
                .map(issue -> {
                    RepositoryIssueEntity entity = stored.get(issue.id());
                    if (entity == null) {
                        return new RepositoryIssueEntity(repositoryId, issue, syncMarker);
                    }
                    entity.update(repositoryId, issue, syncMarker);
                    return entity;
                })
                .collect(ImmutableList.toImmutableList()));
    }

    @Override
    @Transactional
    public void completeSync(
            long repositoryId,
            RepoRef repositoryRef,
            String syncMarker,
            Instant syncedAt)
    {
        requireNonNull(repositoryRef, "repositoryRef is null");
        requireNonNull(syncMarker, "syncMarker is null");
        issueRows.deleteStale(repositoryId, syncMarker);
        RepositoryIssueSyncEntity entity = repository.findById(repositoryId)
                .orElseGet(RepositoryIssueSyncEntity::new);
        entity.setRepositoryId(repositoryId);
        entity.setOwner(repositoryRef.owner());
        entity.setRepo(repositoryRef.repo());
        entity.setSyncedAt(requireNonNull(syncedAt, "syncedAt is null"));
        repository.save(entity);
    }

    @Override
    @Transactional
    public void updateMetadata(
            long repositoryId,
            int issueNumber,
            List<String> assignees,
            List<String> labels,
            Instant updatedAt)
    {
        RepositoryIssueEntity issue = issueRows.findByRepositoryIdAndNumber(repositoryId, issueNumber)
                .orElseThrow(() -> new IllegalArgumentException("issue is not stored: " + issueNumber));
        issue.updateMetadata(
                requireNonNull(assignees, "assignees is null"),
                requireNonNull(labels, "labels is null"),
                requireNonNull(updatedAt, "updatedAt is null"));
    }

    @Override
    @Transactional
    public void delete(long repositoryId, int issueNumber)
    {
        issueRows.findByRepositoryIdAndNumber(repositoryId, issueNumber)
                .ifPresent(issueRows::delete);
    }
}
