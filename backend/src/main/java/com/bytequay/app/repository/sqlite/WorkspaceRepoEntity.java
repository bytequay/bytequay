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
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "workspace_repos")
class WorkspaceRepoEntity
{
    @EmbeddedId
    private WorkspaceRepoKey id;

    @Column(name = "default_base_branch")
    private String defaultBaseBranch;

    @Column(name = "added_at_ms", nullable = false)
    private long addedAtMs;

    WorkspaceRepoKey getId() { return id; }
    void setId(WorkspaceRepoKey id) { this.id = id; }

    String getDefaultBaseBranch() { return defaultBaseBranch; }
    void setDefaultBaseBranch(String defaultBaseBranch) { this.defaultBaseBranch = defaultBaseBranch; }

    long getAddedAtMs() { return addedAtMs; }
    void setAddedAtMs(long addedAtMs) { this.addedAtMs = addedAtMs; }

    @Embeddable
    static final class WorkspaceRepoKey
            implements Serializable
    {
        @Column(name = "workspace_id", nullable = false)
        private String workspaceId;

        @Column(name = "repo_full_name", nullable = false)
        private String repoFullName;

        WorkspaceRepoKey() {}

        WorkspaceRepoKey(String workspaceId, String repoFullName)
        {
            this.workspaceId = workspaceId;
            this.repoFullName = repoFullName;
        }

        String getWorkspaceId() { return workspaceId; }
        void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

        String getRepoFullName() { return repoFullName; }
        void setRepoFullName(String repoFullName) { this.repoFullName = repoFullName; }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (!(o instanceof WorkspaceRepoKey that)) {
                return false;
            }
            return Objects.equals(workspaceId, that.workspaceId)
                    && Objects.equals(repoFullName, that.repoFullName);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(workspaceId, repoFullName);
        }
    }
}
