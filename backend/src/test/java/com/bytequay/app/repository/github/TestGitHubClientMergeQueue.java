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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubClientMergeQueue
{
    private static final String RULES_PAGE_ONE =
            "https://api.github.test/repos/owner/repo/rules/branches/main"
                    + "?per_page=100&page=1";
    private static final String RULES_PAGE_TWO =
            "https://api.github.test/repos/owner/repo/rules/branches/main"
                    + "?per_page=100&page=2";

    @Test
    void enqueueSendsExpectedHeadOid()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.query", containsString("expectedHeadOid")))
                .andExpect(jsonPath("$.variables.id").value("PR_node"))
                .andExpect(jsonPath("$.variables.head").value("head-sha"))
                .andRespond(withSuccess("""
                        {"data":{"enqueuePullRequest":{"mergeQueueEntry":{
                          "id":"MQ_entry","position":2,"state":"AWAITING_CHECKS"
                        }}}}
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.client.enqueuePullRequest(
                "pat", "PR_node", "head-sha");

        assertThat(result.queued()).isTrue();
        assertThat(result.message()).contains("position 2");
        fixture.server.verify();
    }

    @Test
    void graphQlErrorsCannotBecomeAcceptedQueueEvidence()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":null,"errors":[{
                          "type":"UNPROCESSABLE",
                          "message":"Pull request head oid has changed"
                        }]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.enqueuePullRequest(
                "pat", "PR_node", "stale-head"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("head oid has changed");
        fixture.server.verify();
    }

    @Test
    void graphQlErrorsCannotProveThatDirectMergeIsSafe()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":null,"errors":[{
                          "type":"RATE_LIMITED",
                          "message":"API rate limit exceeded"
                        }]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.fetchMergeQueueInfo(
                "pat", PullRequestRef.of("owner", "repo", 17)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RATE_LIMITED");
        fixture.server.verify();
    }

    @Test
    void activeBranchRulesWithoutMergeQueueProveDirectMergeIsSafe()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andExpect(jsonPath("$.query", containsString("baseRefName")))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{
                          "baseRefName":"main",
                          "mergeQueue":null,"mergeQueueEntry":null
                        }}}}
                        """, MediaType.APPLICATION_JSON));
        fixture.restServer.expect(requestTo(RULES_PAGE_ONE))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"type":"required_status_checks"},{"type":"pull_request"}]
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.client.fetchMergeQueueInfo(
                "pat", PullRequestRef.of("owner", "repo", 17));

        assertThat(result.queueConfigured()).isFalse();
        assertThat(result.entryState()).isNull();
        fixture.server.verify();
        fixture.restServer.verify();
    }

    @Test
    void activeBranchMergeQueueRuleProvesQueueSupport()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{
                          "baseRefName":"main",
                          "mergeQueue":null,"mergeQueueEntry":null
                        }}}}
                        """, MediaType.APPLICATION_JSON));
        fixture.restServer.expect(requestTo(RULES_PAGE_ONE))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"type":"merge_queue","parameters":{"check_response_timeout_minutes":60}}]
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.client.fetchMergeQueueInfo(
                "pat", PullRequestRef.of("owner", "repo", 17));

        assertThat(result.queueConfigured()).isTrue();
        assertThat(result.entryState()).isNull();
        fixture.server.verify();
        fixture.restServer.verify();
    }

    @Test
    void malformedQueueObjectCannotProveThatDirectMergeIsSafe()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{
                          "baseRefName":"main",
                          "mergeQueue":{"id":null},"mergeQueueEntry":null
                        }}}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.fetchMergeQueueInfo(
                "pat", PullRequestRef.of("owner", "repo", 17)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("incomplete GraphQL data");
        fixture.server.verify();
    }

    @Test
    void configuredQueueWithoutEntryProvesQueueSupport()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{
                          "baseRefName":"main",
                          "mergeQueue":{"id":"MQ_queue"},
                          "mergeQueueEntry":null
                        }}}}
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.client.fetchMergeQueueInfo(
                "pat", PullRequestRef.of("owner", "repo", 17));

        assertThat(result.queueConfigured()).isTrue();
        assertThat(result.entryState()).isNull();
        fixture.server.verify();
    }

    @Test
    void rulesetQueueEntryProvesSupportWithoutQueueObject()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{
                          "baseRefName":"main",
                          "mergeQueue":null,
                          "mergeQueueEntry":{"state":"AWAITING_CHECKS"}
                        }}}}
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.client.fetchMergeQueueInfo(
                "pat", PullRequestRef.of("owner", "repo", 17));

        assertThat(result.queueConfigured()).isTrue();
        assertThat(result.entryState()).isEqualTo("AWAITING_CHECKS");
        fixture.server.verify();
    }

    @Test
    void omittedSelectedQueueFieldCannotProveThatDirectMergeIsSafe()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{
                          "baseRefName":"main",
                          "mergeQueueEntry":null
                        }}}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.fetchMergeQueueInfo(
                "pat", PullRequestRef.of("owner", "repo", 17)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("incomplete GraphQL data");
        fixture.server.verify();
    }

    @Test
    void omittedBaseBranchCannotProveQueueCapability()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{
                          "mergeQueue":null,"mergeQueueEntry":null
                        }}}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.fetchMergeQueueInfo(
                "pat", PullRequestRef.of("owner", "repo", 17)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("incomplete GraphQL data");
        fixture.server.verify();
    }

    @Test
    void malformedQueueEntryCannotBecomeSupportedQueueEvidence()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{
                          "baseRefName":"main",
                          "mergeQueue":{"id":"MQ_queue"},
                          "mergeQueueEntry":{"state":""}
                        }}}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.fetchMergeQueueInfo(
                "pat", PullRequestRef.of("owner", "repo", 17)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("incomplete GraphQL data");
        fixture.server.verify();
    }

    @Test
    void incompleteBranchRulesCannotProveQueueCapability()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{
                          "baseRefName":"main",
                          "mergeQueue":null,"mergeQueueEntry":null
                        }}}}
                        """, MediaType.APPLICATION_JSON));
        fixture.restServer.expect(requestTo(RULES_PAGE_ONE))
                .andRespond(withSuccess("""
                        [{"type":null}]
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.fetchMergeQueueInfo(
                "pat", PullRequestRef.of("owner", "repo", 17)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("incomplete branch rules data");
        fixture.server.verify();
        fixture.restServer.verify();
    }

    @Test
    void branchRulesReadFailureCannotProveQueueCapability()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{
                          "baseRefName":"main",
                          "mergeQueue":null,"mergeQueueEntry":null
                        }}}}
                        """, MediaType.APPLICATION_JSON));
        fixture.restServer.expect(requestTo(RULES_PAGE_ONE))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> fixture.client.fetchMergeQueueInfo(
                "pat", PullRequestRef.of("owner", "repo", 17)))
                .isInstanceOf(ResponseStatusException.class);
        fixture.server.verify();
        fixture.restServer.verify();
    }

    @Test
    void mergeQueueOnLaterRulesPageProvesQueueSupport()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{
                          "baseRefName":"main",
                          "mergeQueue":null,"mergeQueueEntry":null
                        }}}}
                        """, MediaType.APPLICATION_JSON));
        fixture.restServer.expect(requestTo(RULES_PAGE_ONE))
                .andRespond(withSuccess("""
                        [{"type":"required_status_checks"}]
                        """, MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.LINK,
                                "<" + RULES_PAGE_TWO + ">; rel=\"next\", "
                                        + "<" + RULES_PAGE_TWO
                                        + ">; rel=\"last\""));
        fixture.restServer.expect(requestTo(RULES_PAGE_TWO))
                .andRespond(withSuccess("""
                        [{"type":"merge_queue"}]
                        """, MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.LINK,
                                "<" + RULES_PAGE_ONE + ">; rel=\"prev\", "
                                        + "<" + RULES_PAGE_ONE
                                        + ">; rel=\"first\""));

        var result = fixture.client.fetchMergeQueueInfo(
                "pat", PullRequestRef.of("owner", "repo", 17));

        assertThat(result.queueConfigured()).isTrue();
        fixture.server.verify();
        fixture.restServer.verify();
    }

    @Test
    void incompleteRulesPaginationCannotProveQueueCapability()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{
                          "baseRefName":"main",
                          "mergeQueue":null,"mergeQueueEntry":null
                        }}}}
                        """, MediaType.APPLICATION_JSON));
        fixture.restServer.expect(requestTo(RULES_PAGE_ONE))
                .andRespond(withSuccess("""
                        [{"type":"required_status_checks"}]
                        """, MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.LINK,
                                "<" + RULES_PAGE_TWO + ">; rel=\"next\""));
        fixture.restServer.expect(requestTo(RULES_PAGE_TWO))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> fixture.client.fetchMergeQueueInfo(
                "pat", PullRequestRef.of("owner", "repo", 17)))
                .isInstanceOf(ResponseStatusException.class);
        fixture.server.verify();
        fixture.restServer.verify();
    }

    @Test
    void inconsistentRulesPaginationCannotProveQueueCapability()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{
                          "baseRefName":"main",
                          "mergeQueue":null,"mergeQueueEntry":null
                        }}}}
                        """, MediaType.APPLICATION_JSON));
        fixture.restServer.expect(requestTo(RULES_PAGE_ONE))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.LINK,
                                "<" + RULES_PAGE_TWO.replace("page=2", "page=3")
                                        + ">; rel=\"next\""));

        assertThatThrownBy(() -> fixture.client.fetchMergeQueueInfo(
                "pat", PullRequestRef.of("owner", "repo", 17)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("incomplete branch rules data");
        fixture.server.verify();
        fixture.restServer.verify();
    }

    private static Fixture fixture()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer restServer = MockRestServiceServer
                .bindTo(restBuilder).build();
        RestClient.Builder graphBuilder = RestClient.builder()
                .baseUrl("https://api.github.test/graphql");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(graphBuilder).build();
        GitHubClient client = new GitHubClient(
                restBuilder.build(),
                graphBuilder.build());
        return new Fixture(client, server, restServer);
    }

    private record Fixture(
            GitHubClient client,
            MockRestServiceServer server,
            MockRestServiceServer restServer) {}
}
