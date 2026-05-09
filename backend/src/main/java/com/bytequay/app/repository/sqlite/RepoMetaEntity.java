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
package com.bytequay.app.repository.sqlite;

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
class RepoMetaEntity
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

    @Column(name = "synced_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant syncedAt;

    RepoMetaKey getId() { return id; }
    void setId(RepoMetaKey id) { this.id = id; }

    String getFullName() { return fullName; }
    void setFullName(String fullName) { this.fullName = fullName; }

    String getHtmlUrl() { return htmlUrl; }
    void setHtmlUrl(String htmlUrl) { this.htmlUrl = htmlUrl; }

    String getDescription() { return description; }
    void setDescription(String description) { this.description = description; }

    String getDefaultBranch() { return defaultBranch; }
    void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }

    String getLicense() { return license; }
    void setLicense(String license) { this.license = license; }

    int getStargazersCount() { return stargazersCount; }
    void setStargazersCount(int stargazersCount) { this.stargazersCount = stargazersCount; }

    int getForksCount() { return forksCount; }
    void setForksCount(int forksCount) { this.forksCount = forksCount; }

    int getWatchersCount() { return watchersCount; }
    void setWatchersCount(int watchersCount) { this.watchersCount = watchersCount; }

    int getOpenIssuesCount() { return openIssuesCount; }
    void setOpenIssuesCount(int openIssuesCount) { this.openIssuesCount = openIssuesCount; }

    long getSizeKb() { return sizeKb; }
    void setSizeKb(long sizeKb) { this.sizeKb = sizeKb; }

    Instant getCreatedAt() { return createdAt; }
    void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    Instant getPushedAt() { return pushedAt; }
    void setPushedAt(Instant pushedAt) { this.pushedAt = pushedAt; }

    List<String> getTopics() { return topics; }
    void setTopics(List<String> topics) { this.topics = topics; }

    Map<String, Long> getLanguages() { return languages; }
    void setLanguages(Map<String, Long> languages) { this.languages = languages; }

    Instant getSyncedAt() { return syncedAt; }
    void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }

    @Embeddable
    static class RepoMetaKey
            implements Serializable
    {
        @Column(nullable = false)
        private String owner;

        @Column(nullable = false)
        private String repo;

        RepoMetaKey() {}

        RepoMetaKey(String owner, String repo)
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
