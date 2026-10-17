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
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * One local day's assistant conversation: the agent its last reply ran with, the provider conversation the next message resumes, and
 * every ref its read tool handed out that day.
 *
 * @param laneId the agent lane the session belongs to; a message on another lane starts a fresh session
 * @param model the model label of the last reply
 * @param session the provider conversation the next message resumes; its lane is {@code laneId}
 * @param served each ref the read tool returned today, with where it opens, so a later message may name it
 */
public record AssistantDay(LocalDate day, String laneId, String model, Optional<DiscussionSession> session, Map<String, ServedRef> served, Instant createdAt)
{
    public AssistantDay
    {
        requireNonNull(day, "day is null");
        requireNonNull(laneId, "laneId is null");
        requireNonNull(model, "model is null");
        requireNonNull(session, "session is null");
        served = ImmutableMap.copyOf(served);
        requireNonNull(createdAt, "createdAt is null");
    }
}
