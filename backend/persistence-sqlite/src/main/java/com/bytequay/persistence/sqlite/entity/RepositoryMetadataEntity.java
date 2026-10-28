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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.domain.RepositoryMetadataSnapshot;
import com.bytequay.core.domain.RepositoryPickerUser;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Entity
@Table(name = "hosting_repository_metadata")
@Getter
@Setter
public class RepositoryMetadataEntity
{
    @Id
    @Column(name = "repository_id")
    private Long repositoryId;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String repo;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "stargazers_count", nullable = false, columnDefinition = "INTEGER DEFAULT 0")
    private int stargazersCount;

    @Column(nullable = false, columnDefinition = "TEXT DEFAULT '{}'")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Long> languages = ImmutableMap.of();

    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private List<RepositoryContributor> contributors = ImmutableList.of();

    @Column(name = "picker_users", nullable = false, columnDefinition = "TEXT DEFAULT '[]'")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<RepositoryPickerUser> pickerUsers = ImmutableList.of();

    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private List<RepositoryLabel> labels = ImmutableList.of();

    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> topics = ImmutableList.of();

    @Column(name = "contributors_synced_at")
    private Instant contributorsSyncedAt;

    @Column(name = "picker_users_synced_at")
    private Instant pickerUsersSyncedAt;

    @Column(name = "overview_synced_at")
    private Instant overviewSyncedAt;

    @Column(name = "labels_topics_synced_at")
    private Instant labelsTopicsSyncedAt;

    public RepositoryMetadataSnapshot toDomain()
    {
        return new RepositoryMetadataSnapshot(
                getRepositoryId(), RepoRef.of(getOwner(), getRepo()), getDescription(), getStargazersCount(), getLanguages(), getContributors(), getPickerUsers(), getLabels(),
                getTopics(), Optional.ofNullable(getOverviewSyncedAt()), Optional.ofNullable(getContributorsSyncedAt()), Optional.ofNullable(getPickerUsersSyncedAt()),
                Optional.ofNullable(getLabelsTopicsSyncedAt()));
    }
}
