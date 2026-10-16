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
 * One suggestion on a day's agenda, as the agent wrote it and the app resolved it.
 *
 * @param ref the reference the agent picked, exactly as the agenda's reading tool handed it out
 * @param label a short name for a chip, without the verb its action icon already says
 * @param at since when the item has waited, which the row shows as its age
 * @param workspaceId the workspace the item belongs to; a workspace Home shows only its own
 */
public record AgendaItem(
        String ref,
        Action action,
        String title,
        String label,
        String why,
        Optional<Instant> at,
        Optional<String> workspaceId,
        AgendaTarget target)
{
    public enum Action
    {
        START, REPLY, REVIEW, FIX, MERGE, ANSWER, DECIDE, FOLLOW_UP
    }

    public AgendaItem
    {
        checkText(ref, "ref");
        requireNonNull(action, "action is null");
        checkText(title, "title");
        checkText(label, "label");
        requireNonNull(why, "why is null");
        requireNonNull(at, "at is null");
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(target, "target is null");
    }
}
