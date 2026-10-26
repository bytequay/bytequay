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
import com.bytequay.core.domain.ArtifactOffer;
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

    /** Every conversation whose last question timed out unanswered, in any workspace. */
    List<Artifact> missed();

    void save(Artifact artifact);

    /**
     * The conversation's agent events after a sequence, oldest first, as their stored JSON. The events have the same form as a task's,
     * so a conversation renders like a task transcript.
     */
    List<String> events(String workspaceId, String artifactId, long afterSequence, int limit);

    /** The conversation's last event sequence; zero before its first event. */
    long lastEvent(String workspaceId, String artifactId);

    void addEvent(String workspaceId, String artifactId, long sequence, String json);

    /** The conversation's offered pages in the order they were offered. */
    List<ArtifactOffer> offers(String workspaceId, String artifactId);

    void saveOffer(String workspaceId, ArtifactOffer offer);

    /** The conversation's saved page versions, oldest first; pages stay out. */
    List<ArtifactPage> pages(String workspaceId, String artifactId);

    void savePage(String workspaceId, ArtifactPage page, String html);

    Optional<String> pageHtml(String workspaceId, String artifactId, String pageId);

    void deleteWorkspace(String workspaceId);
}
