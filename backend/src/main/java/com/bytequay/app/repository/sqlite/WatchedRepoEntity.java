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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "watched_repos")
class WatchedRepoEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String repo;

    @Column(nullable = false)
    private int displayOrder;

    @Column(name = "local_clone_path")
    private String localClonePath;

    @Column(name = "upstream_remote_name")
    private String upstreamRemoteName;

    @Column(name = "view_focus")
    private String viewFocus;

    protected WatchedRepoEntity() {}

    WatchedRepoEntity(String owner, String repo, int displayOrder)
    {
        this.owner = owner;
        this.repo = repo;
        this.displayOrder = displayOrder;
    }

    Long getId() { return id; }

    String getOwner() { return owner; }

    String getRepo() { return repo; }

    int getDisplayOrder() { return displayOrder; }
    void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }

    String getLocalClonePath() { return localClonePath; }
    void setLocalClonePath(String localClonePath) { this.localClonePath = localClonePath; }

    String getUpstreamRemoteName() { return upstreamRemoteName; }
    void setUpstreamRemoteName(String upstreamRemoteName) { this.upstreamRemoteName = upstreamRemoteName; }

    String getViewFocus() { return viewFocus; }
    void setViewFocus(String viewFocus) { this.viewFocus = viewFocus; }
}
