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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.CiDescriptionProposal;
import com.bytequay.agent.spi.PullRequestCiBridge;
import com.bytequay.app.ci.BoundedCiLog;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.ci.CiHeadSnapshot;
import com.bytequay.app.ci.CiJob;
import com.bytequay.app.ci.CiLogExcerpt;
import com.bytequay.app.ci.CiRawLogPage;
import com.bytequay.app.ci.CiRawLogStore;
import com.bytequay.app.ci.CiWorkflowRun;
import com.bytequay.app.ci.GitHubActionsClient;
import com.bytequay.app.ci.ReadCiLogQuery;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Reads GitHub evidence through the task's existing worktree and upstream PR association. */
@Component
public class PullRequestCiBridgeResolverImpl
        implements BridgeResolver<PullRequestCiBridge>
{
    private static final int MAXIMUM_CANDIDATES = 20;
    private static final int MAXIMUM_LOG_BYTES = 1024 * 1024;
    private static final int SUMMARY_BUDGET = 30_000;

    private final AgentTaskStore tasks;
    private final WorkspaceStore workspaces;
    private final ManagedWorktreeService worktrees;
    private final PullRequestStore pullRequests;
    private final PullRequestService pullRequestCatalog;
    private final PrDetailStore details;
    private final GitHubActionsClient actions;
    private final TaskLogStore logs;
    private final ObjectMapper objectMapper;
    private final CiRawLogStore rawLogs;
    private final CiAutofixStore ciRuns;
    private final AgentTurnStore turns;
    private final ObjectProvider<CiAutofixService> ci;

    public PullRequestCiBridgeResolverImpl(
            AgentTaskStore tasks, WorkspaceStore workspaces, ManagedWorktreeService worktrees,
            PullRequestStore pullRequests, PullRequestService pullRequestCatalog, PrDetailStore details,
            GitHubActionsClient actions, TaskLogStore logs, ObjectMapper objectMapper, CiRawLogStore rawLogs, CiAutofixStore ciRuns,
            AgentTurnStore turns, ObjectProvider<CiAutofixService> ci)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pullRequestCatalog = requireNonNull(pullRequestCatalog, "pullRequestCatalog is null");
        this.details = requireNonNull(details, "details is null");
        this.actions = requireNonNull(actions, "actions is null");
        this.logs = requireNonNull(logs, "logs is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
        this.rawLogs = requireNonNull(rawLogs, "rawLogs is null");
        this.ciRuns = requireNonNull(ciRuns, "ciRuns is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.ci = requireNonNull(ci, "ci is null");
    }

    @Override
    public Optional<PullRequestCiBridge> resolve(String taskId)
    {
        TaskId id = TaskId.valueOf(taskId);
        return eligibleTask(id).map(task -> {
            Optional<Function<CiDescriptionProposal, String>> propose = task.permissionPolicy() == AgentPermissionPolicy.CI_AUTOFIX
                    ? turns.findActive(id).map(turn -> proposal -> {
                        ci.getObject().proposeDescription(id, turn.id(), proposal);
                        return "Description proposal saved. ByteQuay will apply and verify it after this turn completes; this tool has not changed GitHub.";
                    }) : Optional.empty();
            return new PullRequestCiBridge(jobId -> read(id, jobId), (logId, offset) -> readRaw(id, logId, offset), PullRequestCiBridge.INSTRUCTION, propose,
                    task.permissionPolicy() == AgentPermissionPolicy.CI_AUTOFIX ? turns.findActive(id).map(turn -> (jobId, reason) -> {
                        ci.getObject().requestRerun(id, turn.id(), jobId, reason);
                        return "Rerun assessment saved. ByteQuay will consider one failed-job rerun after this turn; this tool has not rerun GitHub Actions.";
                    }) : Optional.empty());
        });
    }

    private Optional<AgentTask> eligibleTask(TaskId taskId)
    {
        return tasks.find(taskId)
                .filter(task -> task.state() == TaskState.OPEN)
                .filter(task -> (task.interactive() && !task.permissionPolicy().unattended()) || task.permissionPolicy() == AgentPermissionPolicy.CI_AUTOFIX)
                .filter(task -> task.taskWorktree().isPresent() && task.worktreeRemovedAt().isEmpty());
    }

    private Optional<Scope> scope(TaskId taskId)
    {
        return eligibleTask(taskId).flatMap(task -> {
            TaskWorktree worktree = task.taskWorktree().orElseThrow();
            if (task.permissionPolicy() == AgentPermissionPolicy.CI_AUTOFIX) {
                return ciRuns.findByAgentTaskId(taskId.toString())
                        .filter(run -> run.status() != CiAutofixRun.Status.FINISHED && run.scope().workspaceId().equals(worktree.workspaceId()))
                        .filter(run -> run.scope().physicalRepository().equals(physicalRepository(worktree)))
                        .map(run -> new Scope(worktree, RepoRef.of(run.scope().pullRequest().owner(), run.scope().pullRequest().repo()), run.scope().headRepository(),
                                ImmutableList.of(run.scope().headRef()), Optional.of(run.scope().pullRequest()), Optional.of(run.expectedRemoteHead())));
            }
            return workspaces.findById(worktree.workspaceId())
                    .filter(workspace -> workspace.selectedRepository().fullName().equalsIgnoreCase(physicalRepository(worktree).fullName()))
                    .map(workspace -> new Scope(worktree, workspace.monitoredRepository(), physicalRepository(worktree),
                            Stream.concat(Stream.of(worktree.generatedBranch()), worktrees.currentBranch(worktree).stream())
                                    .distinct().collect(toImmutableList()), Optional.empty(), Optional.empty()));
        });
    }

    private String read(TaskId taskId, Optional<Long> jobId)
    {
        requireNonNull(jobId, "jobId is null");
        try {
            Optional<Scope> resolved = scope(taskId);
            if (resolved.isEmpty()) {
                return message("unavailable", "The task is no longer open with an available worktree.");
            }
            Scope scope = resolved.orElseThrow();
            List<Candidate> candidates;
            if (scope.pullRequest().isPresent()) {
                CiHeadSnapshot head = actions.inspect(scope.pullRequest().orElseThrow());
                if (!head.pullRequest().equals(scope.pullRequest().orElseThrow()) || !matches(scope, head)) {
                    return message("stale", "The CI Autofix pull request no longer matches this task's authorized head.");
                }
                candidates = ImmutableList.of(new Candidate(-1, head));
            }
            else {
                // A cached match cannot rule out another matching PR created since the panel refreshed.
                for (String branch : scope.branches()) {
                    pullRequestCatalog.syncRepositoryBranch(scope.repository(), scope.worktree().physicalRepository().owner(), branch);
                }
                candidates = candidates(scope);
            }
            if (!scope(taskId).filter(scope::equals).isPresent()) {
                return message("unavailable", "The task or worktree changed while reading its pull request.");
            }
            if (candidates.isEmpty()) {
                return message("no_pull_request", "No open pull request is associated with this task's current or generated branch.");
            }
            if (candidates.size() != 1) {
                return message("ambiguous_pull_request", "More than one open pull request matches this task's branches.");
            }
            Candidate candidate = candidates.getFirst();
            return jobId.isPresent() ? readLog(taskId, scope, candidate.head(), jobId.orElseThrow()) : overview(taskId, scope, candidate);
        }
        catch (RuntimeException failure) {
            // Provider exceptions can contain authenticated URLs or response bodies; never pass them to the model.
            return message("unavailable", "ByteQuay could not verify the current pull request or read its CI evidence. Try again later.");
        }
    }

    private List<Candidate> candidates(Scope scope)
    {
        List<PullRequest> rows = scope.branches().stream()
                .flatMap(branch -> pullRequests.findByHeadRef(scope.repository().fullName(), branch).stream())
                .filter(row -> row.repo().equalsIgnoreCase(scope.repository().fullName()) && scope.branches().contains(row.headRef()))
                .filter(row -> "open".equalsIgnoreCase(row.state()))
                .distinct().limit(MAXIMUM_CANDIDATES + 1).collect(toImmutableList());
        if (rows.size() > MAXIMUM_CANDIDATES) {
            throw new IllegalStateException("Too many task pull request candidates");
        }
        ImmutableList.Builder<Candidate> matches = ImmutableList.builder();
        for (PullRequest row : rows) {
            PullRequestRef ref = PullRequestRef.of(scope.repository().owner(), scope.repository().repo(), row.number());
            CiHeadSnapshot head = actions.inspect(ref);
            if (head.pullRequest().equals(ref) && matches(scope, head)) {
                matches.add(new Candidate(row.id(), head));
            }
        }
        return matches.build();
    }

    private static boolean matches(Scope scope, CiHeadSnapshot head)
    {
        return head.open()
                && head.pullRequest().repoFullName().equalsIgnoreCase(scope.repository().fullName())
                && head.headRepository().fullName().equalsIgnoreCase(scope.headRepository().fullName())
                && scope.branches().contains(head.headRef())
                && scope.expectedHead().map(head.headSha()::equals).orElse(true);
    }

    private String overview(TaskId taskId, Scope scope, Candidate candidate)
    {
        CiHeadSnapshot head = candidate.head();
        ObjectNode result = anchor(head);
        ObjectNode ci = result.putObject("actions")
                .put("green", head.green()).put("pending", head.pending()).put("requiredChecksVerified", false)
                .put("workflowsOmitted", head.workflowRuns().size()).put("jobsOmitted", head.jobs().size());
        ArrayNode workflows = ci.putArray("workflows");
        int includedJobs = 0;
        for (CiWorkflowRun workflow : head.workflowRuns().stream()
                .sorted(Comparator.comparing((CiWorkflowRun run) -> run.jobs().stream().anyMatch(CiJob::actionableFailure)).reversed()).toList()) {
            ObjectNode value = workflow(workflow);
            ArrayNode jobs = value.putArray("jobs");
            workflows.add(value);
            if (json(result).length() > SUMMARY_BUDGET) {
                workflows.remove(workflows.size() - 1);
                break;
            }
            for (CiJob job : workflow.jobs().stream().sorted(Comparator.comparing(CiJob::actionableFailure).reversed()).toList()) {
                jobs.add(job(job));
                if (json(result).length() > SUMMARY_BUDGET) {
                    jobs.remove(jobs.size() - 1);
                    break;
                }
                includedJobs++;
            }
        }
        ci.put("workflowsOmitted", head.workflowRuns().size() - workflows.size());
        ci.put("jobsOmitted", head.jobs().size() - includedJobs);
        cachedChecks(result, candidate);
        if (scope.pullRequest().isPresent()) {
            String description = actions.readDescription(head.pullRequest());
            text(result, "description", description, 60_000);
        }
        if (!scope(taskId).filter(scope::equals).isPresent()) {
            return message("unavailable", "The task or worktree changed while reading its CI evidence.");
        }
        return json(result);
    }

    private void cachedChecks(ObjectNode result, Candidate candidate)
    {
        CiHeadSnapshot head = candidate.head();
        ObjectNode cached = result.putObject("cachedChecks").put("available", false).put("cached", true).put("checksComplete", false);
        if (candidate.rowId() < 0) {
            return;
        }
        Optional<StoredPrDetail> stored = details.find(candidate.rowId()).filter(value -> head.headSha().equals(value.raw().headSha()));
        if (stored.isEmpty()) {
            return;
        }
        StoredPrDetail detail = stored.orElseThrow();
        cached.put("available", true).put("checksComplete", detail.checkRunsComplete());
        cached.put("syncedAt", details.findSyncedAt(head.pullRequest().repoFullName(), head.pullRequest().number()).map(Instant::toString).orElse(null));
        cached.put("headSha", head.headSha()).put("checksOmitted", detail.checkRuns().size());
        ArrayNode checks = cached.putArray("checks");
        for (PrCheckRunState check : detail.checkRuns().stream()
                .sorted(Comparator.comparing((PrCheckRunState value) -> "success".equals(value.conclusion()))).toList()) {
            ObjectNode value = objectMapper.createObjectNode().put("id", check.githubId());
            text(value, "name", check.name(), 500);
            text(value, "status", check.status(), 100);
            text(value, "conclusion", check.conclusion(), 100);
            text(value, "source", check.source(), 100);
            text(value, "detailsUrl", check.detailsUrl(), 1000);
            checks.add(value);
            cached.put("checksOmitted", detail.checkRuns().size() - checks.size());
            if (json(result).length() > PullRequestCiBridge.MAXIMUM_OUTPUT_LENGTH - 1000) {
                checks.remove(checks.size() - 1);
                cached.put("checksOmitted", detail.checkRuns().size() - checks.size());
                break;
            }
        }
    }

    private String readLog(TaskId taskId, Scope scope, CiHeadSnapshot head, long jobId)
    {
        Optional<CiWorkflowRun> workflow = head.workflowRuns().stream()
                .filter(run -> run.jobs().stream().anyMatch(job -> job.id() == jobId && job.attempt() == run.attempt() && job.actionableFailure()))
                .findFirst();
        if (workflow.isEmpty()) {
            return message("unknown_job", "No current failed Actions job in this task's pull request matches that jobId.");
        }
        CiWorkflowRun run = workflow.orElseThrow();
        CiJob job = run.jobs().stream().filter(value -> value.id() == jobId).findFirst().orElseThrow();
        BoundedCiLog fetched = actions.readLog(new ReadCiLogQuery(taskId, head.pullRequest(), run, job, MAXIMUM_LOG_BYTES));
        String contents = fetched.rawLogId().map(logId -> rawLogs.read(taskId, logId))
                .orElseGet(() -> fetched.blob().map(blob -> StandardCharsets.UTF_8.decode(logs.readBlob(taskId, blob)).toString()).orElse(""));
        CiHeadSnapshot after = actions.inspect(head.pullRequest());
        if (!scope(taskId).filter(scope::equals).isPresent()) {
            return message("unavailable", "The task or worktree changed while reading the CI log.");
        }
        if (!matches(scope, after) || !head.pullRequest().equals(after.pullRequest())
                || !head.headSha().equals(after.headSha()) || !head.headRef().equals(after.headRef())
                || !head.baseRef().equals(after.baseRef())
                || !head.baseSha().equals(after.baseSha())
                || after.workflowRuns().stream().noneMatch(value -> value.id() == run.id() && value.attempt() == run.attempt()
                        && value.jobs().stream().anyMatch(current -> current.id() == job.id() && current.attempt() == job.attempt() && current.actionableFailure()))) {
            return message("stale", "The pull request head or workflow attempt changed while reading the log. Read the current CI summary again.");
        }
        ObjectNode result = anchor(head);
        ObjectNode log = result.putObject("log").put("jobId", job.id()).put("workflowRunId", run.id()).put("attempt", job.attempt())
                .put("downloadTruncated", fetched.truncated() || fetched.omittedBeginning()).put("unavailableReason", fetched.unavailableReason().orElse(null));
        log.put("rawLogId", fetched.rawLogId().orElse(null)).put("diagnosticsMatched", fetched.diagnosticsMatched());
        log.put("message", fetched.unavailableReason().orElseGet(() -> fetched.diagnosticsMatched()
                ? "Error blocks selected from the cached raw log. Use read_ci_raw_log with logId set to rawLogId for complete evidence."
                : "No diagnostic keywords or stack trace matched. Only the log tail (at most 32 KiB) is shown. "
                        + "Call read_ci_raw_log with logId set to rawLogId for the full cached log."));
        ArrayNode errors = log.putArray("terminalErrorLines");
        log.put("terminalErrorLinesOmitted", fetched.terminalErrorLines().size());
        for (String error : fetched.terminalErrorLines()) {
            errors.add(error);
            if (json(result).length() > SUMMARY_BUDGET) {
                errors.remove(errors.size() - 1);
                break;
            }
        }
        log.put("terminalErrorLinesOmitted", fetched.terminalErrorLines().size() - errors.size());
        int excerptBudget = PullRequestCiBridge.MAXIMUM_OUTPUT_LENGTH - json(result).length() - 1024;
        while (true) {
            CiLogExcerpt excerpt = CiLogExcerpt.from(contents, Math.min(32 * 1024, excerptBudget), excerptBudget);
            boolean compacted = fetched.compacted() || excerpt.compacted();
            log.put("excerpt", excerpt.text()).put("excerptOmittedBeginning", fetched.omittedBeginning())
                    .put("excerptOmittedEnd", fetched.truncated()).put("excerptCompacted", compacted)
                    .put("truncated", fetched.truncated() || fetched.omittedBeginning() || compacted || errors.size() < fetched.terminalErrorLines().size());
            if (json(result).length() <= PullRequestCiBridge.MAXIMUM_OUTPUT_LENGTH) {
                return json(result);
            }
            excerptBudget = Math.max(1, excerptBudget / 2);
        }
    }

    private String readRaw(TaskId taskId, String logId, long offset)
    {
        try {
            Optional<Scope> before = scope(taskId);
            if (before.isEmpty()) {
                return message("unavailable", "The task worktree is no longer available.");
            }
            int budget = 32 * 1024;
            while (true) {
                CiRawLogPage page = rawLogs.page(taskId, logId, offset, budget);
                if (!scope(taskId).equals(before)) {
                    return message("unavailable", "The task worktree changed while reading its cached raw log.");
                }
                boolean complete = page.nextOffset() == page.totalBytes();
                ObjectNode result = objectMapper.createObjectNode().put("status", "available").put("untrusted", true)
                        .put("logId", logId).put("offset", offset).put("totalBytes", page.totalBytes()).put("complete", complete).put("text", page.text());
                if (complete) {
                    result.putNull("nextOffset");
                }
                else {
                    result.put("nextOffset", page.nextOffset());
                }
                String json = json(result);
                if (json.length() <= PullRequestCiBridge.MAXIMUM_OUTPUT_LENGTH) {
                    return json;
                }
                budget = Math.max(4, budget / 2);
            }
        }
        catch (RuntimeException failure) {
            return message("unavailable", "No readable cached raw log at this offset. Request the error log first, then use its logId and returned nextOffset.");
        }
    }

    private ObjectNode anchor(CiHeadSnapshot head)
    {
        ObjectNode result = objectMapper.createObjectNode().put("status", "available").put("untrusted", true).put("observedAt", Instant.now().toString());
        ObjectNode pr = result.putObject("pullRequest").put("number", head.pullRequest().number());
        text(pr, "repository", head.pullRequest().repoFullName(), 500);
        text(pr, "url", head.url(), 1000);
        text(pr, "headRepository", head.headRepository().fullName(), 500);
        text(pr, "headRef", head.headRef(), 500);
        pr.put("headSha", head.headSha()).put("baseSha", head.baseSha());
        text(pr, "baseRef", head.baseRef(), 500);
        return result;
    }

    private ObjectNode workflow(CiWorkflowRun run)
    {
        ObjectNode result = objectMapper.createObjectNode().put("id", run.id()).put("attempt", run.attempt())
                .put("status", run.status()).put("conclusion", run.conclusion().orElse(null));
        text(result, "name", run.name(), 500);
        return result;
    }

    private ObjectNode job(CiJob job)
    {
        ObjectNode result = objectMapper.createObjectNode().put("id", job.id()).put("attempt", job.attempt())
                .put("status", job.status()).put("conclusion", job.conclusion().orElse(null));
        text(result, "name", job.name(), 500);
        ArrayNode steps = result.putArray("failedSteps");
        job.failedSteps().stream().limit(5).forEach(step -> steps.add(step.name()));
        result.put("failedStepsOmitted", Math.max(0, job.failedSteps().size() - steps.size()));
        return result;
    }

    private String message(String status, String message)
    {
        return json(objectMapper.createObjectNode().put("status", status).put("untrusted", true).put("message", message));
    }

    private static void text(ObjectNode node, String field, String value, int maximum)
    {
        if (value == null) {
            node.putNull(field);
            return;
        }
        int end = Math.min(value.length(), maximum);
        if (end < value.length() && end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        node.put(field, value.substring(0, end));
        node.put(field + "Truncated", end < value.length());
    }

    private String json(ObjectNode value)
    {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to encode pull request CI evidence", failure);
        }
    }

    private static RepoRef physicalRepository(TaskWorktree worktree)
    {
        return RepoRef.of(worktree.physicalRepository().owner(), worktree.physicalRepository().name());
    }

    private record Scope(
            TaskWorktree worktree, RepoRef repository, RepoRef headRepository, List<String> branches,
            Optional<PullRequestRef> pullRequest, Optional<String> expectedHead) {}

    private record Candidate(long rowId, CiHeadSnapshot head) {}
}
