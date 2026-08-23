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

import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.store.RepositoryIssueDetailStore;
import com.bytequay.persistence.sqlite.entity.RepositoryIssueDetailEntity;
import com.bytequay.persistence.sqlite.repository.RepositoryIssueDetailJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteRepositoryIssueDetailStore
        implements RepositoryIssueDetailStore
{
    private final RepositoryIssueDetailJpaRepository repository;

    public SqliteRepositoryIssueDetailStore(RepositoryIssueDetailJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RepositoryIssueDetail> find(long repositoryId, int issueNumber)
    {
        return repository.findById(snapshotId(repositoryId, issueNumber))
                .map(RepositoryIssueDetailEntity::toDomain);
    }

    @Override
    @Transactional
    public void save(RepositoryIssueDetail detail)
    {
        requireNonNull(detail, "detail is null");
        RepositoryIssueDetailEntity entity = new RepositoryIssueDetailEntity();
        entity.setId(snapshotId(detail.repositoryId(), detail.issueNumber()));
        entity.setRepositoryId(detail.repositoryId());
        entity.setIssueNumber(detail.issueNumber());
        entity.setTimeline(detail.timeline());
        entity.setCurrentType(detail.currentType());
        entity.setAvailableTypes(detail.availableTypes());
        entity.setRefreshedAt(detail.refreshedAt().orElse(null));
        repository.save(entity);
    }

    private static String snapshotId(long repositoryId, int issueNumber)
    {
        checkArgument(repositoryId > 0, "repositoryId must be positive");
        checkArgument(issueNumber > 0, "issueNumber must be positive");
        return repositoryId + ":" + issueNumber;
    }
}
