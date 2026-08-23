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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Durable facts that Git does not contain for one repository commit. */
public record CommitEnrichment(
        long repositoryId,
        String sha,
        Optional<String> avatarKey,
        Optional<Integer> pullRequestNumber,
        Optional<String> pullRequestTitle,
        Optional<String> pullRequestUrl,
        Optional<Instant> avatarCheckedAt,
        Optional<Instant> associationCheckedAt,
        Instant lastSeenAt)
{
    public CommitEnrichment
    {
        checkArgument(repositoryId > 0, "repositoryId must be positive");
        requireNonNull(sha, "sha is null");
        checkArgument(!sha.isBlank(), "sha is blank");
        requireNonNull(avatarKey, "avatarKey is null");
        requireNonNull(pullRequestNumber, "pullRequestNumber is null");
        requireNonNull(pullRequestTitle, "pullRequestTitle is null");
        requireNonNull(pullRequestUrl, "pullRequestUrl is null");
        requireNonNull(avatarCheckedAt, "avatarCheckedAt is null");
        requireNonNull(associationCheckedAt, "associationCheckedAt is null");
        requireNonNull(lastSeenAt, "lastSeenAt is null");
        avatarKey.ifPresent(value -> checkArgument(!value.isBlank(), "avatarKey is blank"));
        pullRequestNumber.ifPresent(value -> checkArgument(value > 0, "pullRequestNumber must be positive"));
        pullRequestTitle.ifPresent(value -> checkArgument(!value.isBlank(), "pullRequestTitle is blank"));
        pullRequestUrl.ifPresent(value -> checkArgument(!value.isBlank(), "pullRequestUrl is blank"));
        checkArgument(pullRequestNumber.isPresent() || pullRequestTitle.isEmpty(),
                "pullRequestTitle requires pullRequestNumber");
        checkArgument(pullRequestNumber.isPresent() || pullRequestUrl.isEmpty(),
                "pullRequestUrl requires pullRequestNumber");
    }
}
