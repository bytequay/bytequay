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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.Consumer;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubDeliveryChecks
{
    private static final PullRequestRef PR = PullRequestRef.of("owner", "repo", 7);
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String RULES = "https://api.github.test/repos/owner/repo/rules/branches/main?per_page=100&page=1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void verifiesRequiredClassicAndRulesetChecksOnTheExactCurrentHead()
    {
        Fixture fixture = fixture();
        String checks = checkRun("build", "COMPLETED", "SUCCESS", 123, true) + "," + status("external", "SUCCESS");
        ObjectNode response = response(checks, 2, false);
        protection(response).put("requiresStatusChecks", true).set("requiredStatusChecks", json("[{\"context\":\"build\",\"app\":{\"databaseId\":123}}]"));
        fixture.graph().expect(requestTo("https://api.github.test/graphql"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer token"))
                .andExpect(content().string(containsString("isRequired(pullRequestNumber:$number)")))
                .andExpect(content().string(containsString("branchProtectionRule")))
                .andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
        fixture.api().expect(requestTo(RULES)).andRespond(withSuccess(requiredRule("external", "null"), MediaType.APPLICATION_JSON));

        GitHubDeliveryChecks result = fixture.client().fetchDeliveryChecks("token", PR);

        assertThat(result.green()).isTrue();
        assertThat(result.nodeId()).isEqualTo("PR_7");
        assertThat(result.pullRequest().headSha()).isEqualTo(HEAD);
        assertThat(result.pullRequest().baseSha()).isEqualTo(BASE);
        assertThat(result.pullRequest().draft()).isTrue();
        fixture.verify();
    }

    @Test
    void waitsForMissingPendingAndUnknownRequiredResults()
    {
        for (String state : ImmutableList.of("PENDING", "EXPECTED", "UNKNOWN")) {
            Fixture fixture = fixture();
            fixture.expect(response(status("external", state), 1, false), requiredRule("external", "null"));
            GitHubDeliveryChecks result = fixture.client().fetchDeliveryChecks("token", PR);
            assertThat(result.green()).isFalse();
            assertThat(result.waiting()).anySatisfy(value -> assertThat(value).contains("external"));
            assertThat(result.failures()).isEmpty();
            fixture.verify();
        }
        Fixture fixture = fixture();
        fixture.expect(response(checkRun("build", "COMPLETED", "SUCCESS", 123, false), 1, false), requiredRule("missing", "null"));
        assertThat(fixture.client().fetchDeliveryChecks("token", PR).waiting()).anySatisfy(value -> assertThat(value).contains("missing", "draft"));
        fixture.verify();
    }

    @Test
    void reportsThirdPartyFailuresWithoutInventingAnActionsRepair()
    {
        for (String state : ImmutableList.of("FAILURE", "ERROR")) {
            Fixture fixture = fixture();
            fixture.expect(response(status("external", state), 1, false), "[]");
            GitHubDeliveryChecks result = fixture.client().fetchDeliveryChecks("token", PR);
            assertThat(result.green()).isFalse();
            assertThat(result.failures()).singleElement().asString().contains("External CI check external", "cannot repair");
            fixture.verify();
        }
    }

    @Test
    void cannotSubstituteAnotherAppOrAnUnprovenLegacyStatusForARequiredApp()
    {
        for (String checks : ImmutableList.of(checkRun("build", "COMPLETED", "SUCCESS", 456, true),
                checkRun("build", "COMPLETED", "SUCCESS", 123, false), status("build", "SUCCESS"))) {
            Fixture fixture = fixture();
            fixture.expect(response(checks, 1, false), requiredRule("build", "123"));
            GitHubDeliveryChecks result = fixture.client().fetchDeliveryChecks("token", PR);
            assertThat(result.green()).isFalse();
            assertThat(result.waiting()).anySatisfy(value -> assertThat(value).contains("required source"));
            fixture.verify();
        }
    }

    @Test
    void completedCheckRunsAcceptSuccessNeutralAndSkippedOnly()
    {
        for (String conclusion : ImmutableList.of("SUCCESS", "NEUTRAL", "SKIPPED")) {
            Fixture fixture = fixture();
            fixture.expect(response(checkRun("build", "COMPLETED", conclusion, 123, false), 1, false), "[]");
            assertThat(fixture.client().fetchDeliveryChecks("token", PR).green()).isTrue();
            fixture.verify();
        }
        Fixture fixture = fixture();
        fixture.expect(response(checkRun("build", "QUEUED", "SUCCESS", 123, false), 1, false), "[]");
        assertThat(fixture.client().fetchDeliveryChecks("token", PR).green()).isFalse();
        fixture.verify();
    }

    @Test
    void readsAllCheckPagesAndFindsAFailureBeyondTheFirstPage()
    {
        Fixture fixture = fixture();
        fixture.expect(response(checkRun("build", "COMPLETED", "SUCCESS", 123, false), 2, true), "[]");
        fixture.graph().expect(requestTo("https://api.github.test/graphql"))
                .andExpect(content().string(containsString("\"cursor\":\"next\"")))
                .andRespond(withSuccess(response(status("external", "FAILURE"), 2, false).toString(), MediaType.APPLICATION_JSON));

        assertThat(fixture.client().fetchDeliveryChecks("token", PR).failures()).singleElement().asString().contains("external");
        fixture.verify();
    }

    @Test
    void doesNotCombineChecksFromDifferentHeadOrBaseRevisions()
    {
        for (String field : ImmutableList.of("headRefOid", "baseRefOid")) {
            Fixture fixture = fixture();
            fixture.expect(response(checkRun("build", "COMPLETED", "SUCCESS", 123, false), 2, true), "[]");
            ObjectNode changed = response(status("external", "SUCCESS"), 2, false);
            pr(changed).put(field, "c".repeat(40));
            ((ObjectNode) pr(changed).path(field.equals("headRefOid") ? "headRef" : "baseRef").path("target")).put("oid", "c".repeat(40));
            fixture.graph().expect(requestTo("https://api.github.test/graphql")).andRespond(withSuccess(changed.toString(), MediaType.APPLICATION_JSON));
            GitHubDeliveryChecks result = fixture.client().fetchDeliveryChecks("token", PR);
            assertThat(result.green()).isFalse();
            assertThat(result.waiting()).anySatisfy(value -> assertThat(value).contains("revision changed"));
            fixture.verify();
        }
    }

    @Test
    void partialOrMalformedEvidenceNeverBecomesGreen()
    {
        for (Consumer<ObjectNode> change : ImmutableList.<Consumer<ObjectNode>>of(
                value -> pr(value).remove("statusCheckRollup"),
                value -> ((ObjectNode) pr(value).path("statusCheckRollup").path("commit")).put("oid", "c".repeat(40)),
                value -> contexts(value).put("totalCount", 2),
                value -> contexts(value).remove("nodes"),
                value -> contexts(value).remove("pageInfo"),
                value -> ((ObjectNode) contexts(value).path("nodes").get(0)).remove("isRequired"),
                value -> ((ObjectNode) contexts(value).path("nodes").get(0)).remove("conclusion"),
                value -> ((ObjectNode) pr(value).path("baseRef")).remove("branchProtectionRule"),
                value -> protection(value).put("requiresStatusChecks", true).remove("requiredStatusChecks"))) {
            Fixture fixture = fixture();
            ObjectNode response = response(checkRun("build", "COMPLETED", "SUCCESS", 123, false), 1, false);
            change.accept(response);
            fixture.graph().expect(requestTo("https://api.github.test/graphql")).andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
            boolean validProtection = pr(response).path("baseRef").has("branchProtectionRule") && protection(response).has("requiredStatusChecks");
            if (validProtection) {
                fixture.api().expect(requestTo(RULES)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
            }
            GitHubDeliveryChecks result = fixture.client().fetchDeliveryChecks("token", PR);
            assertThat(result.green()).isFalse();
            assertThat(result.waiting()).isNotEmpty();
            fixture.verify();
        }
    }

    @Test
    void emptyResultsAndUnsupportedRequiredWorkflowsRemainWaiting()
    {
        Fixture fixture = fixture();
        fixture.expect(response("", 0, false), "[{\"type\":\"workflows\"}]");
        GitHubDeliveryChecks result = fixture.client().fetchDeliveryChecks("token", PR);
        assertThat(result.green()).isFalse();
        assertThat(result.waiting()).anySatisfy(value -> assertThat(value).contains("not supported"));
        assertThat(result.waiting()).anySatisfy(value -> assertThat(value).contains("No current-head", "draft"));
        fixture.verify();
    }

    @Test
    void headSuccessCannotBypassSeparateMergeChecks()
    {
        for (String state : ImmutableList.of("SUCCESS", "PENDING", "UNKNOWN", "FAILURE", "ERROR")) {
            Fixture fixture = fixture();
            ObjectNode response = response(checkRun("build", "COMPLETED", "SUCCESS", 123, false), 1, false);
            pr(response).set("potentialMergeCommit", json("""
                    {"oid":"%s","statusCheckRollup":{"commit":{"oid":"%s"},"state":"%s","contexts":{"totalCount":1}}}
                    """.formatted("c".repeat(40), "c".repeat(40), state)));
            fixture.expect(response, "[]");
            GitHubDeliveryChecks result = fixture.client().fetchDeliveryChecks("token", PR);
            assertThat(result.green()).isFalse();
            assertThat(ImmutableList.<String>builder().addAll(result.waiting()).addAll(result.failures()).build())
                    .anySatisfy(value -> assertThat(value).contains("Test merge commit", state));
            fixture.verify();
        }
    }

    @Test
    void absentOrUnknownMergeEvidenceCannotBecomeGreen()
    {
        for (String merge : ImmutableList.of("null", "{}", "{\"oid\":\"merge\",\"statusCheckRollup\":{}}")) {
            Fixture fixture = fixture();
            ObjectNode response = response(checkRun("build", "COMPLETED", "SUCCESS", 123, false), 1, false);
            pr(response).set("potentialMergeCommit", json(merge));
            fixture.expect(response, "[]");
            assertThat(fixture.client().fetchDeliveryChecks("token", PR).green()).isFalse();
            fixture.verify();
        }
    }

    @Test
    void readsAllActiveRulePagesBeforeDeclaringSuccess()
    {
        Fixture fixture = fixture();
        fixture.expect(response(checkRun("build", "COMPLETED", "SUCCESS", 123, false), 1, false),
                IntStream.range(0, 100).mapToObj(value -> "{\"type\":\"deletion\"}").collect(joining(",", "[", "]")));
        fixture.api().expect(requestTo(RULES.replace("&page=1", "&page=2"))).andRespond(withSuccess(requiredRule("missing", "null"), MediaType.APPLICATION_JSON));
        assertThat(fixture.client().fetchDeliveryChecks("token", PR).waiting()).anySatisfy(value -> assertThat(value).contains("missing"));
        fixture.verify();
    }

    @Test
    void deniedOrMalformedProtectionReadsNeverMeanNoRequirements()
    {
        Fixture denied = fixture();
        denied.graph().expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("{\"errors\":[{\"message\":\"not authorized\"}],\"data\":null}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> denied.client().fetchDeliveryChecks("token", PR)).isInstanceOf(ResponseStatusException.class);
        denied.verify();

        Fixture unavailable = fixture();
        unavailable.graph().expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess(response(status("external", "SUCCESS"), 1, false).toString(), MediaType.APPLICATION_JSON));
        unavailable.api().expect(requestTo(RULES)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> unavailable.client().fetchDeliveryChecks("token", PR)).isInstanceOf(ResponseStatusException.class);
        unavailable.verify();

        Fixture malformed = fixture();
        malformed.expect(response(status("external", "SUCCESS"), 1, false), "{}");
        assertThat(malformed.client().fetchDeliveryChecks("token", PR).green()).isFalse();
        malformed.verify();

        Fixture unknown = fixture();
        unknown.expect(response(status("external", "SUCCESS"), 1, false), "[{\"type\":\"future_check_policy\"}]");
        assertThat(unknown.client().fetchDeliveryChecks("token", PR).waiting()).anySatisfy(value -> assertThat(value).contains("Unknown branch rule"));
        unknown.verify();
    }

    @Test
    void malformedGraphQlErrorsCannotBeIgnoredBesideValidData()
    {
        Fixture fixture = fixture();
        ObjectNode response = response(status("external", "SUCCESS"), 1, false);
        response.set("errors", json("{}"));
        fixture.graph().expect(requestTo("https://api.github.test/graphql")).andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> fixture.client().fetchDeliveryChecks("token", PR)).isInstanceOf(ResponseStatusException.class);
        fixture.verify();
    }

    private static ObjectNode response(String checks, int total, boolean next)
    {
        return (ObjectNode) json("""
                {"data":{"repository":{"pullRequest":{
                  "id":"PR_7","number":7,"url":"https://github.com/owner/repo/pull/7","state":"OPEN","isDraft":true,"author":{"login":"owner"},
                  "headRefName":"feature","headRefOid":"%s","baseRefName":"main","baseRefOid":"%s",
                  "headRepository":{"nameWithOwner":"owner/repo"},"baseRepository":{"nameWithOwner":"owner/repo"},
                  "headRef":{"target":{"oid":"%s"}},"baseRef":{"target":{"oid":"%s"},
                    "branchProtectionRule":{"requiresStatusChecks":false,"requiredStatusChecks":[]}},
                  "potentialMergeCommit":{"oid":"cccccccccccccccccccccccccccccccccccccccc","statusCheckRollup":null},
                  "statusCheckRollup":{"commit":{"oid":"%s"},"state":"SUCCESS","contexts":{
                    "totalCount":%s,"pageInfo":{"hasNextPage":%s,"endCursor":"next"},"nodes":[%s]}}
                }}}}
                """.formatted(HEAD, BASE, HEAD, BASE, HEAD, total, next, checks));
    }

    private static String checkRun(String name, String state, String conclusion, long app, boolean required)
    {
        return """
                {"__typename":"CheckRun","id":"check-%s","name":"%s","status":"%s","conclusion":"%s","isRequired":%s,
                 "checkSuite":{"app":{"databaseId":%s,"slug":"github-actions"}}}
                """.formatted(name, name, state, conclusion, required, app);
    }

    private static String status(String name, String state)
    {
        return "{\"__typename\":\"StatusContext\",\"id\":\"status-" + name + "\",\"context\":\"" + name + "\",\"state\":\"" + state + "\",\"isRequired\":true}";
    }

    private static String requiredRule(String context, String app)
    {
        return "[{\"type\":\"required_status_checks\",\"parameters\":{\"required_status_checks\":[{\"context\":\"" + context + "\",\"integration_id\":" + app + "}]}}]";
    }

    private static JsonNode json(String value)
    {
        try {
            return MAPPER.readTree(value);
        }
        catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static ObjectNode pr(ObjectNode response)
    {
        return (ObjectNode) response.path("data").path("repository").path("pullRequest");
    }

    private static ObjectNode protection(ObjectNode response)
    {
        return (ObjectNode) pr(response).path("baseRef").path("branchProtectionRule");
    }

    private static ObjectNode contexts(ObjectNode response)
    {
        return (ObjectNode) pr(response).path("statusCheckRollup").path("contexts");
    }

    private static Fixture fixture()
    {
        RestClient.Builder api = RestClient.builder().baseUrl("https://api.github.test");
        RestClient.Builder graph = RestClient.builder().baseUrl("https://api.github.test/graphql");
        MockRestServiceServer apiServer = MockRestServiceServer.bindTo(api).build();
        MockRestServiceServer graphServer = MockRestServiceServer.bindTo(graph).build();
        return new Fixture(new GitHubPullRequestReadClient(api.build(), graph.build()), apiServer, graphServer);
    }

    private record Fixture(GitHubPullRequestReadClient client, MockRestServiceServer api, MockRestServiceServer graph)
    {
        void expect(ObjectNode response, String rules)
        {
            graph.expect(requestTo("https://api.github.test/graphql")).andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
            api.expect(requestTo(RULES)).andRespond(withSuccess(rules, MediaType.APPLICATION_JSON));
        }

        void verify()
        {
            api.verify();
            graph.verify();
        }
    }
}
