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
import java.util.List;

/**
 * Aggregate fetched for the in-app Issue detail page. Mirrors
 * {@link PullRequestDetail} in shape but strips out everything that
 * doesn't apply to an issue: no diff, no CI, no review threads, no
 * merge bar. Body + comments + the right-rail facts (labels with
 * colour, assignees, milestone) cover the read-only flow that ships
 * first; linked PRs and the activity tab still defer to I3b/I4.
 *
 * @param body Markdown body posted by the author. May be null or blank when
 * the issue was opened with no description.
 * @param state GitHub issue state: {@code "open"} or {@code "closed"}.
 * @param closedAt null while the issue is open.
 * @param milestone null when the issue is not milestoned.
 * @param timeline structural timeline events feeding the Activity and Linked
 * tabs. Always non-null.
 * @param subscribed true iff the viewer has explicitly subscribed to the issue
 * via {@code PUT /issues/{n}/subscription}.
 */
public record IssueDetail(
        long id,
        int number,
        String title,
        String body,
        String author,
        String authorAvatarUrl,
        String state,
        String htmlUrl,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt,
        List<Label> labels,
        List<Assignee> assignees,
        Milestone milestone,
        List<Comment> comments,
        List<IssueTimelineEvent> timeline,
        boolean subscribed)
{
    public record Label(String name, String color) {}

    public record Assignee(String login, String avatarUrl) {}

    public record Milestone(String title, String state) {}

    /**
     * One comment in the conversation thread.
     *
     * @param reactions aggregated reaction counts. Never null; empty rows use
     * {@link Reactions#EMPTY} so callers do not need to null-check.
     */
    public record Comment(
            long id,
            String author,
            String authorAvatarUrl,
            String body,
            Instant createdAt,
            Reactions reactions) {}
}
