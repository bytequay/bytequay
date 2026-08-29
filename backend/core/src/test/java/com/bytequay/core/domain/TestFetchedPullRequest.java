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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestFetchedPullRequest
{
    @Test
    void derivesTheViewerOriginFromGitHubFacts()
    {
        assertThat(pullRequest("Alice", ImmutableList.of("alice")).withViewerOrigin("ALICE").origin())
                .isEqualTo(PullRequest.Origin.AUTHORED);
        assertThat(pullRequest("bob", ImmutableList.of("Alice")).withViewerOrigin("alice").origin())
                .isEqualTo(PullRequest.Origin.REVIEW_REQUESTED);
        assertThat(pullRequest("bob", ImmutableList.of("carol")).withViewerOrigin("alice").origin())
                .isEqualTo(PullRequest.Origin.OTHER);
    }

    private static FetchedPullRequest pullRequest(String author, List<String> requestedReviewers)
    {
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        return new FetchedPullRequest(
                1L, "owner/repo", 7, "Title", author, "https://github.com/owner/repo/pull/7",
                now, now, PullRequest.Origin.AUTHORED, ImmutableList.of(), ImmutableMap.of(), false,
                "open", null, null, requestedReviewers, null, null, "feature");
    }
}
