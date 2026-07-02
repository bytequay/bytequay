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

/**
 * Cached write-permission verdict for one (repo, login) pair. Keyed by a
 * synthetic {@code repo#login} id — login can't contain {@code #}, so the
 * delimiter never collides.
 */
@Entity
@Table(name = "repo_write_collaborator")
class RepoWriteCollaboratorEntity
{
    @Id
    private String id;

    @Column(name = "repo_full_name", nullable = false)
    private String repoFullName;

    @Column(nullable = false)
    private String login;

    @Column(name = "can_write", nullable = false)
    private boolean canWrite;

    @Column(name = "fetched_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant fetchedAt;

    static String idOf(String repoFullName, String login)
    {
        return repoFullName + "#" + login;
    }

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getRepoFullName() { return repoFullName; }
    void setRepoFullName(String repoFullName) { this.repoFullName = repoFullName; }

    String getLogin() { return login; }
    void setLogin(String login) { this.login = login; }

    boolean isCanWrite() { return canWrite; }
    void setCanWrite(boolean canWrite) { this.canWrite = canWrite; }

    Instant getFetchedAt() { return fetchedAt; }
    void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
}
