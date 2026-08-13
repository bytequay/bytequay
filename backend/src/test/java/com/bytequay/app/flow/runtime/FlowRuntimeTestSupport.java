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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/** Test-only bridge for pre-provisioning runtime fixtures. */
public final class FlowRuntimeTestSupport
{
    private FlowRuntimeTestSupport() {}

    /** Test-only legacy fixture behind the private inspected-admission flag. */
    public static FlowRuntimeRecords.WriterFence acquireWriterFixture(
            FlowRuntime runtime,
            Claim claim,
            FlowRuntimeRecords.AgentRole role,
            FlowRuntimeRecords.WorktreeSnapshot snapshot,
            Duration ttl)
    {
        try {
            Method method = FlowRuntime.class.getDeclaredMethod(
                    "acquireWriterLease", Claim.class,
                    FlowRuntimeRecords.AgentRole.class,
                    FlowRuntimeRecords.WorktreeSnapshot.class,
                    Duration.class, boolean.class);
            method.setAccessible(true);
            return (FlowRuntimeRecords.WriterFence) method.invoke(
                    runtime, claim, role, snapshot, ttl, true);
        }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IllegalStateException(failure.getCause());
        }
        catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static InProcessWriterAgentSupervisor.ExecutionHandle
            launchWriterFixture(
                    InProcessWriterAgentSupervisor supervisor,
                    FlowRuntime runtime,
                    String runId,
                    Claim claim,
                    FlowRuntimeRecords.WriterFence fence,
                    Function<InProcessWriterAgentSupervisor.WriterToolCapability,
                            InProcessWriterAgentSupervisor.AgentCompletion>
                            body)
    {
        return supervisor.launch(
                runId, claim, fence, "RUNTIME_AGENT_RESULT",
                (stoppedRun, stoppedClaim, stoppedFence, completion) ->
                        finishWriterFixture(
                                runtime, stoppedRun, stoppedClaim,
                                stoppedFence, completion),
                body);
    }

    public static InProcessWriterAgentSupervisor.ExecutionHandle
            launchWriterFixture(
                    InProcessWriterAgentSupervisor supervisor,
                    FlowRuntime runtime,
                    String runId,
                    Claim claim,
                    FlowRuntimeRecords.WriterFence fence,
                    String finalizerKind,
                    InProcessWriterAgentSupervisor.StoppedFinalizer finalizer,
                    Function<InProcessWriterAgentSupervisor.WriterToolCapability,
                            InProcessWriterAgentSupervisor.AgentCompletion>
                            body)
    {
        return supervisor.launch(
                runId, claim, fence, finalizerKind, finalizer, body);
    }

