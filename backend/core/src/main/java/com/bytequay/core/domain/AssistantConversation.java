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

import com.bytequay.core.discussion.DiscussionSession;
import com.google.common.collect.ImmutableMap;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One assistant conversation: its title, the agent its last reply ran with, the provider conversation the next message resumes, and
 * every ref its read tool handed out.
 *
 * @param id a UUID the panel chose, since it also names the conversation's folder
 * @param title the first message's words, or the first receipt's line when a card's button began it
 * @param laneId the agent lane the session belongs to, empty before the first reply; a message on another lane starts a fresh session
 * @param model the model label of the last reply
 * @param session the provider conversation the next message resumes; its lane is {@code laneId}
 * @param served each ref the read tool returned in this conversation, with where it opens, so a later message may name it
 * @param updatedAt when the user last wrote in it or a reply last ended, which orders the history
 */
public record AssistantConversation(String id, String title, Optional<String> laneId, Optional<String> model, Optional<DiscussionSession> session, Map<String, ServedRef> served,
        Instant createdAt, Instant updatedAt)
{
    public AssistantConversation
    {
        checkArgument(isId(id), "id is not a conversation id");
        checkText(title, "title");
        checkOptionalText(laneId, "laneId");
        checkOptionalText(model, "model");
        requireNonNull(session, "session is null");
        served = ImmutableMap.copyOf(served);
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
    }

    public static AssistantConversation start(String id, String title, Instant at)
    {
        return new AssistantConversation(id, title, Optional.empty(), Optional.empty(), Optional.empty(), ImmutableMap.of(), at, at);
    }

    /** Whether the text is a conversation id: a UUID in its own spelling, so it is safe as a folder name. */
    public static boolean isId(String id)
    {
        try {
            return id != null && UUID.fromString(id).toString().equals(id);
        }
        catch (IllegalArgumentException notUuid) {
            return false;
        }
    }
}
