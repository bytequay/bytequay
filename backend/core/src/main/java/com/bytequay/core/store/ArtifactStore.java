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

import java.util.List;
import java.util.Optional;

public interface ArtifactStore
{
    Optional<Artifact> find(String workspaceId, String id);

    /** Newest change first; pages stay out so a long list reads only the records. */
    List<Artifact> list(String workspaceId);

    /** Every artifact still marked as being written, in any workspace. */
    List<Artifact> writing();

    /** The saved page; absent until the writer first saves one. */
    Optional<String> page(String workspaceId, String id);

    /** Saves the record and keeps its page. */
    void save(Artifact artifact);

    /** Saves the record with a new page. */
    void save(Artifact artifact, String page);

    void deleteWorkspace(String workspaceId);
}
