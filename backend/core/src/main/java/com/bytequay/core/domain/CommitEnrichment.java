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

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Durable facts that Git does not contain for one repository commit. */
public record CommitEnrichment(
        long repositoryId,
        String sha,
        Optional<String> authorLogin,
        Optional<String> avatarKey,
        Optional<String> committerLogin,
        Optional<String> committerAvatarKey,
        Optional<Integer> pullRequestNumber,
        Optional<String> pullRequestTitle,
        Optional<String> pullRequestUrl,
        Optional<Instant> pullRequestMergedAt,
        Optional<Instant> avatarCheckedAt,
        Optional<Instant> associationCheckedAt,
        Instant lastSeenAt)
{
    private static final Duration NEGATIVE_RETRY_INTERVAL = Duration.ofDays(1);
    private static final Duration NEGATIVE_RETRY_WINDOW = Duration.ofDays(7);

    public CommitEnrichment
    {
        checkArgument(repositoryId > 0, "repositoryId must be positive");
        checkText(sha, "sha");
        requireNonNull(pullRequestNumber, "pullRequestNumber is null");
        requireNonNull(pullRequestMergedAt, "pullRequestMergedAt is null");
        requireNonNull(avatarCheckedAt, "avatarCheckedAt is null");
        requireNonNull(associationCheckedAt, "associationCheckedAt is null");
        requireNonNull(lastSeenAt, "lastSeenAt is null");
        checkOptionalText(authorLogin, "authorLogin");
        checkOptionalText(avatarKey, "avatarKey");
        checkOptionalText(committerLogin, "committerLogin");
        checkOptionalText(committerAvatarKey, "committerAvatarKey");
        pullRequestNumber.ifPresent(value -> checkArgument(value > 0, "pullRequestNumber must be positive"));
        checkOptionalText(pullRequestTitle, "pullRequestTitle");
        checkOptionalText(pullRequestUrl, "pullRequestUrl");
        checkArgument(pullRequestNumber.isPresent() || pullRequestTitle.isEmpty(), "pullRequestTitle requires pullRequestNumber");
        checkArgument(pullRequestNumber.isPresent() || pullRequestUrl.isEmpty(), "pullRequestUrl requires pullRequestNumber");
        checkArgument(pullRequestNumber.isPresent() || pullRequestMergedAt.isEmpty(), "pullRequestMergedAt requires pullRequestNumber");
    }

    /**
     * Shared retry policy for commit-to-PR associations. Positive mappings remain reusable, unchecked
     * commits are due, and checked negatives retry daily only during the commit's first seven days.
     * A missing or malformed commit time retains the existing conservative recent-commit fallback.
     */
    public static boolean associationDue(String committedAt, Optional<CommitEnrichment> existing, Instant now)
    {
        requireNonNull(existing, "existing is null");
        requireNonNull(now, "now is null");
        Optional<Instant> checkedAt = existing.flatMap(CommitEnrichment::associationCheckedAt);
        if (existing.flatMap(CommitEnrichment::pullRequestNumber).isPresent()) {
            // Mappings saved before the merge time was kept fetch it again, at most daily.
            return existing.flatMap(CommitEnrichment::pullRequestMergedAt).isEmpty()
                    && checkedAt.filter(value -> value.isAfter(now.minus(NEGATIVE_RETRY_INTERVAL))).isEmpty();
        }
        if (checkedAt.isEmpty()) {
            return true;
        }
        Instant committed;
        try {
            committed = committedAt == null ? now : Instant.parse(committedAt);
        }
        catch (DateTimeParseException ignored) {
            committed = now;
        }
        return !committed.isBefore(now.minus(NEGATIVE_RETRY_WINDOW))
                && !checkedAt.orElseThrow().isAfter(now.minus(NEGATIVE_RETRY_INTERVAL));
    }
}
