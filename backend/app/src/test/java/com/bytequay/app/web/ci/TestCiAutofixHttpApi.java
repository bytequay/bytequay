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
package com.bytequay.app.web.ci;

import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.app.agent.AgentProperties;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.ci.SendCiAutofixMessageCommand;
import com.bytequay.app.ci.StartCiAutofixCommand;
import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.app.web.agent.AgentController;
import com.bytequay.app.web.agent.AgentRequestFilter;
import com.bytequay.core.ci.CiAutofixAttempt;
import com.bytequay.core.ci.CiAutofixAttempt.FailureEvidence;
import com.bytequay.core.ci.CiAutofixAttempt.GitResult;
import com.bytequay.core.ci.CiAutofixAttemptId;
import com.bytequay.core.ci.CiAutofixConflictException;
import com.bytequay.core.ci.CiAutofixNotFoundException;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRun.Authorization;
import com.bytequay.core.ci.CiAutofixRun.Scope;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.domain.PullRequestRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestCiAutofixHttpApi
{
    private static final String SESSION = "desktop-session";
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    private CiAutofixService service;
    private AgentMetricsService metrics;
    private MockMvc mvc;
    private CiAutofixRun run;

    @BeforeEach
    void setUp()
    {
        service = mock(CiAutofixService.class);
        metrics = mock(AgentMetricsService.class);
        run = run();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AgentProperties properties = new AgentProperties(
                Path.of("/tmp/agent-runtime"),
                Duration.ofMinutes(30),
                SESSION,
                "codex",
                false,
                "claude",
                false);
        mvc = standaloneSetup(new CiAutofixController(service, metrics))
                .addFilters(new AgentRequestFilter(properties, objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void protectsEveryEndpointWithTheDesktopSessionAndRejectsBrowserOrigins()
            throws Exception
    {
        mvc.perform(get("/api/ci-autofix/runs"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ci-autofix/runs")
                        .header(AgentController.SESSION_HEADER, SESSION)
                        .header("Origin", "https://example.com"))
                .andExpect(status().isForbidden());
        verify(service, never()).list(any(Integer.class));
    }

    @Test
    void startsListsGetsMessagesAndCancelsWithoutExposingInternalPathsOrLogs()
            throws Exception
    {
        when(service.start(any())).thenReturn(run);
        when(service.list(10)).thenReturn(ImmutableList.of(run));
        when(service.get(run.id())).thenReturn(run);
        when(service.sendMessage(any())).thenReturn(run);
        when(service.cancel(run.id())).thenReturn(run);

        String startBody = """
                {
                  "repository":"bytequay/sample",
                  "pullRequestNumber":42,
                  "worktree":"/private/tmp/secret-worktree",
                  "laneId":"codex",
                  "maxAttempts":3,
                  "allowFailedJobReruns":true,
                  "allowHistoryRewrites":true,
                  "allowExactLeasePush":true
                }
                """;
        mvc.perform(post("/api/ci-autofix/runs")
                        .header(AgentController.SESSION_HEADER, SESSION)
                        .header("Idempotency-Key", "start-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repository").value("bytequay/sample"))
                .andExpect(jsonPath("$.activity").value("PREPARING_AGENT"))
                .andExpect(content().string(not(containsString("secret-worktree"))))
                .andExpect(content().string(not(containsString("start-key"))))
                .andExpect(content().string(not(containsString("log.path"))));

        mvc.perform(get("/api/ci-autofix/runs?limit=10")
                        .header(AgentController.SESSION_HEADER, SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(run.id().toString()));
        mvc.perform(get("/api/ci-autofix/runs/{runId}", run.id())
                        .header(AgentController.SESSION_HEADER, SESSION))
                .andExpect(status().isOk());
        mvc.perform(post("/api/ci-autofix/runs/{runId}/messages", run.id())
                        .header(AgentController.SESSION_HEADER, SESSION)
                        .header("Idempotency-Key", "message-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"try the compiler again\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/ci-autofix/runs/{runId}/cancel", run.id())
                        .header(AgentController.SESSION_HEADER, SESSION))
                .andExpect(status().isOk());

        ArgumentCaptor<StartCiAutofixCommand> start = ArgumentCaptor.forClass(StartCiAutofixCommand.class);
        verify(service).start(start.capture());
        assertThat(start.getValue().idempotencyKey()).isEqualTo("start-key");
        ArgumentCaptor<SendCiAutofixMessageCommand> message =
                ArgumentCaptor.forClass(SendCiAutofixMessageCommand.class);
        verify(service).sendMessage(message.capture());
        assertThat(message.getValue().idempotencyKey()).isEqualTo("message-key");
    }

    @Test
    void validatesHeadersAndMapsCiDomainFailures()
            throws Exception
    {
        mvc.perform(post("/api/ci-autofix/runs")
                        .header(AgentController.SESSION_HEADER, SESSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        when(service.list(201)).thenThrow(new IllegalArgumentException("limit must be between 1 and 200"));
        mvc.perform(get("/api/ci-autofix/runs?limit=201")
                        .header(AgentController.SESSION_HEADER, SESSION))
                .andExpect(status().isBadRequest());

        when(service.get(run.id())).thenThrow(new CiAutofixNotFoundException(run.id()));
        mvc.perform(get("/api/ci-autofix/runs/{runId}", run.id())
                        .header(AgentController.SESSION_HEADER, SESSION))
                .andExpect(status().isNotFound());

        when(service.cancel(run.id())).thenThrow(new CiAutofixConflictException("version changed"));
        mvc.perform(post("/api/ci-autofix/runs/{runId}/cancel", run.id())
                        .header(AgentController.SESSION_HEADER, SESSION))
                .andExpect(status().isConflict());
    }

    @Test
    void boundsGitFactListsInResponses()
    {
        CiAutofixRun source = run();
        CiAutofixAttempt attempt = CiAutofixAttempt.create(
                CiAutofixAttemptId.random(),
                source.id(),
                1,
                "a".repeat(40),
                ImmutableList.of(new FailureEvidence(
                        "compile",
                        "compile failed",
                        100,
                        1,
                        200,
                        1,
                        Optional.empty())),
                NOW.plusSeconds(1));
        source = source.addAttempt(attempt, NOW.plusSeconds(1));
        List<String> paths = IntStream.range(0, 201).mapToObj(index -> "src/File" + index + ".java").collect(toImmutableList());
        List<String> subjects = IntStream.range(0, 201).mapToObj(index -> "Commit " + index).collect(toImmutableList());
        attempt = source.attempts().getFirst().withGitResult(
                new GitResult("c".repeat(40), "fingerprint", paths, subjects),
                NOW.plusSeconds(2));
        source = source.updateAttempt(attempt, NOW.plusSeconds(2));

        CiAutofixHttpModels.GitResponse git = CiAutofixHttpModels.run(source, Optional.empty())
                .attempts().getFirst().git().orElseThrow();

        assertThat(git.changedPaths()).hasSize(200);
        assertThat(git.changedPathsTruncated()).isTrue();
        assertThat(git.commitSubjects()).hasSize(200);
        assertThat(git.commitSubjectsTruncated()).isTrue();
    }

    @Test
    void exposesTheLinkedRebaseAsProgramOwnedActivity()
    {
        CiAutofixRun rebasing = run()
                .assignAgentTask("task-1", NOW.plusSeconds(1))
                .linkBranchRebase("rebase-1", NOW.plusSeconds(2));

        CiAutofixHttpModels.RunResponse response = CiAutofixHttpModels.run(rebasing, Optional.empty());

        assertThat(response.activity()).isEqualTo("REBASING");
        assertThat(response.currentRebaseId()).contains("rebase-1");
        assertThat(response.currentTurnId()).isEmpty();
    }

    @Test
    void exposesAnAssignedZeroAttemptRunAsWatchingCi()
    {
        CiAutofixRun watching = run().assignAgentTask("task-1", NOW.plusSeconds(1));

        CiAutofixHttpModels.RunResponse response = CiAutofixHttpModels.run(watching, Optional.empty());

        assertThat(response.activity()).isEqualTo("WATCHING_CI");
        assertThat(response.attempts()).isEmpty();
    }

    private static CiAutofixRun run()
    {
        return CiAutofixRun.create(
                CiAutofixRunId.random(),
                "never-expose-this-key",
                new Scope(
                        PullRequestRef.of("bytequay", "sample", 42),
                        "author",
                        "feature",
                        "main",
                        Path.of("/private/tmp/secret-worktree"),
                        "codex"),
                "a".repeat(40),
                "b".repeat(40),
                new Authorization(true, true, true, 3, NOW, NOW.plus(Duration.ofHours(8))),
                NOW);
    }
}
