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
import java.util.Locale;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One supporting reference on a work item. The label and title are the ones it had
 * when it was added, so an item that later leaves every local cache still reads as itself.
 *
 * @param key {@code owner/repo#number} for a pull request or issue, {@code connection:cloud:issue}
 * for a Jira ticket, and the task id for a task
 * @param label what the item is called beside its icon: {@code #123}, {@code TRINO-412} or {@code Task}
 */
public record GoalItem(GoalItemKind kind, String key, String label, String title, Instant addedAt)
{
    public GoalItem
    {
        requireNonNull(kind, "kind is null");
        requireNonNull(key, "key is null");
        requireNonNull(label, "label is null");
        requireNonNull(title, "title is null");
        requireNonNull(addedAt, "addedAt is null");
        checkArgument(!key.isBlank(), "key is blank");
    }

    /** The key of a pull request or issue: the repository in lower case, then its number. */
    public static String numberedKey(String repo, int number)
    {
        checkArgument(number > 0, "number must be positive");
        return repo.toLowerCase(Locale.ROOT) + "#" + number;
    }

    public static String jiraKey(String connectionId, String cloudId, String issueId)
    {
        return connectionId + ":" + cloudId + ":" + issueId;
    }
}
