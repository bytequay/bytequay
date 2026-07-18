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
@Table(name = "repo_metadata_cache")
class RepoMetadataCacheEntity
{
    @Id
    @Column(name = "repo_full_name")
    private String repoFullName;

    @Column(name = "users_json", nullable = false)
    private String usersJson;

    @Column(name = "labels_json", nullable = false)
    private String labelsJson;

    @Column(name = "fetched_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant fetchedAt;

    String getRepoFullName() { return repoFullName; }
    void setRepoFullName(String repoFullName) { this.repoFullName = repoFullName; }

    String getUsersJson() { return usersJson; }
    void setUsersJson(String usersJson) { this.usersJson = usersJson; }

    String getLabelsJson() { return labelsJson; }
    void setLabelsJson(String labelsJson) { this.labelsJson = labelsJson; }

    Instant getFetchedAt() { return fetchedAt; }
    void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
}
