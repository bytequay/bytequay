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
import static java.util.Objects.requireNonNull;

/**
 * A conversation with an agent about the workspace's repository, and the pages it makes. Its items and pages are stored beside it.
 *
 * @param working whether the agent is answering a message now
 * @param session the agent conversation the next message continues
 * @param missed the agent's last question, when it timed out unanswered and the user has not written since
 */
public record Artifact(String id, String workspaceId, String title, boolean working, Optional<ArtifactSession> session, Optional<MissedQuestion> missed,
        Instant createdAt, Instant updatedAt)
{
    private static final int WORKING_TITLE = 60;

    public Artifact
    {
        checkText(id, "id");
        checkText(workspaceId, "workspaceId");
        checkText(title, "title");
        requireNonNull(session, "session is null");
        requireNonNull(missed, "missed is null");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
    }

    /**
     * A question the agent asked that nobody answered in time.
     *
     * @param laneId the agent that asked
     * @param detail the question as the agent asked it
     */
    public record MissedQuestion(String approvalId, String laneId, String detail, Instant at)
    {
        public MissedQuestion
        {
            checkText(approvalId, "approvalId");
            checkText(laneId, "laneId");
            requireNonNull(detail, "detail is null");
            requireNonNull(at, "at is null");
        }
    }

    /** A new conversation, named by its first message's first words until its first page names it. */
    public static Artifact started(String id, String workspaceId, String firstMessage, Instant now)
    {
        String line = firstMessage.strip().lines().findFirst().orElse(firstMessage).strip();
        String title = line.length() <= WORKING_TITLE ? line : line.substring(0, WORKING_TITLE - 1).strip() + "…";
        return new Artifact(id, workspaceId, title, false, Optional.empty(), Optional.empty(), now, now);
    }

    public Artifact working(boolean answering, Instant now)
    {
        return new Artifact(id, workspaceId, title, answering, session, missed, createdAt, now);
    }

    public Artifact continuing(ArtifactSession conversation, Instant now)
    {
        return new Artifact(id, workspaceId, title, working, Optional.of(conversation), missed, createdAt, now);
    }

    public Artifact named(String newTitle, Instant now)
    {
        return new Artifact(id, workspaceId, newTitle, working, session, missed, createdAt, now);
    }

    public Artifact missing(Optional<MissedQuestion> question, Instant now)
    {
        return new Artifact(id, workspaceId, title, working, session, question, createdAt, now);
    }
}
