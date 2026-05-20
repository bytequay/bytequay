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
package com.bytequay.app.domain;

import java.time.Instant;

/**
 * One row of the Issue detail timeline — fed to the Activity tab and
 * (filtered) the Linked tab. A flatter shape than
 * {@link PrTimelineEvent} because issues don't carry commits, reviews,
 * or per-line comments; every variant we care about can be expressed
 * with a small set of optional sub-records.
 *
 * <p>{@code event} is GitHub's verbatim event string ("labeled",
 * "assigned", "milestoned", "closed", "renamed", "cross-referenced", …).
 * The renderer dispatches on it. "commented" events are NOT included; those live on
 * {@link IssueDetail#comments()}.
 *
 * @param label populated for labeled and unlabeled events.
 * @param assignee populated for assigned and unassigned events.
 * @param milestone populated for milestoned and demilestoned events.
 * @param rename populated for renamed events.
 * @param crossReference populated for cross-referenced events.
 */
public record IssueTimelineEvent(
        String event,
        String actor,
        Instant timestamp,
        Label label,
        String assignee,
        String milestone,
        Rename rename,
        CrossReference crossReference)
{
    public record Label(String name, String color) {}

    public record Rename(String from, String to) {}

    /**
     * Another issue or PR that references this one. The Linked tab filters
     * timeline rows by {@code isPullRequest = true}.
     *
     * @param repoFullName {@code "owner/name"} of the repo the referencing item
     * lives in, or null when GitHub omits it.
     */
    public record CrossReference(
            int number,
            String title,
            String state,
            boolean isPullRequest,
            String repoFullName,
            String htmlUrl) {}
}
