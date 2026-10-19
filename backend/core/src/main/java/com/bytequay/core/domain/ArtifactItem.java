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
import java.util.UUID;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One entry of an artifact's conversation.
 *
 * @param text the message or reply; for activity, what the agent last read and how many steps it took; for a failure, why
 * @param offer what an offered page would cover and the user's answer; present only on an {@code OFFER}
 * @param pageId the saved page version; present only on a {@code PAGE}
 */
public record ArtifactItem(String id, String artifactId, Kind kind, String text, Optional<Offer> offer, Optional<String> pageId, Instant at)
{
    public enum Kind
    {
        USER,
        REPLY,
        ACTIVITY,
        OFFER,
        PAGE,
        FAILURE
    }

    /** A page the agent offered to make once the scope was settled. */
    public record Offer(String title, String covers, State state)
    {
        public enum State
        {
            SHOWN,
            ACCEPTED,
            DISMISSED
        }

        public Offer
        {
            checkText(title, "title");
            checkText(covers, "covers");
            requireNonNull(state, "state is null");
        }
    }

    public ArtifactItem
    {
        checkText(id, "id");
        checkText(artifactId, "artifactId");
        requireNonNull(kind, "kind is null");
        requireNonNull(text, "text is null");
        requireNonNull(offer, "offer is null");
        requireNonNull(pageId, "pageId is null");
        checkArgument(offer.isPresent() == (kind == Kind.OFFER), "Only an offer carries an offer");
        checkArgument(pageId.isPresent() == (kind == Kind.PAGE), "Only a page item names a page");
        requireNonNull(at, "at is null");
    }

    public static ArtifactItem of(String artifactId, Kind kind, String text, Instant at)
    {
        return new ArtifactItem(UUID.randomUUID().toString(), artifactId, kind, text, Optional.empty(), Optional.empty(), at);
    }

    public static ArtifactItem offered(String artifactId, String title, String covers, Instant at)
    {
        return new ArtifactItem(UUID.randomUUID().toString(), artifactId, Kind.OFFER, "", Optional.of(new Offer(title, covers, Offer.State.SHOWN)), Optional.empty(), at);
    }

    public static ArtifactItem page(String artifactId, String pageId, String text, Instant at)
    {
        return new ArtifactItem(UUID.randomUUID().toString(), artifactId, Kind.PAGE, text, Optional.empty(), Optional.of(pageId), at);
    }

    public ArtifactItem withText(String newText)
    {
        return new ArtifactItem(id, artifactId, kind, newText, offer, pageId, at);
    }

    public ArtifactItem answered(Offer.State answer)
    {
        Offer shown = offer.orElseThrow(() -> new IllegalStateException("Not an offer"));
        return new ArtifactItem(id, artifactId, kind, text, Optional.of(new Offer(shown.title(), shown.covers(), answer)), pageId, at);
    }
}