    public static FlowRuntimeRecords.AgentResult finishWriterFixture(
            FlowRuntime runtime,
            String runId,
            Claim claim,
            FlowRuntimeRecords.WriterFence fence,
            InProcessWriterAgentSupervisor.AgentCompletion completion)
    {
        try {
            Method method = FlowRuntime.class.getDeclaredMethod(
                    "finishWriterAgentRun",
                    String.class,
                    Claim.class,
                    FlowRuntimeRecords.WriterFence.class,
                    FlowRuntimeRecords.TerminalOutcome.class,
                    String.class,
                    String.class,
                    String.class,
                    String.class,
                    CiFixOutcome.class,
                    String.class,
                    String.class);
            method.setAccessible(true);
            return (FlowRuntimeRecords.AgentResult) method.invoke(
                    runtime,
                    runId,
                    claim,
                    fence,
                    completion.terminalOutcome(),
                    completion.finalContent(),
                    completion.errorRef(),
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IllegalStateException(failure.getCause());
        }
        catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static FlowRuntimeRecords.PullRequestSubject bindGitHubFixture(
            FlowRuntime runtime,
            String prId,
            String head,
            FlowRuntimeRecords.GitHubRepositoryLocator base,
            FlowRuntimeRecords.GitHubRepositoryLocator headRepository,
            long number,
            String nodeId,
            String url,
            String receiptId)
    {
        try {
            Method method = FlowRuntime.class.getDeclaredMethod(
                    "bindGitHubRemoteIdentity", String.class, String.class,
                    FlowRuntimeRecords.GitHubRepositoryLocator.class,
                    FlowRuntimeRecords.GitHubRepositoryLocator.class,
                    long.class, String.class, String.class, String.class);
            method.setAccessible(true);
            return (FlowRuntimeRecords.PullRequestSubject) method.invoke(
                    runtime, prId, head, base, headRepository, number, nodeId,
                    url, receiptId);
        }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IllegalStateException(failure.getCause());
        }
        catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static Task startTask(
            FlowRuntime runtime,
            String requestKey,
            String repositoryId,
            String goal,
            String requestedWorktree)
    {
        try {
            if (runtime.taskForRequestKey(requestKey).isPresent()) {
                return new TaskProvisioning(
                        dataSource(runtime),
                        runtime,
                        ignored -> {
                            throw new AssertionError(
                                    "replay consulted repository catalog");
                        },
                        Clock.systemUTC())
                        .startTask(requestKey, repositoryId, goal);
            }
            String key = stableId("task", requestKey).split(":", 2)[1];
            Path requested = Path.of(requestedWorktree).toAbsolutePath();
            boolean realWorktree = Files.exists(requested);
            Path owner = realWorktree
                    ? requested.getParent()
                    : Files.createTempDirectory("flow-runtime-test-");
            Path root = owner.resolve("test-worktrees");
            Files.createDirectories(root);
            Path derived = root.resolve(key);
            Path repository = owner.resolve("test-repository-" + key);
            Path common = repository.resolve(".git");
            if (realWorktree) {
                repository = Path.of(git(requested, "worktree", "list", "--porcelain")
                        .lines().filter(line -> line.startsWith("worktree "))
                        .findFirst().orElseThrow().substring("worktree ".length()));
                common = Path.of(git(requested, "rev-parse",
                        "--path-format=absolute", "--git-common-dir"));
                git(requested, "worktree", "move",
                        requested.toString(), derived.toString());
                git(derived, "branch", "-m", "bytequay/" + key);
            }
            else {
                Files.createDirectories(common);
            }
            Path frozenRepository = repository;
            Path frozenCommon = common;
            TaskProvisioning provisioning = new TaskProvisioning(
                    dataSource(runtime),
                    runtime,
                    ignored -> new TaskProvisioning.RepositoryConfig(
                            repositoryId,
                            "octocat",
                            "bytequay",
                            frozenRepository,
                            frozenCommon,
                            "origin",
                            "refs/remotes/origin/main",
                            root),
                    Clock.systemUTC());
            return provisioning.startTask(requestKey, repositoryId, goal);
        }
        catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        catch (Exception failure) {
            throw failure instanceof RuntimeException runtimeFailure
                    ? runtimeFailure : new IllegalStateException(failure);
        }
    }

    public static void provisionTask(
            FlowRuntime runtime, Claim claim, String baseSha)
    {
        requireObjectId(baseSha);
        try {
            Operation operation = runtime.operation(claim.operationId())
                    .orElseThrow();
            Task task = runtime.task(operation.taskId()).orElseThrow();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource(runtime));
            jdbc.update(
                    """
                    INSERT OR IGNORE INTO flow_runtime_provision_subject (
                        operation_id, task_id, launch_digest, base_sha,
                        target_base_ref, mutation_digest, bound_at
                    ) VALUES (?, ?, ?, ?, 'refs/heads/main', ?, 0)
                    """,
                    operation.operationId(),
                    task.taskId(),
                    task.launchDigest(),
                    baseSha,
                    "test-mutation:" + baseSha);
            TaskProvisioning.ResolvedSubject subject =
                    new TaskProvisioning.ResolvedSubject(
                            operation.operationId(),
                            task.taskId(),
                            task.launchDigest(),
                            baseSha,
                            "refs/heads/main",
                            "test-mutation:" + baseSha,
                            Instant.EPOCH);
            Constructor<TaskProvisioning.ProvisionedWorktree> constructor =
                    TaskProvisioning.ProvisionedWorktree.class
                            .getDeclaredConstructor(
                                    TaskProvisioning.ResolvedSubject.class,
                                    FlowWorktreeInspector.Inspection.class);
            constructor.setAccessible(true);
            var proof = constructor.newInstance(
                    subject,
                    new FlowWorktreeInspector.Inspection(
                            baseSha, "test-tree", "test-diff", false));
            runtime.provisionTask(claim, proof);
        }
        catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * TEST-ONLY fixture. It supplies the deferred INITIAL_PUBLISH reviewer
     * lineage without exposing a production runtime bypass.
     */
    public static InitialPublishLineage seedInitialPublishLineage(
            FlowRuntime runtime, String prId)
    {
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource(runtime));
            var pr = runtime.pullRequest(prId).orElseThrow();
            Task task = runtime.task(pr.taskId()).orElseThrow();
            var change = runtime.currentChangeSet(task.taskId()).orElseThrow();
            var base = runtime.currentBaseRevision(task.taskId()).orElseThrow();
            var parent = jdbc.query("SELECT run_id, operation_id, session_id FROM flow_runtime_agent_run "
                            + "WHERE operation_id = (SELECT operation_id FROM flow_runtime_change_set_revision "
                            + "WHERE change_set_revision_id = ?) AND role = 'TASK_AGENT'",
                    (row, number) -> new String[] {
                        row.getString("run_id"),
                        row.getString("operation_id"),
                        row.getString("session_id")},
                    change.changeSetRevisionId()).getFirst();
            String localPolicy = jdbc.queryForObject("SELECT policy_revision_id "
                    + "FROM flow_runtime_local_check_policy_current WHERE repository_id = ?",
                    String.class, task.repositoryId());
            String profile = jdbc.queryForObject("SELECT profile_id FROM flow_runtime_local_check_profile "
                    + "WHERE policy_revision_id = ? ORDER BY profile_id LIMIT 1", String.class, localPolicy);
            String ciPolicy = stableId("test-initial-ci-policy", task.taskId());
            jdbc.update("INSERT OR IGNORE INTO flow_ci_policy_revision "
                            + "(policy_revision_id, repository_id, scope_key, target_base_ref, sequence, resolution, source_ref, source_digest, unavailable_reason_ref, required_check_selectors_json, accepted_conclusions_json, recorded_at) "
                            + "VALUES (?, ?, ?, ?, 1, 'RESOLVED', 'test', 'test', NULL, '[]', '[\"SUCCESS\"]', 0)",
                    ciPolicy, task.repositoryId(), pr.scopeKey(), pr.targetBaseRef());
            jdbc.update("INSERT OR IGNORE INTO flow_ci_policy_current "
                            + "(repository_id, scope_key, policy_revision_id) VALUES (?, ?, ?)",
                    task.repositoryId(), pr.scopeKey(), ciPolicy);
            String draftDigest = stableId("pr-draft:v1", prId, "1",
                    change.changeSetRevisionId(), change.headSha(), "Initial draft", "body");
            String draftId = stableId("pr-draft-revision:v1", draftDigest);
            String priorIdentity = pr.remoteIdentityId();
            jdbc.update("UPDATE flow_runtime_pr SET remote_identity_id = NULL, current_remote_head = NULL, current_draft_revision_id = NULL WHERE pr_id = ?", prId);
            if (priorIdentity != null) {
                jdbc.update("DELETE FROM flow_runtime_remote_identity "
                        + "WHERE remote_identity_id = ?", priorIdentity);
            }
            jdbc.update("INSERT OR IGNORE INTO flow_runtime_pr_draft_revision "
                            + "(draft_revision_id, pr_id, sequence, change_set_revision_id, head_sha, title, body, draft_digest, created_by_run_id, created_at) "
                            + "VALUES (?, ?, 1, ?, ?, 'Initial draft', 'body', ?, ?, 0)",
                    draftId, prId, change.changeSetRevisionId(), change.headSha(), draftDigest, parent[0]);
            jdbc.update("UPDATE flow_runtime_pr SET current_draft_revision_id = ? WHERE pr_id = ?", draftId, prId);
            String checkId = stableId("test-initial-check", task.taskId());
            jdbc.update("INSERT OR IGNORE INTO flow_runtime_local_check_run "
                            + "(check_run_id, task_id, change_set_revision_id, policy_revision_id, profile_id, operation_id, agent_run_id, command_json, working_directory, attempt_sequence, observed_start_head, observed_end_head, started_at, completed_at, conclusion, exit_code, unavailable_reason_code, output_ref, output_text, output_truncated, tracked_tree_clean_before, tracked_tree_clean_after) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, '[\"true\"]', '.', 1, ?, ?, 0, 0, 'PASSED', 0, NULL, ?, '', 0, 1, 1)",
                    checkId, task.taskId(), change.changeSetRevisionId(), localPolicy, profile,
                    parent[1], parent[0], change.headSha(), change.headSha(), "output:" + checkId);
            String session = stableId("test-initial-reviewer-session", task.taskId());
            String request = stableId("test-initial-review-request", task.taskId());
            String reviewerOperation = stableId("test-initial-review-operation", task.taskId());
            String reviewerRun = stableId("test-initial-review-run", task.taskId());
            String reviewerResult = stableId("test-initial-review-result", task.taskId());
            jdbc.update("INSERT OR IGNORE INTO flow_runtime_agent_session "
                            + "(session_id, task_id, role, state, last_run_id, close_reason, created_at, updated_at) VALUES (?, ?, 'ADVERSARIAL_REVIEWER', 'IDLE', ?, NULL, 0, 0)",
                    session, task.taskId(), reviewerRun);
            jdbc.update("INSERT OR IGNORE INTO flow_runtime_operation "
                            + "(operation_id, owner_kind, owner_id, task_id, kind, subject_digest, input_ref, work_watermark, state, attempt, result_ref, created_at) "
                            + "VALUES (?, 'REVIEW_REQUEST', ?, ?, 'RUN_REVIEWER', ?, ?, NULL, 'SUCCEEDED', 1, ?, 0)",
                    reviewerOperation, request, task.taskId(), "review-subject:" + request,
                    "review-request:" + request, reviewerResult);
            jdbc.update("INSERT OR IGNORE INTO flow_runtime_agent_run "
                            + "(run_id, operation_id, session_id, role, head_sha, prompt_manifest_ref, capability_set_ref, input_ref, input_change_set_revision_id, input_remote_head_sha, wake_kind, intended_gate_kind, state, failure_reason_code, created_at, started_at, completed_at) "
                            + "VALUES (?, ?, ?, 'ADVERSARIAL_REVIEWER', ?, 'prompt', 'capability', 'review', NULL, NULL, NULL, NULL, 'COMPLETED', NULL, 0, 0, 0)",
                    reviewerRun, reviewerOperation, session, change.headSha());
            jdbc.update("INSERT OR IGNORE INTO flow_runtime_agent_result "
                            + "(result_id, run_id, terminal_outcome, final_content, error_ref, stop_proof_ref, stored_at) VALUES (?, ?, 'COMPLETED', '', NULL, 'test-stop', 0)",
                    reviewerResult, reviewerRun);
            jdbc.update("INSERT OR IGNORE INTO flow_runtime_reviewer_request "
                            + "(request_id, task_id, parent_operation_id, parent_run_id, reviewer_operation_id, repository_root, base_head_sha, reviewed_head_sha, remote_head_sha, origin_ci_fix_pending_id, origin_ci_fix_source_kind, origin_ci_fix_source_id, change_set_revision_id, local_check_policy_revision_id, head_tree_digest, diff_digest, intended_gate_kind, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, ?, ?, ?, ?, 'INITIAL_PUBLISH', 0)",
                    request, task.taskId(), parent[1], parent[0], reviewerOperation,
                    task.repositoryRoot(), base.baseSha(), change.headSha(),
                    change.changeSetRevisionId(), localPolicy, change.headTreeDigest(), change.diffDigest());
            jdbc.update("INSERT OR IGNORE INTO flow_runtime_reviewer_check_ref "
                            + "(request_id, ordinal, check_run_ref) VALUES (?, 0, ?)", request, checkId);
            String readyInbox = stableId(
                    "test-initial-ready-inbox", task.taskId());
            String readyOperation = stableId(
                    "test-initial-ready-operation", task.taskId());
            String readyRun = stableId(
                    "test-initial-ready-run", task.taskId());
            long watermark = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(work_watermark), 0) + 1 "
                            + "FROM flow_runtime_inbox WHERE task_id = ?",
                    Long.class, task.taskId());
            jdbc.update("INSERT OR IGNORE INTO flow_runtime_operation "
                            + "(operation_id, owner_kind, owner_id, task_id, kind, subject_digest, input_ref, work_watermark, state, attempt, result_ref, created_at) "
                            + "VALUES (?, 'AGENT_RUN', ?, ?, 'RUN_TASK_TURN', ?, ?, ?, 'CLAIMED', 1, NULL, 0)",
                    readyOperation, reviewerRun, task.taskId(),
                    "test-initial-ready-subject:" + reviewerResult,
                    "inbox:" + readyInbox, watermark);
            jdbc.update("INSERT OR IGNORE INTO flow_runtime_dispatch_ticket "
                            + "(operation_id, not_before, claim_owner, claim_expires_at, claim_generation, claim_token, priority, delivery_state) "
                            + "VALUES (?, 0, 'test-initial-ready', 60000, 1, 'test-initial-ready-token', 100, 'CLAIMED')",
                    readyOperation);
            jdbc.update("INSERT OR IGNORE INTO flow_runtime_inbox "
                            + "(inbox_id, task_id, pr_id, source, external_key, revision, kind, subject_head, payload_ref, agent_result_id, intended_gate_kind, work_watermark, observed_at, selected_by_operation_id, handled_by_operation_id, terminal_reason) "
                            + "VALUES (?, ?, ?, 'REVIEWER', ?, '1', 'AGENT_RESULT_READY', ?, ?, ?, 'INITIAL_PUBLISH', ?, 0, ?, NULL, NULL)",
                    readyInbox, task.taskId(), prId, reviewerRun,
                    change.headSha(),
                    "reviewer-request:" + request + ":result:"
                            + reviewerResult,
                    reviewerResult, watermark, readyOperation);
            jdbc.update("INSERT OR IGNORE INTO flow_runtime_agent_run "
                            + "(run_id, operation_id, session_id, role, head_sha, prompt_manifest_ref, capability_set_ref, input_ref, input_change_set_revision_id, input_remote_head_sha, wake_kind, intended_gate_kind, state, failure_reason_code, created_at, started_at, completed_at) "
                            + "VALUES (?, ?, ?, 'TASK_AGENT', ?, 'prompt:initial-ready', 'capability:initial-ready', ?, ?, NULL, 'AGENT_RESULT_READY', 'INITIAL_PUBLISH', 'RUNNING', NULL, 0, 0, NULL)",
                    readyRun, readyOperation, parent[2], change.headSha(),
                    "inbox:" + readyInbox, change.changeSetRevisionId());
            jdbc.update("UPDATE flow_runtime_agent_session "
                            + "SET state = 'RUNNING', last_run_id = ?, updated_at = 0 "
                            + "WHERE session_id = ? AND state = 'IDLE'",
                    readyRun, parent[2]);
            jdbc.update("UPDATE flow_runtime_task "
                            + "SET selected_writer_operation_id = ? "
                            + "WHERE task_id = ? AND selected_writer_operation_id IS NULL",
                    readyOperation, task.taskId());
            return new InitialPublishLineage(readyRun, request, reviewerRun,
                    reviewerResult, draftId, checkId, ciPolicy);
        }
        catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    public record InitialPublishLineage(String parentRunId, String requestId,
            String reviewerRunId, String reviewerResultId, String draftRevisionId,
            String checkRunId, String requiredCiPolicyRevisionId) {}

    private static DataSource dataSource(FlowRuntime runtime)
            throws ReflectiveOperationException
    {
        Field field = FlowRuntime.class.getDeclaredField("jdbc");
        field.setAccessible(true);
        return requireNonNull(((JdbcTemplate) field.get(runtime)).getDataSource());
    }

    private static String git(Path directory, String... arguments)
            throws Exception
    {
        String[] command = new String[arguments.length + 3];
        command[0] = "/usr/bin/git";
        command[1] = "-C";
        command[2] = directory.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .strip();
        if (process.waitFor() != 0) {
            String error = new String(
                    process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8).strip();
            throw new IllegalStateException(
                    "test Git command failed: "
                            + String.join(" ", command) + ": " + error);
        }
        return output;
    }

    private static String stableId(String domain, String... values)
            throws Exception
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        frame(digest, domain);
        for (String value : values) {
            frame(digest, value);
        }
        return domain + ":" + HexFormat.of().formatHex(digest.digest());
    }

    private static void frame(MessageDigest digest, String value)
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length)
                .getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static void requireObjectId(String value)
    {
        if (value == null
                || !value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new IllegalArgumentException("not a full object ID");
        }
    }
}
