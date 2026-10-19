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

import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A follow-up page the writer suggested under a page.
 *
 * @param ask the whole request Write it sends, in the user's voice
 * @param artifactId the page Write it started; present only when {@code WRITTEN}
 */
public record ArtifactNext(String id, String title, String ask, State state, Optional<String> artifactId)
{
    public enum State
    {
        SHOWN,
        WRITTEN,
        DISMISSED
    }

    public ArtifactNext
    {
        checkText(id, "id");
        checkText(title, "title");
        checkText(ask, "ask");
        requireNonNull(state, "state is null");
        requireNonNull(artifactId, "artifactId is null");
        checkArgument(artifactId.isPresent() == (state == State.WRITTEN), "Only a written card names its page");
    }

    public ArtifactNext written(String pageId)
    {
        return new ArtifactNext(id, title, ask, State.WRITTEN, Optional.of(pageId));
    }

    public ArtifactNext dismissed()
    {
        return new ArtifactNext(id, title, ask, State.DISMISSED, Optional.empty());
    }
}
