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

import com.bytequay.core.domain.DoneActivity;
import com.bytequay.core.domain.DoneItem;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubAccountClientDone
        extends AbstractGitHubClientTest
{
    private static final ZonedDateTime FROM = ZonedDateTime.of(2026, 11, 2, 0, 0, 0, 0, ZoneOffset.ofHours(8));
    private static final ZonedDateTime TO = FROM.plusDays(1).minusSeconds(1);

    @Test
    void sortsMergesReviewsAndIssuesIntoOneRowEach()
    {
        graphServer.expect(requestTo("https://api.github.test/graphql"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.variables.authored").value("is:pr is:merged author:me merged:2026-11-02T00:00:00+08:00..2026-11-02T23:59:59+08:00"))
                .andExpect(jsonPath("$.variables.from").value("2026-11-01T16:00:00Z"))
                .andRespond(withSuccess("""
                        {"data": {
                          "authored": {"nodes": [
                            {"number": 1, "title": "Mine", "mergedAt": "2026-11-02T01:00:00Z", "author": {"login": "Me"}, "mergedBy": {"login": "lead"},
                             "repository": {"nameWithOwner": "acme/app"}},
                            {}
                          ]},
                          "reviewed": {"nodes": [
                            {"number": 1, "title": "Mine", "mergedAt": "2026-11-02T01:00:00Z", "author": {"login": "me"}, "mergedBy": {"login": "lead"},
                             "repository": {"nameWithOwner": "acme/app"}},
                            {"number": 2, "title": "Theirs I merged", "mergedAt": "2026-11-02T02:00:00Z", "author": {"login": "other"}, "mergedBy": {"login": "me"},
                             "repository": {"nameWithOwner": "acme/app"}},
                            {"number": 3, "title": "Merged by someone else", "mergedAt": "2026-11-02T03:00:00Z", "author": {"login": "other"},
                             "mergedBy": {"login": "lead"}, "repository": {"nameWithOwner": "acme/app"}}
                          ]},
                          "issues": {"nodes": [
                            {"number": 9, "title": "Closed bug", "closedAt": "2026-11-02T04:00:00Z", "repository": {"nameWithOwner": "acme/lib"}}
                          ]},
                          "user": {"contributionsCollection": {"pullRequestReviewContributions": {"nodes": [
                            {"occurredAt": "2026-11-02T05:00:00Z", "pullRequestReview": {"state": "APPROVED"},
                             "pullRequest": {"number": 4, "title": "Reviewed", "author": {"login": "other"}, "repository": {"nameWithOwner": "acme/app"}}},
                            {"occurredAt": "2026-11-02T06:00:00Z", "pullRequestReview": {"state": "COMMENTED"},
                             "pullRequest": {"number": 4, "title": "Reviewed", "author": {"login": "other"}, "repository": {"nameWithOwner": "acme/app"}}},
                            {"occurredAt": "2026-11-02T01:30:00Z", "pullRequestReview": {"state": "APPROVED"},
                             "pullRequest": {"number": 2, "title": "Theirs I merged", "author": {"login": "other"}, "repository": {"nameWithOwner": "acme/app"}}},
                            {"occurredAt": "2026-11-02T00:30:00Z", "pullRequestReview": {"state": "COMMENTED"},
                             "pullRequest": {"number": 1, "title": "Mine", "author": {"login": "me"}, "repository": {"nameWithOwner": "acme/app"}}}
                          ]}}}
                        }}
                        """, MediaType.APPLICATION_JSON));

        DoneActivity done = new GitHubAccountClient(restClient, graphQlClient).fetchDone("pat", "me", FROM, TO);

        assertThat(done.complete()).isTrue();
        assertThat(done.items()).containsExactly(
                DoneItem.github(DoneItem.Kind.APPROVED, "Reviewed", "acme/app", 4, Instant.parse("2026-11-02T05:00:00Z")),
                DoneItem.github(DoneItem.Kind.ISSUE_CLOSED, "Closed bug", "acme/lib", 9, Instant.parse("2026-11-02T04:00:00Z")),
                DoneItem.github(DoneItem.Kind.MERGED_BY_YOU, "Theirs I merged", "acme/app", 2, Instant.parse("2026-11-02T02:00:00Z")),
                DoneItem.github(DoneItem.Kind.MERGED, "Mine", "acme/app", 1, Instant.parse("2026-11-02T01:00:00Z")));
    }

    @Test
    void partialAnswerKeepsWhatCameBack()
    {
        graphServer.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data": {
                          "authored": {"nodes": [
                            {"number": 1, "title": "Mine", "mergedAt": "2026-11-02T01:00:00Z", "author": {"login": "me"}, "repository": {"nameWithOwner": "acme/app"}}
                          ]},
                          "reviewed": {"nodes": []}, "issues": {"nodes": []}, "user": null},
                         "errors": [{"type": "FORBIDDEN", "message": "Resource not accessible by personal access token"}]}
                        """, MediaType.APPLICATION_JSON));

        DoneActivity done = new GitHubAccountClient(restClient, graphQlClient).fetchDone("pat", "me", FROM, TO);

        assertThat(done.complete()).isFalse();
        assertThat(done.items()).extracting(DoneItem::kind).containsExactly(DoneItem.Kind.MERGED);
    }
}
