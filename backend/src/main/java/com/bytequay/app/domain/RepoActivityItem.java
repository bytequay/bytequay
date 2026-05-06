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
 * One item in a repo's recent activity feed (right pane on the repo
 * detail page). Normalized from GitHub's {@code /repos/{owner}/{repo}/events}
 * payload: many event types (PushEvent, PullRequestEvent, IssuesEvent,
 * ReleaseEvent…) collapse to a single shape so the frontend can render
 * a uniform timeline.
 */
public record RepoActivityItem(
        /** GitHub event type — "PushEvent", "PullRequestEvent",
         *  "IssuesEvent", "ReleaseEvent", "CreateEvent", etc. The
         *  frontend uses this to pick an icon and tense the message. */
        String type,
        /** Login of the user who triggered the event. */
        String actor,
        /** Pre-rendered single-line title — e.g. "#29289 Make Constraint
         *  serializable opened" or "trino-435 released". Built server-
         *  side so the renderer doesn't need to re-derive from the
         *  raw payload. */
        String title,
        /** Deep link into github.com for the event subject. */
        String htmlUrl,
        Instant createdAt) {}
