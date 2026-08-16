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

import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import com.bytequay.persistence.sqlite.converter.StringListConverter;
import com.bytequay.persistence.sqlite.converter.StringLongMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Entity
@Table(name = "repo_meta")
public class RepoMetaEntity
{
    @EmbeddedId
    private RepoMetaKey id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "html_url", nullable = false)
    private String htmlUrl;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "default_branch")
    private String defaultBranch;

    private String license;

    @Column(name = "stargazers_count", nullable = false)
    private int stargazersCount;

    @Column(name = "forks_count", nullable = false)
    private int forksCount;

    @Column(name = "watchers_count", nullable = false)
    private int watchersCount;

    @Column(name = "open_issues_count", nullable = false)
    private int openIssuesCount;

    @Column(name = "size_kb", nullable = false)
    private long sizeKb;

    @Column(name = "created_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Column(name = "pushed_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant pushedAt;

    @Column(nullable = false)
    @Convert(converter = StringListConverter.class)
    private List<String> topics;

    @Column(nullable = false)
    @Convert(converter = StringLongMapConverter.class)
    private Map<String, Long> languages;

    @Column(name = "owner_avatar_url")
    private String ownerAvatarUrl;

    @Column(name = "parent_owner")
    private String parentOwner;

    @Column(name = "parent_repo")
    private String parentRepo;

    @Column(name = "parent_default_branch")
    private String parentDefaultBranch;

    @Column(name = "synced_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant syncedAt;

    public RepoMetaKey getId() { return id; }
    public void setId(RepoMetaKey id) { this.id = id; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getHtmlUrl() { return htmlUrl; }
    public void setHtmlUrl(String htmlUrl) { this.htmlUrl = htmlUrl; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }

    public String getLicense() { return license; }
    public void setLicense(String license) { this.license = license; }

    public int getStargazersCount() { return stargazersCount; }
    public void setStargazersCount(int stargazersCount) { this.stargazersCount = stargazersCount; }

    public int getForksCount() { return forksCount; }
    public void setForksCount(int forksCount) { this.forksCount = forksCount; }

    public int getWatchersCount() { return watchersCount; }
    public void setWatchersCount(int watchersCount) { this.watchersCount = watchersCount; }

    public int getOpenIssuesCount() { return openIssuesCount; }
    public void setOpenIssuesCount(int openIssuesCount) { this.openIssuesCount = openIssuesCount; }

    public long getSizeKb() { return sizeKb; }
    public void setSizeKb(long sizeKb) { this.sizeKb = sizeKb; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getPushedAt() { return pushedAt; }
    public void setPushedAt(Instant pushedAt) { this.pushedAt = pushedAt; }

    public List<String> getTopics() { return topics; }
    public void setTopics(List<String> topics) { this.topics = topics; }

    public Map<String, Long> getLanguages() { return languages; }
    public void setLanguages(Map<String, Long> languages) { this.languages = languages; }

    public String getOwnerAvatarUrl() { return ownerAvatarUrl; }
    public void setOwnerAvatarUrl(String ownerAvatarUrl) { this.ownerAvatarUrl = ownerAvatarUrl; }

    public String getParentOwner() { return parentOwner; }
    public void setParentOwner(String parentOwner) { this.parentOwner = parentOwner; }

    public String getParentRepo() { return parentRepo; }
    public void setParentRepo(String parentRepo) { this.parentRepo = parentRepo; }

    public String getParentDefaultBranch() { return parentDefaultBranch; }
    public void setParentDefaultBranch(String parentDefaultBranch) { this.parentDefaultBranch = parentDefaultBranch; }

    public Instant getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }

    @Embeddable
    public static class RepoMetaKey
            implements Serializable
    {
        @Column(nullable = false)
        private String owner;

        @Column(nullable = false)
        private String repo;

        public RepoMetaKey() {}

        public RepoMetaKey(String owner, String repo)
        {
            this.owner = owner;
            this.repo = repo;
        }

        String getOwner() { return owner; }
        void setOwner(String owner) { this.owner = owner; }

        String getRepo() { return repo; }
        void setRepo(String repo) { this.repo = repo; }

        @Override
        public boolean equals(Object o)
        {
            if (!(o instanceof RepoMetaKey other)) {
                return false;
            }
            return Objects.equals(owner, other.owner) && Objects.equals(repo, other.repo);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(owner, repo);
        }
    }
}
