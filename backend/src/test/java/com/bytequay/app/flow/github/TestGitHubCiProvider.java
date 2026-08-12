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
package com.bytequay.app.flow.github;

import com.bytequay.app.flow.ci.CiAutofixRecords.PolicyResolution;
import com.bytequay.app.flow.ci.CiAutofixRecords.RequiredCiPolicyRevision;
import com.bytequay.app.flow.ci.CiObservationCoordinator.CiObservationActivation;
import com.bytequay.app.flow.runtime.FlowRuntime.CiObservationSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class TestGitHubCiProvider
{
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final String HEAD =
            "2222222222222222222222222222222222222222";

    @Test
    void selectorIdentityIsExactCaseAndPreservesOrdinaryPunctuation()
    {
        ScenarioHttp http = new ScenarioHttp();
        http.runs = List.of(
                run(11, "Build/Test (JDK 25)", "success", null),
                run(12, "build/test (jdk 25)", "failure",
                        "https://example.invalid/not-actions"));
        GitHubCiProvider.PollResult result = provider(http).poll(activation(
                "GITHUB_CHECK:7:Build/Test (JDK 25)"));

        assertThat(result.failure()).isNull();
        assertThat(result.proof().checks()).singleElement().satisfies(check -> {
            assertThat(check.name()).isEqualTo("Build/Test (JDK 25)");
            assertThat(check.providerCheckId()).isEqualTo("11");
            assertThat(check.selectorKey())
                    .isEqualTo("GITHUB_CHECK:7:Build/Test (JDK 25)");
        });
        assertThat(http.logRequests).isZero();
    }

    @Test
    void unsupportedFailureDoesNotMasqueradeAsStaleProviderIdentity()
    {
        ScenarioHttp http = new ScenarioHttp();
        http.runs = List.of(run(
                11, "build", "failure",
                "https://ci.example.test/build/11"));

        assertThat(provider(http).poll(activation(
                "GITHUB_CHECK:7:build")).failure())
                .isEqualTo(GitHubCiProvider.Failure.UNSUPPORTED);
        assertThat(http.logRequests).isZero();

        http.runs = List.of(run(
                12, "build", "failure",
                "https://github.com/base/repo/actions/runs/9/job/12"));
        GitHubCiProvider.PollResult supported = provider(http).poll(
                activation("GITHUB_CHECK:7:build"));
        assertThat(supported.failure()).isNull();
        assertThat(supported.proof().failedLogsByProviderCheckId())
                .containsOnlyKeys("12");
        assertThat(http.logRequests).isOne();
    }

    @Test
    void onlyTheOfficialActionsAppMaySupplyActionsJobEvidence()
    {
        ScenarioHttp http = new ScenarioHttp();
        http.runs = List.of(run(
                11, "build", "failure",
                "https://github.com/base/repo/actions/runs/9/job/12")
                .replace("github-actions", "third-party-ci"));

        GitHubCiProvider.PollResult result = provider(http).poll(activation(
                "GITHUB_CHECK:7:build"));

        assertThat(result.failure())
                .isEqualTo(GitHubCiProvider.Failure.UNSUPPORTED);
        assertThat(result.proof()).isNull();
        assertThat(http.logRequests).isZero();
    }

    @Test
    void historicalUnsupportedFailureCannotBlockTheCurrentRun()
    {
        ScenarioHttp http = new ScenarioHttp();
        String oldFailure = run(
                11, "build", "failure", "https://ci.invalid/build/11")
                .replace("11:55:00Z", "11:50:00Z")
                .replace("11:59:00Z", "11:51:00Z");
        http.runs = List.of(
                oldFailure, run(12, "build", "success", null));

        GitHubCiProvider.PollResult green = provider(http).poll(activation(
                "GITHUB_CHECK:7:build"));

        assertThat(green.failure()).isNull();
        assertThat(green.proof().checks()).hasSize(2);
        assertThat(green.proof().failedLogsByProviderCheckId()).isEmpty();
        assertThat(http.logRequests).isZero();

        http.runs = List.of(
                oldFailure,
                run(13, "build", null, null, "in_progress"));
        GitHubCiProvider.PollResult collecting = provider(http).poll(
                activation("GITHUB_CHECK:7:build"));
        assertThat(collecting.failure()).isNull();
        assertThat(collecting.proof().failedLogsByProviderCheckId()).isEmpty();
        assertThat(http.logRequests).isZero();
    }

    @Test
    void policyAcceptedFailureDoesNotRequireLogEvidence()
    {
        ScenarioHttp http = new ScenarioHttp();
        http.runs = List.of(run(
                11, "build", "failure", "https://ci.invalid/build/11"));

        GitHubCiProvider.PollResult result = provider(http).poll(
                activation("GITHUB_CHECK:7:build", List.of("FAILURE")));

        assertThat(result.failure()).isNull();
        assertThat(result.proof().failedLogsByProviderCheckId()).isEmpty();
        assertThat(http.logRequests).isZero();
    }

    @Test
    void logIsRequiredOnlyWhenTheWholeSnapshotCanBeFinalRed()
    {
        ScenarioHttp http = new ScenarioHttp();
        String unsupportedFailure = run(
                11, "build", "failure", "https://ci.invalid/build/11");
        http.runs = List.of(
                unsupportedFailure,
                run(12, "test", "cancelled", null));
        CiObservationActivation activation = activation(
                List.of("GITHUB_CHECK:7:build", "GITHUB_CHECK:7:test"),
                List.of("SUCCESS"));

        assertThat(provider(http).poll(activation).failure()).isNull();
        assertThat(http.logRequests).isZero();

        http.runs = List.of(
                unsupportedFailure,
                run(13, "test", null, null, "in_progress"));
        assertThat(provider(http).poll(activation).failure()).isNull();
        assertThat(http.logRequests).isZero();

        http.runs = List.of(
                run(14, "build", "failure",
                        "https://github.com/base/repo/actions/runs/9/job/12"),
                run(15, "test", "success", null));
        GitHubCiProvider.PollResult finalRed = provider(http).poll(activation);
        assertThat(finalRed.failure()).isNull();
        assertThat(finalRed.proof().failedLogsByProviderCheckId())
                .containsOnlyKeys("14");
        assertThat(http.logRequests).isOne();
    }

    @Test
    void changingRunBetweenExhaustivePassesProducesNoProof()
    {
        ScenarioHttp http = new ScenarioHttp();
        http.runs = List.of(run(11, "build", "success", null));
        http.secondPassRuns = List.of(run(
                12, "build", null, null, "in_progress"));

        GitHubCiProvider.PollResult result = provider(http).poll(activation(
                "GITHUB_CHECK:7:build"));

        assertThat(result.failure())
                .isEqualTo(GitHubCiProvider.Failure.UNAVAILABLE);
        assertThat(result.proof()).isNull();
    }

    @Test
    void closedOrWrongRepositoryPullRequestIsStableInvalid()
    {
        ScenarioHttp closed = new ScenarioHttp();
        closed.prState = "closed";
        assertThat(provider(closed).poll(activation(
                "GITHUB_CHECK:7:build")).failure())
                .isEqualTo(GitHubCiProvider.Failure.INVALID);

        ScenarioHttp wrongRepository = new ScenarioHttp();
        wrongRepository.baseRepositoryId = 999;
        assertThat(provider(wrongRepository).poll(activation(
                "GITHUB_CHECK:7:build")).failure())
                .isEqualTo(GitHubCiProvider.Failure.INVALID);
    }

    @Test
    void credentialIsWipedOnEveryPollOutcome()
    {
        char[] token = "repository-token".toCharArray();
        ScenarioHttp unavailable = new ScenarioHttp();
        unavailable.complete = false;
        GitHubCiProvider provider = new GitHubCiProvider(
                (id, owner, name) ->
                        new GitHubProvider.RepositoryCredential(id, token),
                unavailable, fixedClock());

        assertThat(provider.poll(activation(
                "GITHUB_CHECK:7:build")).failure())
                .isEqualTo(GitHubCiProvider.Failure.UNAVAILABLE);
        assertThat(token).containsOnly('\0');
    }

    @Test
    void rateLimitHeadersProduceBoundedRetryAdmission()
    {
        ScenarioHttp retryAfter = new ScenarioHttp();
        retryAfter.rateStatus = 429;
        retryAfter.retryAfter = "600";
        assertThat(provider(retryAfter).poll(activation(
                "GITHUB_CHECK:7:build")).retryNotBefore())
                .isEqualTo(NOW.plusSeconds(600));

        ScenarioHttp reset = new ScenarioHttp();
        reset.rateStatus = 403;
        reset.rateLimitReset = Long.toString(
                NOW.plusSeconds(7200).getEpochSecond());
        assertThat(provider(reset).poll(activation(
                "GITHUB_CHECK:7:build")).retryNotBefore())
                .isEqualTo(NOW.plusSeconds(7200));

        ScenarioHttp malformed = new ScenarioHttp();
        malformed.rateStatus = 429;
        malformed.retryAfter = "not-a-delay";
        malformed.rateLimitReset = Long.toString(
                NOW.plusSeconds(900).getEpochSecond());
        assertThat(provider(malformed).poll(activation(
                "GITHUB_CHECK:7:build")).retryNotBefore())
                .isEqualTo(NOW.plusSeconds(900));

        ScenarioHttp headerless = new ScenarioHttp();
        headerless.rateStatus = 403;
        assertThat(provider(headerless).poll(activation(
                "GITHUB_CHECK:7:build")).retryNotBefore())
                .isEqualTo(NOW.plus(Duration.ofHours(1)));

        ScenarioHttp forbiddenRetryAfter = new ScenarioHttp();
        forbiddenRetryAfter.rateStatus = 403;
        forbiddenRetryAfter.retryAfter = "1200";
        assertThat(provider(forbiddenRetryAfter).poll(activation(
                "GITHUB_CHECK:7:build")).retryNotBefore())
                .isEqualTo(NOW.plusSeconds(1200));

        ScenarioHttp limitedReset = new ScenarioHttp();
        limitedReset.rateStatus = 429;
        limitedReset.rateLimitReset = Long.toString(
                NOW.plusSeconds(1800).getEpochSecond());
        assertThat(provider(limitedReset).poll(activation(
                "GITHUB_CHECK:7:build")).retryNotBefore())
                .isEqualTo(NOW.plusSeconds(1800));

        ScenarioHttp absurd = new ScenarioHttp();
        absurd.rateStatus = 429;
        absurd.retryAfter = Long.toString(Duration.ofDays(2).toSeconds());
        assertThat(provider(absurd).poll(activation(
                "GITHUB_CHECK:7:build")).retryNotBefore())
                .isEqualTo(NOW.plus(Duration.ofDays(1)));
    }

    @Test
    void localEnumerationBudgetUsesSlowRetryWithoutPartialProof()
    {
        ScenarioHttp oversized = new ScenarioHttp();
        oversized.suiteCount = 11;

        GitHubCiProvider.PollResult result = provider(oversized).poll(
                activation("GITHUB_CHECK:7:build"));

        assertThat(result.failure())
                .isEqualTo(GitHubCiProvider.Failure.UNAVAILABLE);
        assertThat(result.proof()).isNull();
        assertThat(result.retryNotBefore())
                .isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void relativeRateDelayStartsWhenTheLimitedResponseArrives()
    {
        MutableClock clock = new MutableClock(NOW);
        ScenarioHttp limited = new ScenarioHttp();
        limited.rateStatus = 429;
        limited.retryAfter = "600";
        limited.beforeRateResponse = () ->
                clock.now = NOW.plus(Duration.ofMinutes(2));
        GitHubCiProvider provider = new GitHubCiProvider(
                (id, owner, name) -> credential(id), limited, clock);

        assertThat(provider.poll(activation(
                "GITHUB_CHECK:7:build")).retryNotBefore())
                .isEqualTo(NOW.plus(Duration.ofMinutes(12)));
    }

    private static GitHubCiProvider provider(ScenarioHttp http)
    {
        return new GitHubCiProvider(
                (id, owner, name) -> credential(id),
                http, fixedClock());
    }

    private static GitHubProvider.RepositoryCredential credential(String id)
    {
        return new GitHubProvider.RepositoryCredential(
                id, "repository-token".toCharArray());
    }

    private static Clock fixedClock()
    {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static CiObservationActivation activation(String selector)
    {
        return activation(selector, List.of("SUCCESS"));
    }

    private static CiObservationActivation activation(
            String selector, List<String> acceptedConclusions)
    {
        return activation(List.of(selector), acceptedConclusions);
    }

    private static CiObservationActivation activation(
            List<String> selectors, List<String> acceptedConclusions)
    {
        Claim claim = new Claim(
                "observe-op-1", "task-1", OperationKind.OBSERVE_CI,
                1, "claim-token", "observer", NOW.plusSeconds(600));
        CiObservationSubject subject = new CiObservationSubject(
                "receipt-1", "publish-op-1", "plan-1", "step-1",
                "probe-1", "receipt-digest", "pr-1", "task-1",
                "repo-1", "main", "scope-1", "GITHUB",
                "101", "base", "repo", "202", "head", "fork",
                17L, "PR_node", "topic", "refs/heads/topic",
                "1111111111111111111111111111111111111111", HEAD);
        RequiredCiPolicyRevision policy = new RequiredCiPolicyRevision(
                "policy-1", "repo-1", "scope-1", "main", 1,
                PolicyResolution.RESOLVED, "policy-source", "policy-digest",
                null, selectors, acceptedConclusions, NOW);
        try {
            Constructor<CiObservationActivation> constructor =
                    CiObservationActivation.class.getDeclaredConstructor(
                            Claim.class, CiObservationSubject.class,
                            RequiredCiPolicyRevision.class);
            constructor.setAccessible(true);
            return constructor.newInstance(claim, subject, policy);
        }
        catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String run(
            long id, String name, String conclusion, String detailsUrl)
    {
        return run(id, name, conclusion, detailsUrl, "completed");
    }

    private static String run(
            long id,
            String name,
            String conclusion,
            String detailsUrl,
            String status)
    {
        String conclusionJson = conclusion == null
                ? "null" : "\"" + conclusion + "\"";
        String detailsJson = detailsUrl == null
                ? "null" : "\"" + detailsUrl + "\"";
        return """
                {"id":%d,"check_suite":{"id":1},
                 "head_sha":"%s","name":"%s","status":"%s",
                 "conclusion":%s,"started_at":"2026-08-11T11:55:00Z",
                 "completed_at":"2026-08-11T11:59:00Z","details_url":%s,
                 "app":{"id":7,"slug":"github-actions"}}
                """.formatted(
                id, HEAD, name, status, conclusionJson, detailsJson).trim();
    }

    private static final class ScenarioHttp
            implements GitHubCiProvider.CiHttp
    {
        private boolean complete = true;
        private String prState = "open";
        private long baseRepositoryId = 101;
        private int rateStatus;
        private int suiteCount = 1;
        private String retryAfter;
        private String rateLimitReset;
        private Runnable beforeRateResponse = () -> {};
        private List<String> runs = List.of(
                run(11, "build", "success", null));
        private List<String> secondPassRuns;
        private int runRequests;
        private int logRequests;

        @Override
        public GitHubCiProvider.CiHttpResponse get(
                URI uri, char[] token, int responseLimit)
        {
            if (rateStatus != 0) {
                beforeRateResponse.run();
                return new GitHubCiProvider.CiHttpResponse(
                        true, rateStatus, new byte[0], null,
                        retryAfter, rateLimitReset);
            }
            if (!complete) {
                return response(false, 0, "");
            }
            String path = uri.toString();
            if (path.contains("/pulls/17")) {
                return response(true, 200, prJson(prState));
            }
            if (path.contains("/check-suites?")) {
                List<String> suites = new ArrayList<>();
                for (int index = 1; index <= suiteCount; index++) {
                    suites.add("{\"id\":" + index
                            + ",\"app\":{\"id\":7},\"head_sha\":\""
                            + HEAD + "\"}");
                }
                return response(true, 200,
                        "{\"total_count\":" + suiteCount
                                + ",\"check_suites\":["
                                + String.join(",", suites) + "]}");
            }
            if (path.contains("/check-suites/1/check-runs?")) {
                List<String> current = runRequests++ == 0
                        || secondPassRuns == null ? runs : secondPassRuns;
                return response(true, 200,
                        "{\"total_count\":" + current.size()
                                + ",\"check_runs\":["
                                + String.join(",", current) + "]}");
            }
            if (path.contains("/actions/jobs/12/logs")) {
                logRequests++;
                return response(true, 200, "failure output\n");
            }
            throw new AssertionError("unexpected HTTP request: " + uri);
        }

        private String prJson(String state)
        {
            return """
                    {"number":17,"state":"%s","node_id":"PR_node",
                     "base":{"ref":"main","sha":"base-sha","repo":{
                       "id":%d,"name":"repo","owner":{"login":"base"}}},
                     "head":{"ref":"topic","sha":"%s","repo":{
                       "id":202,"name":"fork","owner":{"login":"head"}}}}
                    """.formatted(state, baseRepositoryId, HEAD);
        }

        private static GitHubCiProvider.CiHttpResponse response(
                boolean complete, int status, String body)
        {
            return new GitHubCiProvider.CiHttpResponse(
                    complete, status,
                    body.getBytes(StandardCharsets.UTF_8),
                    null, null, null);
        }
    }

    private static final class MutableClock
            extends Clock
    {
        private Instant now;

        private MutableClock(Instant now)
        {
            this.now = now;
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return now;
        }
    }
}
