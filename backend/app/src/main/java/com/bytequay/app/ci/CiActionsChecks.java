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
package com.bytequay.app.ci;

import com.bytequay.core.domain.PullRequestRef;
import com.google.common.collect.ImmutableSet;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

final class CiActionsChecks
{
    private static final Set<String> ACTIONABLE_CONCLUSIONS = ImmutableSet.of(
            "action_required",
            "cancelled",
            "failure",
            "stale",
            "startup_failure",
            "timed_out");
    private static final Set<String> PASSING_CONCLUSIONS = ImmutableSet.of("neutral", "skipped", "success");

    private CiActionsChecks() {}

    static PullRequestRef checkPullRequest(PullRequestRef pullRequest)
    {
        requireNonNull(pullRequest, "pullRequest is null");
        checkText(pullRequest.owner(), "repository owner", 100);
        checkText(pullRequest.repo(), "repository name", 100);
        return pullRequest;
    }

    static String checkText(String value, String name, int maximumLength)
    {
        requireNonNull(value, name + " is null");
        checkArgument(!value.isBlank() && value.length() <= maximumLength, name + " must contain between 1 and " + maximumLength + " characters");
        return value;
    }

    static Optional<String> checkOptionalText(Optional<String> value, String name, int maximumLength)
    {
        requireNonNull(value, name + " is null");
        value.ifPresent(text -> checkText(text, name, maximumLength));
        return value;
    }

    static String checkSha(String value, String name)
    {
        requireNonNull(value, name + " is null");
        checkArgument(value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})"), name + " is not a full lowercase Git SHA");
        return value;
    }

    static String checkUrl(String value, String name)
    {
        checkText(value, name, 2048);
        URI uri = URI.create(value);
        checkArgument(uri.isAbsolute() && uri.getHost() != null
                        && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())),
                name + " is not an absolute HTTP URL");
        return value;
    }

    static String normalizeState(String value, String name)
    {
        return checkText(value, name, 100).toLowerCase(Locale.ROOT);
    }

    static Optional<String> normalizeConclusion(Optional<String> value)
    {
        checkOptionalText(value, "conclusion", 100);
        return value.map(conclusion -> conclusion.toLowerCase(Locale.ROOT));
    }

    static boolean isActionableConclusion(Optional<String> conclusion)
    {
        return conclusion.filter(ACTIONABLE_CONCLUSIONS::contains).isPresent();
    }

    static boolean isPassingConclusion(Optional<String> conclusion)
    {
        return conclusion.filter(PASSING_CONCLUSIONS::contains).isPresent();
    }
}
