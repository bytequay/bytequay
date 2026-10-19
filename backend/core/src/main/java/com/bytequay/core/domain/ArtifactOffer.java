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
import java.util.UUID;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * A page the agent offered to make once the scope was settled, and the user's answer.
 *
 * @param turnId the turn that offered it, so the offer shows at the end of that turn
 */
public record ArtifactOffer(String id, String artifactId, String turnId, String title, String covers, State state, Instant at)
{
    public enum State
    {
        SHOWN,
        ACCEPTED,
        DISMISSED
    }

    public ArtifactOffer
    {
        checkText(id, "id");
        checkText(artifactId, "artifactId");
        checkText(turnId, "turnId");
        checkText(title, "title");
        checkText(covers, "covers");
        requireNonNull(state, "state is null");
        requireNonNull(at, "at is null");
    }

    public static ArtifactOffer offered(String artifactId, String turnId, String title, String covers, Instant at)
    {
        return new ArtifactOffer(UUID.randomUUID().toString(), artifactId, turnId, title, covers, State.SHOWN, at);
    }

    public ArtifactOffer answered(State answer)
    {
        return new ArtifactOffer(id, artifactId, turnId, title, covers, answer, at);
    }
}
