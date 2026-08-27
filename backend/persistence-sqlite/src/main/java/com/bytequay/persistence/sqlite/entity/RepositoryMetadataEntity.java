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
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import com.bytequay.persistence.sqlite.converter.RepositoryContributorListConverter;
import com.bytequay.persistence.sqlite.converter.RepositoryLabelListConverter;
import com.bytequay.persistence.sqlite.converter.RepositoryPickerUserListConverter;
import com.bytequay.persistence.sqlite.converter.StringListConverter;
import com.bytequay.persistence.sqlite.converter.StringLongMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Entity
@Table(name = "repository_metadata")
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
    @Convert(converter = StringLongMapConverter.class)
    private Map<String, Long> languages;

    @Column(nullable = false)
    @Convert(converter = RepositoryContributorListConverter.class)
    private List<RepositoryContributor> contributors;

    @Column(name = "picker_users", nullable = false, columnDefinition = "TEXT DEFAULT '[]'")
    @Convert(converter = RepositoryPickerUserListConverter.class)
    private List<RepositoryPickerUser> pickerUsers;

    @Column(nullable = false)
    @Convert(converter = RepositoryLabelListConverter.class)
    private List<RepositoryLabel> labels;

    @Column(nullable = false)
    @Convert(converter = StringListConverter.class)
    private List<String> topics;

    @Column(name = "contributors_synced_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant contributorsSyncedAt;

    @Column(name = "picker_users_synced_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant pickerUsersSyncedAt;

    @Column(name = "overview_synced_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant overviewSyncedAt;

    @Column(name = "labels_topics_synced_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant labelsTopicsSyncedAt;

    public Long getRepositoryId() { return repositoryId; }
    public void setRepositoryId(Long repositoryId) { this.repositoryId = repositoryId; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getStargazersCount() { return stargazersCount; }
    public void setStargazersCount(int stargazersCount) { this.stargazersCount = stargazersCount; }

    public Map<String, Long> getLanguages() { return languages; }
    public void setLanguages(Map<String, Long> languages) { this.languages = languages; }

    public List<RepositoryContributor> getContributors() { return contributors; }
    public void setContributors(List<RepositoryContributor> contributors) { this.contributors = contributors; }

    public List<RepositoryPickerUser> getPickerUsers() { return pickerUsers; }
    public void setPickerUsers(List<RepositoryPickerUser> pickerUsers) { this.pickerUsers = pickerUsers; }

    public List<RepositoryLabel> getLabels() { return labels; }
    public void setLabels(List<RepositoryLabel> labels) { this.labels = labels; }

    public List<String> getTopics() { return topics; }
    public void setTopics(List<String> topics) { this.topics = topics; }

    public Instant getContributorsSyncedAt() { return contributorsSyncedAt; }
    public void setContributorsSyncedAt(Instant contributorsSyncedAt) { this.contributorsSyncedAt = contributorsSyncedAt; }

    public Instant getPickerUsersSyncedAt() { return pickerUsersSyncedAt; }
    public void setPickerUsersSyncedAt(Instant pickerUsersSyncedAt) { this.pickerUsersSyncedAt = pickerUsersSyncedAt; }

    public Instant getOverviewSyncedAt() { return overviewSyncedAt; }
    public void setOverviewSyncedAt(Instant overviewSyncedAt) { this.overviewSyncedAt = overviewSyncedAt; }

    public Instant getLabelsTopicsSyncedAt() { return labelsTopicsSyncedAt; }
    public void setLabelsTopicsSyncedAt(Instant labelsTopicsSyncedAt) { this.labelsTopicsSyncedAt = labelsTopicsSyncedAt; }

    public RepositoryMetadataSnapshot toDomain()
    {
        return new RepositoryMetadataSnapshot(
                getRepositoryId(),
                RepoRef.of(getOwner(), getRepo()),
                getDescription(),
                getStargazersCount(),
                getLanguages(),
                getContributors(),
                getPickerUsers(),
                getLabels(),
                getTopics(),
                Optional.ofNullable(getOverviewSyncedAt()),
                Optional.ofNullable(getContributorsSyncedAt()),
                Optional.ofNullable(getPickerUsersSyncedAt()),
                Optional.ofNullable(getLabelsTopicsSyncedAt()));
    }
}
