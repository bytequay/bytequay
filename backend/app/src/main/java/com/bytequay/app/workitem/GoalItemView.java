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
package com.bytequay.app.workitem;

import com.bytequay.core.domain.GoalItemKind;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * A goal item, or a candidate for one, as read from local stores.
 *
 * @param label what the item is called beside its icon: {@code #123}, {@code ENG-412} or {@code Task}
 * @param status the item's own state in words, such as {@code Merged}, {@code In Progress} or {@code Running}
 * @param blocked whether the item waits on others: a Jira ticket in a Blocked status
 * @param addedAt when the item joined the goal; empty for a candidate
 * @param priority the source's own priority, such as a Jira ticket's, shown as it is and never mapped to the work's
 */
public record GoalItemView(
        GoalItemKind kind,
        String key,
        String label,
        String title,
        GoalItemState state,
        String status,
        boolean blocked,
        Optional<String> url,
        Optional<GoalActivity> activity,
        Optional<Instant> addedAt,
        Optional<String> priority)
{
    public GoalItemView
    {
        requireNonNull(kind, "kind is null");
        requireNonNull(key, "key is null");
        requireNonNull(label, "label is null");
        requireNonNull(title, "title is null");
        requireNonNull(state, "state is null");
        requireNonNull(status, "status is null");
        requireNonNull(url, "url is null");
        requireNonNull(activity, "activity is null");
        requireNonNull(addedAt, "addedAt is null");
        requireNonNull(priority, "priority is null");
    }

    GoalItemView withAddedAt(Instant at)
    {
        return new GoalItemView(kind, key, label, title, state, status, blocked, url, activity, Optional.of(at), priority);
    }
}
