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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * A single review submission: who reviewed, what state they left (APPROVED,
 * CHANGES_REQUESTED, etc.), and when they submitted it.
 *
 * <p>{@code submittedAt} may be null for cached reviews captured before
 * V53 added the column — older rows simply don't carry a timestamp.
 * Callers that need to filter by time window must treat null as
 * "unknown" and skip rather than include.
 */
public record PrReviewState(
        Long githubId,
        String login,
        String avatarUrl,
        boolean bot,
        String state,
        Instant submittedAt,
        String body,
        String htmlUrl,
        String commitId)
{
    public PrReviewState
    {
        requireNonNull(login, "login is null");
        requireNonNull(state, "state is null");
    }

    /** Each reviewer's latest review that is more than a comment: what GitHub's review decision counts. */
    public static Collection<PrReviewState> latestVerdicts(List<PrReviewState> reviews)
    {
        Map<String, PrReviewState> verdicts = new HashMap<>();
        reviews.stream()
                .sorted(Comparator.comparing(review -> Optional.ofNullable(review.submittedAt()).orElse(Instant.EPOCH)))
                .filter(review -> !"COMMENTED".equals(review.state()) && !"PENDING".equals(review.state()))
                .forEach(review -> verdicts.put(review.login().toLowerCase(Locale.ROOT), review));
        return verdicts.values();
    }
}
