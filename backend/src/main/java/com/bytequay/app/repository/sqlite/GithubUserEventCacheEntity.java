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
import java.util.Objects;

@Entity
@Table(name = "github_user_event_cache")
class GithubUserEventCacheEntity
{
    @EmbeddedId
    private GithubUserEventCacheKey id;

    @Column(nullable = false)
    private String payload;

    @Column(name = "fetched_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant fetchedAt;

    GithubUserEventCacheKey getId() { return id; }
    void setId(GithubUserEventCacheKey id) { this.id = id; }

    String getPayload() { return payload; }
    void setPayload(String payload) { this.payload = payload; }

    Instant getFetchedAt() { return fetchedAt; }
    void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }

    @Embeddable
    static class GithubUserEventCacheKey
            implements Serializable
    {
        @Column(nullable = false)
        private String login;

        @Column(nullable = false)
        private String feed;

        GithubUserEventCacheKey() {}

        GithubUserEventCacheKey(String login, String feed)
        {
            this.login = login;
            this.feed = feed;
        }

        String getLogin() { return login; }
        void setLogin(String login) { this.login = login; }

        String getFeed() { return feed; }
        void setFeed(String feed) { this.feed = feed; }

        @Override
        public boolean equals(Object o)
        {
            if (!(o instanceof GithubUserEventCacheKey other)) {
                return false;
            }
            return Objects.equals(login, other.login) && Objects.equals(feed, other.feed);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(login, feed);
        }
    }
}
