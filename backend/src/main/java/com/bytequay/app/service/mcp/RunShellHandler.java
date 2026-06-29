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
package com.bytequay.app.service.mcp;

import com.bytequay.app.beans.mcp.RunShellArgs;
import com.bytequay.app.beans.mcp.RunShellResult;
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.local.ShellRunner;
import com.bytequay.app.service.threads.McpPermissionGate;
import com.bytequay.app.service.threads.ThreadService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;

/**
 * Handles {@code run_shell}: the escape-hatch tool. Routes the call
 * through the same permission gate the CLI uses for
 * {@code approval_prompt} — a per-call user click. On Allow the
 * runner spawns the process in the active task's worktree under
 * the policy enumerated in {@link ShellRunner}; on Deny the agent
 * gets a deny envelope.
 */
@Component
public class RunShellHandler
        implements ToolHandler
{
    private static final Logger log = LoggerFactory.getLogger(RunShellHandler.class);

    /** Short MCP tool name; the dispatcher maps this verbatim. */
    public static final String NAME = "run_shell";

    /** Cap on the command string we surface in the permission
     *  prompt — anything longer is truncated with an ellipsis so the
     *  card stays readable. */
    private static final int PROMPT_SUMMARY_CAP = 200;

    private final TaskStore taskStore;
    private final McpPermissionGate gate;
    private final ThreadService threads;
    private final ShellRunner shellRunner;
    private final McpResponses responses;

    public RunShellHandler(
            TaskStore taskStore,
            McpPermissionGate gate,
            ThreadService threads,
            ShellRunner shellRunner,
            McpResponses responses)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.gate = requireNonNull(gate, "gate is null");
        this.threads = requireNonNull(threads, "threads is null");
        this.shellRunner = requireNonNull(shellRunner, "shellRunner is null");
        this.responses = requireNonNull(responses, "responses is null");
    }

    @Override
    public String toolName()
    {
        return NAME;
    }

    @Override
    public void handle(ToolDispatchContext ctx, DeferredResult<JsonNode> deferred)
    {
        JsonNode id = ctx.id();
        RunShellArgs args;
        try {
            args = responses.bindArgs(ctx.params().arguments(), RunShellArgs.class);
        }
        catch (JsonProcessingException e) {
            deferred.setResult(responses.error(id, -32602, "invalid run_shell args: " + e.getMessage()));
            return;
        }
        String command = (args.command() == null ? "" : args.command()).trim();
        if (command.isEmpty()) {
            deferred.setResult(responses.toolResponse(id, responses.deny("command is required")));
            return;
        }
        // Resolve the task from the turn's stamped task_id — not a thread-level
        // "active task" guess, which excluded shipped (IN_REVIEW) tasks and so
        // denied run_shell on a CI-fix / address-comments turn ("not active").
        Optional<Task> active = ctx.taskId() == null
                ? Optional.empty()
                : taskStore.findTaskById(ctx.taskId());
        if (active.isEmpty() || active.get().worktreePath() == null
                || active.get().worktreePath().isBlank()) {
            deferred.setResult(responses.toolResponse(id,
                    responses.deny("run_shell requires a task with a worktree")));
            return;
        }
        Path worktree = Path.of(active.get().worktreePath());
        String callId = UUID.randomUUID().toString();

        // Surface a permission card in the conversation pane so the
        // user sees the exact cmdline before deciding. Same shape
        // the approval_prompt path uses for built-in Bash / Edit.
        CompletableFuture<PermissionDecision> decisionFuture = gate.register(callId, NAME, ctx.agentKey());
        decisionFuture.whenComplete((decision, ex) ->
                completeRunShell(deferred, id, decision, ex, worktree, command));
        try {
            threads.notifyPermissionRequested(ctx.threadId(), ctx.agentKey(), callId, NAME,
                    "cmd: " + truncate(command, PROMPT_SUMMARY_CAP));
        }
        catch (RuntimeException e) {
            log.warn("Failed to surface run_shell prompt for thread {}: {}",
                    ctx.threadId(), e.getMessage());
        }
        deferred.onTimeout(() -> {
            gate.cancel(callId);
            deferred.setResult(responses.toolResponse(id,
                    responses.deny("timed out waiting for the user")));
        });
        deferred.onCompletion(() -> gate.cancel(callId));
    }

    private void completeRunShell(
            DeferredResult<JsonNode> deferred,
            JsonNode id,
            PermissionDecision decision,
            Throwable ex,
            Path worktree,
            String command)
    {
        if (ex != null) {
            deferred.setResult(responses.toolResponse(id,
                    responses.deny("interrupted: " + ex.getMessage())));
            return;
        }
        if (decision != PermissionDecision.ALLOW) {
            deferred.setResult(responses.toolResponse(id, responses.deny("user denied")));
            return;
        }
        try {
            ShellRunner.Result result = shellRunner.run(worktree, command);
            RunShellResult out = new RunShellResult(
                    result.ran(),
                    result.exitCode(),
                    result.truncated(),
                    result.output(),
                    result.error());
            deferred.setResult(responses.plainText(id, responses.mapper().writeValueAsString(out)));
        }
        catch (JsonProcessingException je) {
            throw new IllegalStateException("failed to serialise run_shell result", je);
        }
        catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            deferred.setResult(responses.toolResponse(id,
                    responses.deny("interrupted: " + ie.getMessage())));
        }
        catch (RuntimeException e) {
            deferred.setResult(responses.toolResponse(id,
                    responses.deny("run_shell failed: " + e.getMessage())));
        }
    }

    private static String truncate(String s, int cap)
    {
        return s.length() <= cap ? s : s.substring(0, cap - 3) + "…";
    }
}
