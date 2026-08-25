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
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "commit_enrichment")
public class CommitEnrichmentEntity
{
    @EmbeddedId
    private Key id;

    @Column(name = "author_login")
    private String authorLogin;

    @Column(name = "avatar_key")
    private String avatarKey;

    @Column(name = "committer_login")
    private String committerLogin;

    @Column(name = "committer_avatar_key")
    private String committerAvatarKey;

    @Column(name = "pull_request_number")
    private Integer pullRequestNumber;

    @Column(name = "pull_request_title")
    private String pullRequestTitle;

    @Column(name = "pull_request_url")
    private String pullRequestUrl;

    @Column(name = "avatar_checked_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant avatarCheckedAt;

    @Column(name = "association_checked_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant associationCheckedAt;

    @Column(name = "last_seen_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant lastSeenAt;

    public Key getId() { return id; }
    public void setId(Key id) { this.id = id; }

    public String getAuthorLogin() { return authorLogin; }
    public void setAuthorLogin(String authorLogin) { this.authorLogin = authorLogin; }

    public String getAvatarKey() { return avatarKey; }
    public void setAvatarKey(String avatarKey) { this.avatarKey = avatarKey; }

    public String getCommitterLogin() { return committerLogin; }
    public void setCommitterLogin(String committerLogin) { this.committerLogin = committerLogin; }

    public String getCommitterAvatarKey() { return committerAvatarKey; }
    public void setCommitterAvatarKey(String committerAvatarKey) { this.committerAvatarKey = committerAvatarKey; }

    public Integer getPullRequestNumber() { return pullRequestNumber; }
    public void setPullRequestNumber(Integer pullRequestNumber) { this.pullRequestNumber = pullRequestNumber; }

    public String getPullRequestTitle() { return pullRequestTitle; }
    public void setPullRequestTitle(String pullRequestTitle) { this.pullRequestTitle = pullRequestTitle; }

    public String getPullRequestUrl() { return pullRequestUrl; }
    public void setPullRequestUrl(String pullRequestUrl) { this.pullRequestUrl = pullRequestUrl; }

    public Instant getAvatarCheckedAt() { return avatarCheckedAt; }
    public void setAvatarCheckedAt(Instant avatarCheckedAt) { this.avatarCheckedAt = avatarCheckedAt; }

    public Instant getAssociationCheckedAt() { return associationCheckedAt; }
    public void setAssociationCheckedAt(Instant associationCheckedAt) { this.associationCheckedAt = associationCheckedAt; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    @Embeddable
    public static class Key
            implements Serializable
    {
        @Column(name = "repository_id", nullable = false)
        private Long repositoryId;

        @Column(nullable = false, length = 64)
        private String sha;

        public Key() {}

        public Key(long repositoryId, String sha)
        {
            this.repositoryId = repositoryId;
            this.sha = sha;
        }

        public Long getRepositoryId() { return repositoryId; }
        public String getSha() { return sha; }

        @Override
        public boolean equals(Object object)
        {
            return object instanceof Key other
                    && Objects.equals(repositoryId, other.repositoryId)
                    && Objects.equals(sha, other.sha);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(repositoryId, sha);
        }
    }
}
