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
package com.bytequay.app.jira;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * The last change ByteQuay saw on a cached ticket, found by comparing a refreshed ticket with the one
 * it replaced. Jira's changelog is never read, so a status or field change names no actor.
 *
 * @param note the opening text of a comment that arrived with a status change
 * @param fields the changed fields in words, such as {@code priority} or {@code due date}
 */
public record JiraActivity(
        Kind kind,
        Instant at,
        Optional<JiraDtos.Person> actor,
        Optional<JiraDtos.Status> from,
        Optional<JiraDtos.Status> to,
        Optional<String> note,
        List<String> fields)
{
    public enum Kind
    {
        STATUS_CHANGED,
        COMMENTED,
        FIELDS_CHANGED
    }

    public JiraActivity
    {
        requireNonNull(kind, "kind is null");
        requireNonNull(at, "at is null");
        requireNonNull(actor, "actor is null");
        requireNonNull(from, "from is null");
        requireNonNull(to, "to is null");
        requireNonNull(note, "note is null");
        fields = ImmutableList.copyOf(requireNonNull(fields, "fields is null"));
    }
}
