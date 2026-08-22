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
package com.bytequay.app.ci.e2e;

import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.app.ci.CiAutofixMonitorTestDriver;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.ci.CiHeadSnapshot;
import com.bytequay.app.ci.CiJob;
import com.bytequay.app.ci.CiRerunResult;
import com.bytequay.app.ci.CiStep;
import com.bytequay.app.ci.CiWorkflowRun;
import com.bytequay.app.ci.InspectCiHeadQuery;
import com.bytequay.app.ci.ReadCiLogQuery;
import com.bytequay.app.ci.RerunFailedJobsCommand;
import com.bytequay.app.web.agent.AgentController;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.Math.toIntExact;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

final class CiAutofixE2eHarness
        implements AutoCloseable
{
    static final String SESSION = "ci-e2e-session";
    static final String RAW_COMPILE_LOG = "RAW_COMPILE_LOG_SENTINEL: cannot find symbol GeneratedValue\n";
    static final String RAW_TEST_LOG = "RAW_TEST_LOG_SENTINEL: expected repaired value\n";

    private static final AtomicLong NEXT_PR_ID = new AtomicLong(900_000);

    private final MockMvc mvc;
    private final ObjectMapper objectMapper;
    private final CiAutofixService service;
    private final CiAutofixMonitorTestDriver monitor;
    private final ScriptedGitHubActionsClient gitHub;
    private final ScriptedAgentDriver agent;
    private final AgentTaskService tasks;
    private final AgentTurnService turns;
    private final AgentEventService events;
    private final AgentResourceService resources;
    private final PullRequestJpaRepository pullRequests;
    private final PrDetailStore details;
    private final Clock clock;
    private final List<CiAutofixRunId> startedRuns = new ArrayList<>();

    CiAutofixE2eHarness(
            MockMvc mvc,
            ObjectMapper objectMapper,
            CiAutofixService service,
            CiAutofixMonitorTestDriver monitor,
            ScriptedGitHubActionsClient gitHub,
            ScriptedAgentDriver agent,
            AgentTaskService tasks,
            AgentTurnService turns,
            AgentEventService events,
            AgentResourceService resources,
            PullRequestJpaRepository pullRequests,
            PrDetailStore details,
            Clock clock)
    {
        this.mvc = mvc;
        this.objectMapper = objectMapper;
        this.service = service;
        this.monitor = monitor;
        this.gitHub = gitHub;
        this.agent = agent;
        this.tasks = tasks;
        this.turns = turns;
        this.events = events;
        this.resources = resources;
        this.pullRequests = pullRequests;
        this.details = details;
        this.clock = clock;
    }

    Fixture createFixture(Path temporaryDirectory, String name)
    {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long prId = NEXT_PR_ID.incrementAndGet();
        PullRequestRef pullRequest = PullRequestRef.of(
                "bytequay",
                name + "-" + suffix,
                toIntExact(prId));
        E2eGitRepository repository = E2eGitRepository.create(
                temporaryDirectory.resolve(name + "-" + suffix),
                pullRequest);
        seedPullRequest(prId, repository);
        return new Fixture(prId, repository, prId * 10 + 1, prId * 10 + 2);
    }

    void scriptFailingCi(Fixture fixture, boolean blockAgent)
    {
        CiHeadSnapshot snapshot = failingSnapshot(fixture);
        gitHub.setSnapshot(snapshot);
        gitHub.setLog(
                fixture.repository().pullRequest(),
                fixture.workflowRunId(),
                1,
                fixture.jobId(),
                1,
                "compile failed: missing symbol\n",
                ImmutableList.of("compile failed: missing symbol"));
        agent.bind(fixture.repository().pullRequest(), fixture.repository().worktree());
        if (blockAgent) {
            agent.enqueueBlocked(fixture.repository().pullRequest(), fixture.repository().worktree());
        }
    }

    void scriptPrimaryJourney(Fixture fixture)
    {
        gitHub.setSnapshot(failingSnapshot(
                fixture,
                fixture.repository().initialHeadSha(),
                fixture.workflowRunId(),
                fixture.jobId(),
                "Backend compile",
                "Compile",
                true));
        gitHub.setLog(
                fixture.repository().pullRequest(),
                fixture.workflowRunId(),
                1,
                fixture.jobId(),
                1,
                RAW_COMPILE_LOG,
                ImmutableList.of("cannot find symbol GeneratedValue"));
        agent.bind(fixture.repository().pullRequest(), fixture.repository().worktree());
    }

    void scriptUnauthorizedCi(Fixture fixture, String author, boolean sameRepository)
    {
        CiHeadSnapshot snapshot = failingSnapshot(fixture);
        gitHub.setSnapshot(new CiHeadSnapshot(
                snapshot.pullRequest(),
                snapshot.open(),
                author,
                sameRepository,
                snapshot.headRef(),
                snapshot.headSha(),
                snapshot.baseRef(),
                snapshot.baseSha(),
                snapshot.url(),
                snapshot.workflowRuns()));
    }

    RerunFailedJobsCommand allowRerun(Fixture fixture, int previousAttempt)
    {
        RerunFailedJobsCommand command = new RerunFailedJobsCommand(
                fixture.repository().pullRequest(),
                fixture.workflowRunId(),
                fixture.repository().initialHeadSha(),
                previousAttempt,
                true);
        gitHub.enqueueRerun(command, new CiRerunResult(CiRerunResult.Outcome.ACCEPTED, "accepted by test"));
        return command;
    }

    void scriptRerunCi(Fixture fixture, int attempt, boolean passing)
    {
        gitHub.setSnapshot(rerunSnapshot(fixture, attempt, passing));
    }

    void scriptTestFailure(Fixture fixture, String headSha)
    {
        gitHub.setSnapshot(failingSnapshot(
                fixture,
                headSha,
                fixture.workflowRunId() + 100,
                fixture.jobId() + 100,
                "Backend tests",
                "Unit tests",
                false));
        gitHub.setLog(
                fixture.repository().pullRequest(),
                fixture.workflowRunId() + 100,
                1,
                fixture.jobId() + 100,
                1,
                RAW_TEST_LOG,
                ImmutableList.of("expected repaired value"));
    }

    void scriptGreenCi(Fixture fixture, String headSha)
    {
        gitHub.setSnapshot(greenSnapshot(fixture, headSha));
    }

    StartResult start(Fixture fixture, String idempotencyKey)
            throws Exception
    {
        MvcResult result = mvc.perform(post("/api/ci-autofix/runs")
                        .header(AgentController.SESSION_HEADER, SESSION)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody(fixture)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        CiAutofixRunId runId = CiAutofixRunId.valueOf(response.required("id").textValue());
        startedRuns.add(runId);
        return new StartResult(runId, response);
    }

    int startStatus(
            Fixture fixture,
            String idempotencyKey,
            boolean allowFailedJobReruns,
            boolean allowHistoryRewrites,
            boolean allowExactLeasePush)
            throws Exception
    {
        return mvc.perform(post("/api/ci-autofix/runs")
                        .header(AgentController.SESSION_HEADER, SESSION)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody(
                                fixture,
                                allowFailedJobReruns,
                                allowHistoryRewrites,
                                allowExactLeasePush)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    int startWithoutSession()
            throws Exception
    {
        return mvc.perform(post("/api/ci-autofix/runs")
                        .header("Idempotency-Key", "missing-session-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    void tick()
    {
        monitor.tick();
    }

    CiAutofixRun driveUntil(CiAutofixRunId runId, Predicate<CiAutofixRun> done, int maximumTicks)
    {
        CiAutofixRun run = service.get(runId);
        for (int tick = 0; tick < maximumTicks && !done.test(run); tick++) {
            monitor.tick();
            run = service.get(runId);
        }
        if (!done.test(run)) {
            throw new AssertionError("CI Autofix did not reach the expected condition after "
                    + maximumTicks + " ticks: " + snapshot(runId));
        }
        return run;
    }

    CiAutofixRun run(CiAutofixRunId runId)
    {
        return service.get(runId);
    }

    JsonNode getResponse(CiAutofixRunId runId)
            throws Exception
    {
        MvcResult result = mvc.perform(get("/api/ci-autofix/runs/{runId}", runId)
                        .header(AgentController.SESSION_HEADER, SESSION))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    JsonNode cancel(CiAutofixRunId runId)
            throws Exception
    {
        MvcResult result = mvc.perform(post("/api/ci-autofix/runs/{runId}/cancel", runId)
                        .header(AgentController.SESSION_HEADER, SESSION))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    JsonNode sendMessage(CiAutofixRunId runId, String idempotencyKey, String message)
            throws Exception
    {
        MvcResult result = mvc.perform(post("/api/ci-autofix/runs/{runId}/messages", runId)
                        .header(AgentController.SESSION_HEADER, SESSION)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ImmutableMap.of("message", message))))
                .andExpect(status().isAccepted())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    Snapshot snapshot(CiAutofixRunId runId)
    {
        CiAutofixRun run = service.get(runId);
        Optional<AgentTask> task = run.agentTaskId().map(TaskId::valueOf).map(tasks::get);
        Optional<AgentTurn> turn = run.currentTurnId().map(TurnId::valueOf).map(turns::get);
        Optional<TaskId> taskId = run.agentTaskId().map(TaskId::valueOf);
        String remoteHead = E2eGitRepository.git(
                run.scope().worktree(),
                "ls-remote",
                "origin",
                "refs/heads/" + run.scope().headRef()).split("\\s+", 2)[0];
        return new Snapshot(
                run,
                task.map(value -> value.state().name()),
                turn.map(value -> value.state().name()),
                gitHub.inspectCalls(),
                gitHub.logCalls(),
                gitHub.rerunCalls(),
                agent.turns(),
                agent.memoryReads(),
                agent.gitActions(),
                agent.cancellations(),
                taskId.map(value -> events.readAfter(value, 0, 100).stream()
                        .map(event -> event.event().kind().name())
                        .collect(toImmutableList())).orElse(ImmutableList.of()),
                taskId.map(this::runtimeFiles).orElse(ImmutableList.of()),
                E2eGitRepository.git(
                        run.scope().worktree(),
                        "rev-parse",
                        "HEAD"),
                remoteHead,
                E2eGitRepository.git(
                        run.scope().worktree(),
                        "status",
                        "--porcelain=v1",
                        "--untracked-files=all"));
    }

    @Override
    public void close()
    {
        for (CiAutofixRunId runId : startedRuns) {
            CiAutofixRun run = service.get(runId);
            if (run.status() != CiAutofixRun.Status.FINISHED) {
                try {
                    cancel(runId);
                }
                catch (Exception e) {
                    throw new IllegalStateException("Cannot clean up CI Autofix E2E run " + runId, e);
                }
            }
        }
    }

    private List<String> runtimeFiles(TaskId taskId)
    {
        Path root = resources.require(taskId).runtimeDirectory();
        try (var files = Files.walk(root, 3)) {
            return files.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .sorted()
                    .limit(100)
                    .collect(toImmutableList());
        }
        catch (IOException e) {
            return ImmutableList.of("runtime files unavailable: " + e.getClass().getSimpleName());
        }
    }

    private String startBody(Fixture fixture)
            throws Exception
    {
        return startBody(fixture, true, true, true);
    }

    private String startBody(
            Fixture fixture,
            boolean allowFailedJobReruns,
            boolean allowHistoryRewrites,
            boolean allowExactLeasePush)
            throws Exception
    {
        return objectMapper.writeValueAsString(ImmutableMap.of(
                "repository", fixture.repository().pullRequest().repoFullName(),
                "pullRequestNumber", fixture.repository().pullRequest().number(),
                "worktree", fixture.repository().worktree().toString(),
                "laneId", ScriptedAgentDriver.LANE.toString(),
                "maxAttempts", 3,
                "allowFailedJobReruns", allowFailedJobReruns,
                "allowHistoryRewrites", allowHistoryRewrites,
                "allowExactLeasePush", allowExactLeasePush));
    }

    private void seedPullRequest(long prId, E2eGitRepository repository)
    {
        PullRequestEntity entity = new PullRequestEntity();
        entity.setId(prId);
        entity.setRepo(repository.pullRequest().repoFullName());
        entity.setNumber(repository.pullRequest().number());
        entity.setTitle("CI Autofix E2E");
        entity.setAuthor("author");
        entity.setOrigin("AUTHORED");
        entity.setLabels(ImmutableList.of());
        entity.setSyncedAt(clock.instant());
        entity.setHeadRef(E2eGitRepository.HEAD_REF);
        pullRequests.saveAndFlush(entity);
        details.save(prId, new StoredPrDetail(
                new PrRawDetail(
                        "body",
                        ImmutableList.of(),
                        false,
                        true,
                        "clean",
                        1,
                        1,
                        1,
                        0,
                        ImmutableList.of(),
                        repository.initialHeadSha(),
                        E2eGitRepository.HEAD_REF),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of()));
    }

    private static CiHeadSnapshot failingSnapshot(Fixture fixture)
    {
        return failingSnapshot(
                fixture,
                fixture.repository().initialHeadSha(),
                fixture.workflowRunId(),
                fixture.jobId(),
                "Backend compile",
                "Compile",
                false);
    }

    private static CiHeadSnapshot failingSnapshot(
            Fixture fixture,
            String headSha,
            long workflowRunId,
            long jobId,
            String jobName,
            String stepName,
            boolean includePendingSlowJob)
    {
        E2eGitRepository repository = fixture.repository();
        CiStep step = new CiStep(1, stepName, "completed", Optional.of("failure"));
        CiJob job = new CiJob(
                jobId,
                workflowRunId,
                jobName,
                1,
                "completed",
                Optional.of("failure"),
                "https://github.com/" + repository.pullRequest().repoFullName() + "/actions/jobs/"
                        + jobId,
                ImmutableList.of(step));
        CiWorkflowRun workflow = new CiWorkflowRun(
                workflowRunId,
                workflowRunId + 1,
                "CI",
                1,
                "completed",
                Optional.of("failure"),
                headSha,
                "https://github.com/" + repository.pullRequest().repoFullName() + "/actions/runs/"
                        + workflowRunId,
                ImmutableList.of(job));
        ImmutableList.Builder<CiWorkflowRun> workflows = ImmutableList.builder();
        workflows.add(workflow);
        if (includePendingSlowJob) {
            long slowWorkflowId = workflowRunId + 10;
            long slowJobId = jobId + 10;
            CiJob slowJob = new CiJob(
                    slowJobId,
                    slowWorkflowId,
                    "Slow integration tests",
                    1,
                    "in_progress",
                    Optional.empty(),
                    "https://github.com/" + repository.pullRequest().repoFullName() + "/actions/jobs/"
                            + slowJobId,
                    ImmutableList.of(new CiStep(1, "Integration tests", "in_progress", Optional.empty())));
            workflows.add(new CiWorkflowRun(
                    slowWorkflowId,
                    slowWorkflowId + 1,
                    "Slow CI",
                    1,
                    "in_progress",
                    Optional.empty(),
                    headSha,
                    "https://github.com/" + repository.pullRequest().repoFullName() + "/actions/runs/"
                            + slowWorkflowId,
                    ImmutableList.of(slowJob)));
        }
        return new CiHeadSnapshot(
                repository.pullRequest(),
                true,
                "author",
                true,
                E2eGitRepository.HEAD_REF,
                headSha,
                E2eGitRepository.BASE_REF,
                repository.baseSha(),
                "https://github.com/" + repository.pullRequest().fullName(),
                workflows.build());
    }

    private static CiHeadSnapshot greenSnapshot(Fixture fixture, String headSha)
    {
        E2eGitRepository repository = fixture.repository();
        long workflowRunId = fixture.workflowRunId() + 200;
        long jobId = fixture.jobId() + 200;
        CiJob job = new CiJob(
                jobId,
                workflowRunId,
                "CI success",
                1,
                "completed",
                Optional.of("success"),
                "https://github.com/" + repository.pullRequest().repoFullName() + "/actions/jobs/" + jobId,
                ImmutableList.of(new CiStep(1, "Checks", "completed", Optional.of("success"))));
        CiWorkflowRun workflow = new CiWorkflowRun(
                workflowRunId,
                workflowRunId + 1,
                "CI",
                1,
                "completed",
                Optional.of("success"),
                headSha,
                "https://github.com/" + repository.pullRequest().repoFullName() + "/actions/runs/"
                        + workflowRunId,
                ImmutableList.of(job));
        return new CiHeadSnapshot(
                repository.pullRequest(),
                true,
                "author",
                true,
                E2eGitRepository.HEAD_REF,
                headSha,
                E2eGitRepository.BASE_REF,
                repository.baseSha(),
                "https://github.com/" + repository.pullRequest().fullName(),
                ImmutableList.of(workflow));
    }

    private static CiHeadSnapshot rerunSnapshot(Fixture fixture, int attempt, boolean passing)
    {
        E2eGitRepository repository = fixture.repository();
        String conclusion = passing ? "success" : "failure";
        CiJob job = new CiJob(
                fixture.jobId(),
                fixture.workflowRunId(),
                "Backend compile",
                attempt,
                "completed",
                Optional.of(conclusion),
                "https://github.com/" + repository.pullRequest().repoFullName() + "/actions/jobs/"
                        + fixture.jobId(),
                ImmutableList.of(new CiStep(1, "Compile", "completed", Optional.of(conclusion))));
        CiWorkflowRun workflow = new CiWorkflowRun(
                fixture.workflowRunId(),
                fixture.workflowRunId() + 1,
                "CI",
                attempt,
                "completed",
                Optional.of(conclusion),
                repository.initialHeadSha(),
                "https://github.com/" + repository.pullRequest().repoFullName() + "/actions/runs/"
                        + fixture.workflowRunId(),
                ImmutableList.of(job));
        return new CiHeadSnapshot(
                repository.pullRequest(),
                true,
                "author",
                true,
                E2eGitRepository.HEAD_REF,
                repository.initialHeadSha(),
                E2eGitRepository.BASE_REF,
                repository.baseSha(),
                "https://github.com/" + repository.pullRequest().fullName(),
                ImmutableList.of(workflow));
    }

    record Fixture(long prId, E2eGitRepository repository, long workflowRunId, long jobId) {}

    record StartResult(CiAutofixRunId runId, JsonNode response) {}

    record Snapshot(
            CiAutofixRun run,
            Optional<String> taskState,
            Optional<String> turnState,
            List<InspectCiHeadQuery> inspectCalls,
            List<ReadCiLogQuery> logCalls,
            List<RerunFailedJobsCommand> rerunCalls,
            List<ScriptedAgentDriver.TurnCall> agentTurns,
            List<ScriptedAgentDriver.MemoryRead> memoryReads,
            List<ScriptedAgentDriver.GitAction> gitActions,
            List<ScriptedAgentDriver.TurnCall> cancellations,
            List<String> recentEventKinds,
            List<String> runtimeFiles,
            String localHead,
            String remoteHead,
            String gitStatus) {}
}
