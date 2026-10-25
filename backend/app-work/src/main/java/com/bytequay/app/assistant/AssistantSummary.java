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
package com.bytequay.app.assistant;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * One conversation in the panel's history.
 *
 * @param title the first message's words
 * @param updatedAt when the user last wrote in it or a reply last ended
 * @param running whether a reply in it is being written now
 */
public record AssistantSummary(String id, String title, Instant updatedAt, boolean running)
{
    public AssistantSummary
    {
        requireNonNull(id, "id is null");
        requireNonNull(title, "title is null");
        requireNonNull(updatedAt, "updatedAt is null");
    }
}
