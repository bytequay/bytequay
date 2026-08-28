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

import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Entity
@Table(name = "commit_enrichment")
@Getter
@Setter
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

    @Embeddable
    @Getter
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

    public CommitEnrichment toDomain()
    {
        return new CommitEnrichment(
                getId().getRepositoryId(),
                getId().getSha(),
                Optional.ofNullable(getAuthorLogin()),
                Optional.ofNullable(getAvatarKey()),
                Optional.ofNullable(getCommitterLogin()),
                Optional.ofNullable(getCommitterAvatarKey()),
                Optional.ofNullable(getPullRequestNumber()),
                Optional.ofNullable(getPullRequestTitle()),
                Optional.ofNullable(getPullRequestUrl()),
                Optional.ofNullable(getAvatarCheckedAt()),
                Optional.ofNullable(getAssociationCheckedAt()),
                getLastSeenAt());
    }
}
