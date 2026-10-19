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

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A page an agent writes for the user to read inside ByteQuay. The page itself is stored beside this record.
 *
 * @param ask the user's latest request for the page: what it was first asked for, or the change asked last; Try again sends it again
 * @param failure why the last write ended without a page; present only when {@code FAILED}
 * @param writtenAt when the page was last saved; absent until the first save
 * @param commit the default-branch commit the page was last written from
 * @param session the agent conversation the next request continues
 * @param next the follow-up pages the writer suggested with the page
 */
public record Artifact(
        String id,
        String workspaceId,
        String title,
        String ask,
        State state,
        Optional<String> failure,
        Optional<Instant> writtenAt,
        Optional<String> commit,
        Optional<ArtifactSession> session,
        List<ArtifactNext> next,
        Instant updatedAt)
{
    private static final int WORKING_TITLE = 60;

    public enum State
    {
        WRITING,
        READY,
        FAILED
    }

    public Artifact
    {
        checkText(id, "id");
        checkText(workspaceId, "workspaceId");
        checkText(title, "title");
        checkText(ask, "ask");
        requireNonNull(state, "state is null");
        requireNonNull(failure, "failure is null");
        checkArgument(failure.isPresent() == (state == State.FAILED), "Only a failed artifact has a failure");
        requireNonNull(writtenAt, "writtenAt is null");
        requireNonNull(commit, "commit is null");
        requireNonNull(session, "session is null");
        next = ImmutableList.copyOf(next);
        requireNonNull(updatedAt, "updatedAt is null");
    }

    /** A new artifact being written, named by the ask's first words until the writer names it. */
    public static Artifact asked(String id, String workspaceId, String ask, Instant now)
    {
        String line = ask.strip().lines().findFirst().orElse(ask).strip();
        String title = line.length() <= WORKING_TITLE ? line : line.substring(0, WORKING_TITLE - 1).strip() + "…";
        return new Artifact(id, workspaceId, title, ask, State.WRITING, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), now);
    }

    /** Being written again for this request; the page and its cards stay until the writer saves. */
    public Artifact writing(String request, Instant now)
    {
        return new Artifact(id, workspaceId, title, request, State.WRITING, Optional.empty(), writtenAt, commit, session, next, now);
    }

    /** The writer saved the page: its title, the commit it read, and its suggestions, which replace the earlier ones. */
    public Artifact saved(String newTitle, String readCommit, List<ArtifactNext> suggested, Instant now)
    {
        return new Artifact(id, workspaceId, newTitle, ask, state, failure, Optional.of(now), Optional.of(readCommit), session, suggested, now);
    }

    /** The write ended: ready when a page was saved during it, otherwise failed for {@code reason}. */
    public Artifact ended(boolean savedPage, String reason, Optional<ArtifactSession> conversation, Instant now)
    {
        return new Artifact(id, workspaceId, title, ask, savedPage ? State.READY : State.FAILED, savedPage ? Optional.empty() : Optional.of(reason),
                writtenAt, commit, conversation.or(() -> session), next, now);
    }

    public Artifact withNext(List<ArtifactNext> cards, Instant now)
    {
        return new Artifact(id, workspaceId, title, ask, state, failure, writtenAt, commit, session, cards, now);
    }

    public Optional<ArtifactNext> card(String cardId)
    {
        return next.stream().filter(card -> card.id().equals(cardId)).findFirst();
    }
}
