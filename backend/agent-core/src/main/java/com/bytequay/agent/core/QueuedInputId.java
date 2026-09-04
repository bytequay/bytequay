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
package com.bytequay.agent.core;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** One user request waiting in a task's queue to become a turn. */
public record QueuedInputId(UUID value)
{
    public QueuedInputId
    {
        requireNonNull(value, "value is null");
    }

    public static QueuedInputId random()
    {
        return new QueuedInputId(UUID.randomUUID());
    }

    public static QueuedInputId valueOf(String value)
    {
        return new QueuedInputId(UUID.fromString(value));
    }

    @Override
    public String toString()
    {
        return value.toString();
    }
}
