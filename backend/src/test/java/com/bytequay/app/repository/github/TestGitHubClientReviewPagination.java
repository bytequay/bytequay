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

import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.PullRequestReview;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubClientReviewPagination
{
    @Test
    void listsEveryReviewPageForRecoveryEvidence()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubPullRequestReadClient client = new GitHubPullRequestReadClient(
                restBuilder.build(),
                RestClient.builder().baseUrl("https://graphql.test").build());
        String endpoint = "https://api.github.test/repos/acme/widget"
                + "/pulls/17/reviews";
        server.expect(requestTo(endpoint + "?per_page=100&page=1"))
                .andRespond(withSuccess(reviewPage(1, 100),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(endpoint + "?per_page=100&page=2"))
                .andRespond(withSuccess(reviewPage(101, 2),
                        MediaType.APPLICATION_JSON));

        List<PullRequestReview> reviews = client.listReviews(
                "pat", PullRequestRef.of("acme", "widget", 17));

        assertThat(reviews).hasSize(102);
        assertThat(reviews.getFirst().id()).isEqualTo(1);
        assertThat(reviews.getLast().id()).isEqualTo(102);
        server.verify();
    }

    private static String reviewPage(int first, int count)
    {
        return IntStream.range(first, first + count)
                .mapToObj(id -> """
                        {"id":%d,"user":{"login":"alice"},
                         "body":"body-%d","state":"COMMENTED",
                         "commit_id":"head-1",
                         "submitted_at":"2026-07-29T00:00:00Z",
                         "html_url":"https://github.test/review/%d"}
                        """.formatted(id, id, id))
                .collect(joining(",", "[", "]"));
    }
}
