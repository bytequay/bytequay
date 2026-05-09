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
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "github_user_profile_cache")
class GithubUserProfileCacheEntity
{
    @Id
    private String login;

    @Column(nullable = false)
    private String payload;

    @Column(name = "fetched_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant fetchedAt;

    String getLogin() { return login; }
    void setLogin(String login) { this.login = login; }

    String getPayload() { return payload; }
    void setPayload(String payload) { this.payload = payload; }

    Instant getFetchedAt() { return fetchedAt; }
    void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
}
