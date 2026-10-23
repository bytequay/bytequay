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
package com.bytequay.app.repository.github;

import com.bytequay.core.domain.PullRequestRef;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** A changed GitHub inbox thread; its reason is a subscription relationship, not an event verb. */
public record GitHubNotificationRef(String id, PullRequestRef ref, boolean pullRequest, String title, String reason, Instant updatedAt, Optional<String> commentUrl)
{
    public GitHubNotificationRef
    {
        requireNonNull(id, "id is null");
        requireNonNull(ref, "ref is null");
        requireNonNull(title, "title is null");
        requireNonNull(reason, "reason is null");
        requireNonNull(updatedAt, "updatedAt is null");
        requireNonNull(commentUrl, "commentUrl is null");
    }

    public boolean reviewRequested()
    {
        return "review_requested".equals(reason);
    }

    /** GitHub files threads it sent only because the viewer watches the repository as "subscribed". */
    public boolean repositoryWatch()
    {
        return "subscribed".equals(reason);
    }
}
