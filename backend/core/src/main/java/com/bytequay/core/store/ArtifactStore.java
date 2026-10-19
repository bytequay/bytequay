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
package com.bytequay.core.store;

import com.bytequay.core.domain.Artifact;
import com.bytequay.core.domain.ArtifactItem;
import com.bytequay.core.domain.ArtifactPage;

import java.util.List;
import java.util.Optional;

public interface ArtifactStore
{
    Optional<Artifact> find(String workspaceId, String id);

    /** Newest change first. */
    List<Artifact> list(String workspaceId);

    /** Every conversation still marked as answering, in any workspace. */
    List<Artifact> working();

    void save(Artifact artifact);

    /** The conversation's items in the order they were added. */
    List<ArtifactItem> items(String workspaceId, String artifactId);

    /** Appends the item after the conversation's last one. */
    void add(String workspaceId, ArtifactItem item);

    /** Replaces an item in place, such as a growing activity line or an answered offer. */
    void update(ArtifactItem item);

    /** The conversation's saved page versions, oldest first; pages stay out. */
    List<ArtifactPage> pages(String workspaceId, String artifactId);

    void savePage(String workspaceId, ArtifactPage page, String html);

    Optional<String> pageHtml(String workspaceId, String artifactId, String pageId);

    void deleteWorkspace(String workspaceId);
}
