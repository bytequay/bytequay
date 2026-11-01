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
package com.bytequay.core.hosting;

import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PullRequestRef;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * One entry from an account's activity feed. The latest comment is read only when asked, so a failed read cannot hide
 * the notice itself.
 */
public record ActivityNotice(PullRequestRef ref, boolean pullRequest, Reason reason, Instant updatedAt, Supplier<Optional<PrActivitySummary>> latestComment)
{
    public enum Reason
    {
        REVIEW_REQUESTED,
        MENTIONED,
        CI,
        /** Sent only because the viewer follows the repository, which does not make its requests the viewer's work. */
        FOLLOWED_REPOSITORY,
        OTHER
    }

    public ActivityNotice
    {
        requireNonNull(ref, "ref is null");
        requireNonNull(reason, "reason is null");
        requireNonNull(updatedAt, "updatedAt is null");
        requireNonNull(latestComment, "latestComment is null");
    }
}
