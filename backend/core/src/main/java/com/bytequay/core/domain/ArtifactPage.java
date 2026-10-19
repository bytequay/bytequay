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
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One saved version of a page an artifact's agent wrote. The HTML is stored beside it.
 *
 * @param id this version's own id, which is also the viewer's origin, so versions cannot read each other's storage
 * @param key the page this version belongs to, such as {@code p1}; the agent names it to save a new version
 * @param commit the default-branch commit the agent read
 * @param turnId the turn that saved it, so its line shows at the end of that turn; absent on pages saved before turns were recorded
 */
public record ArtifactPage(String id, String artifactId, String key, int version, String title, String commit, Instant writtenAt, Optional<String> turnId)
{
    public ArtifactPage
    {
        checkText(id, "id");
        checkText(artifactId, "artifactId");
        checkText(key, "key");
        checkArgument(version > 0, "version must be positive");
        checkText(title, "title");
        checkText(commit, "commit");
        requireNonNull(writtenAt, "writtenAt is null");
        requireNonNull(turnId, "turnId is null");
    }
}
