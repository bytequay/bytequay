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
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** JPA row for a {@code pr_commit}. */
@Entity
@Table(name = "pr_commit")
class PrCommitEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "pr_id", nullable = false)
    private String prId;

    @Column(name = "sha", nullable = false)
    private String sha;

    @Column(name = "message", nullable = false)
    private String message;

    @Column(name = "additions", nullable = false)
    private int additions;

    @Column(name = "deletions", nullable = false)
    private int deletions;

    @Column(name = "authored_at_ms", nullable = false)
    private long authoredAtMs;

    @Column(name = "pushed_at_ms")
    private Long pushedAtMs;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getPrId() { return prId; }
    void setPrId(String prId) { this.prId = prId; }

    String getSha() { return sha; }
    void setSha(String sha) { this.sha = sha; }

    String getMessage() { return message; }
    void setMessage(String message) { this.message = message; }

    int getAdditions() { return additions; }
    void setAdditions(int additions) { this.additions = additions; }

    int getDeletions() { return deletions; }
    void setDeletions(int deletions) { this.deletions = deletions; }

    long getAuthoredAtMs() { return authoredAtMs; }
    void setAuthoredAtMs(long authoredAtMs) { this.authoredAtMs = authoredAtMs; }

    Long getPushedAtMs() { return pushedAtMs; }
    void setPushedAtMs(Long pushedAtMs) { this.pushedAtMs = pushedAtMs; }
}
