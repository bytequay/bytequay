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
 *
 * @param type GitHub event type, for example {@code PushEvent},
 * {@code PullRequestEvent}, {@code IssuesEvent}, {@code ReleaseEvent}, or
 * {@code CreateEvent}. The frontend uses this to pick an icon and tense the
 * message.
 * @param actor Login of the user who triggered the event.
 * @param title Pre-rendered single-line title, built server-side so the
 * renderer does not need to re-derive it from the raw payload.
 * @param htmlUrl Deep link into github.com for the event subject.
 */
public record RepoActivityItem(
        String type,
        String actor,
        String title,
        String htmlUrl,
        Instant createdAt) {}
