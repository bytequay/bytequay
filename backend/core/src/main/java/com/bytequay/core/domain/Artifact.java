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
package com.bytequay.core.domain;

import java.time.Instant;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * A page written for the user to read inside ByteQuay.
 *
 * @param html a whole HTML document; the desktop viewer runs its scripts without network or app access
 */
public record Artifact(String id, String workspaceId, String title, String html, Instant updatedAt)
{
    public Artifact
    {
        checkText(id, "id");
        checkText(workspaceId, "workspaceId");
        checkText(title, "title");
        requireNonNull(html, "html is null");
        requireNonNull(updatedAt, "updatedAt is null");
    }

    public ArtifactSummary summary()
    {
        return new ArtifactSummary(id, title, updatedAt);
    }
}
