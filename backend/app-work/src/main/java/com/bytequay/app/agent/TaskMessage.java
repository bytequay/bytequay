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
package com.bytequay.app.agent;

import com.bytequay.agent.core.TurnId;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** The start of the newest root message in a task's conversation, and who sent it. */
public record TaskMessage(Author author, String text, Instant at, Optional<TurnId> turnId)
{
    public enum Author
    {
        USER,
        AGENT,
    }

    public TaskMessage
    {
        requireNonNull(author, "author is null");
        requireNonNull(text, "text is null");
        requireNonNull(at, "at is null");
        requireNonNull(turnId, "turnId is null");
    }
}
