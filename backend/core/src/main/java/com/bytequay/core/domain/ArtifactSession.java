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

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * The agent conversation an artifact's next request continues.
 *
 * @param laneId the agent lane the conversation belongs to; on another lane the next request starts a fresh one
 */
public record ArtifactSession(String laneId, DiscussionSession conversation)
{
    public ArtifactSession
    {
        checkText(laneId, "laneId");
        requireNonNull(conversation, "conversation is null");
    }
}
