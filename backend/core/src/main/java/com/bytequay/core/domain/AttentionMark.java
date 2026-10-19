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

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A Needs attention item the user acknowledged or snoozed, kept under the key the list names it by. An acknowledgment, and a snooze
 * with no end, last until something new happens to the item; a timed snooze lasts until it ends.
 *
 * @param key the item's key in the list: owner/repo#number for a pull request or an issue, jira:cloud:id for a ticket, task:id for a task
 * @param kind whether the item was acknowledged or snoozed
 * @param until when a timed snooze ends
 * @param seenUpdatedAt the item's update time as the list read it when it was marked, or the time the assistant marked it
 * @param seenActivityVersion the list's own fingerprint of the item's activity when it was marked; empty until the list first draws an
 * item the assistant marked
 * @param label how the list names the mark, such as "acknowledged" or "tomorrow"
 * @param snoozedItem the item as the list drew it, in the list's own JSON, so a timed snooze comes back after the item leaves the list
 */
public record AttentionMark(String key, Kind kind, Optional<Instant> until, String seenUpdatedAt, Optional<String> seenActivityVersion, String label,
        Optional<String> snoozedItem)
{
    public enum Kind
    {
        ACK, SNOOZE
    }

    public AttentionMark
    {
        checkText(key, "key", 500);
        requireNonNull(kind, "kind is null");
        requireNonNull(until, "until is null");
        checkArgument(until.isEmpty() || kind == Kind.SNOOZE, "only a snooze ends at a time");
        // The list copies the item's time as its source gave it, so only its length is checked.
        checkArgument(requireNonNull(seenUpdatedAt, "seenUpdatedAt is null").length() <= 64, "seenUpdatedAt is too long");
        // An issue's whole description rides along in both its fingerprint and its copy.
        checkOptionalText(seenActivityVersion, "seenActivityVersion", 512 * 1024);
        checkText(label, "label", 500);
        checkOptionalText(snoozedItem, "snoozedItem", 1024 * 1024);
    }
}
