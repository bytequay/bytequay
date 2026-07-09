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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Wraps the {@code codex} CLI as a {@link ThreadAgent}.
 *
 * <p>Each {@link #send} spawns {@code codex exec --json} (or {@code codex
 * exec resume <session-id> --json} on a follow-up turn) with the prompt
 * as the trailing argv argument, and reads its JSONL stdout through a
 * {@link CodexJsonParser}. All the provider-agnostic lifecycle lives in
 * {@link AbstractCliThreadAgent}; this subclass supplies only the Codex
 * argv and the prompt-via-argv delivery.
 *
 * <p>Unlike the Claude agent, {@code codex exec} is non-interactive:
 * tool calls are governed by its {@code --sandbox} setting rather than an
 * MCP approval prompt, so there's no permission gate to wire and no
 * per-session temp files to clean up. The role-skill and workspace-memory
 * context — which the Claude agent passes via {@code
 * --append-system-prompt} flags Codex doesn't have — are folded into the
 * first turn's prompt instead.
 */
public class CodexCliThreadAgent
        extends AbstractCliThreadAgent
{
    /** {@code codex}'s default binary name on PATH. */
    private static final String DEFAULT_BINARY = "codex";

    /** Filesystem-write sandbox: Codex may read/write inside the working
     *  directory (the task's worktree) but not touch the wider system.
     *  The right default for a coding task agent; tighter than
     *  {@code danger-full-access}, looser than {@code read-only}. */
    private static final String SANDBOX_MODE = "workspace-write";

    private final Supplier<String> workspaceMemoryProvider;
    private final String roleSkillText;
    /** Optional Codex reasoning-effort override for planning-heavy sessions. */
    private final String reasoningEffort;

    public CodexCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            CodexJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            String roleSkillText,
            Task boundTask)
    {
        this(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, roleSkillText, DEFAULT_BINARY, (String) null,
                boundTask, (String) null, (String) null);
    }

    /**
     * Stage-scoped constructor carrying the resolved work-model cascade's
     * model id (stage → task → thread → workspace → global), so a stage or
     * task override reaches the {@code -m} spawn arg instead of only the
     * thread's frozen {@link Thread#model()}. Null/blank means no override —
     * falls back to {@code thread.model()} like the constructor above.
     */
    public CodexCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            CodexJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            String roleSkillText,
            Task boundTask,
            String modelOverride)
    {
        this(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, roleSkillText, DEFAULT_BINARY, (String) null,
                boundTask, modelOverride, (String) null);
    }

    /** Trunk-mode constructor: no focused Task, cwd defaulting to {@code
     *  trunkCwd}, resuming {@code thread.agentSessionId}. */
    public CodexCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            CodexJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            String roleSkillText,
            String trunkCwd,
            @SuppressWarnings("unused") TrunkMode trunkMode)
    {
        this(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, roleSkillText, DEFAULT_BINARY, trunkCwd,
                (Task) null, (String) null, (String) null);
    }

    /** Trunk-mode constructor with an explicit provider-native effort. */
    public CodexCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            CodexJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            String roleSkillText,
            String trunkCwd,
            @SuppressWarnings("unused") TrunkMode trunkMode,
            String reasoningEffort)
    {
        this(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, roleSkillText, DEFAULT_BINARY, trunkCwd,
                (Task) null, (String) null, reasoningEffort);
    }

    /** Marker enum disambiguating the trailing-string constructor
     *  overloads. {@link #ENABLED} = trunk mode. */
    public enum TrunkMode { ENABLED }

    CodexCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            CodexJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            String roleSkillText,
            String binary,
            Task boundTask)
    {
        this(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, roleSkillText, binary, (String) null,
                boundTask, (String) null, (String) null);
    }

    private CodexCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            CodexJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            String roleSkillText,
            String binary,
            String trunkCwd,
            Task boundTask,
            String modelOverride,
            String reasoningEffort)
    {
        super(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                binary, trunkCwd, boundTask, modelOverride);
        this.workspaceMemoryProvider = requireNonNull(workspaceMemoryProvider, "workspaceMemoryProvider is null");
        this.roleSkillText = roleSkillText;
        this.reasoningEffort = reasoningEffort;
    }

    @Override
    protected ProcessBuilder buildCommand(String userInput)
    {
        ImmutableList.Builder<String> argv = ImmutableList.<String>builder()
                .add(binary)
                // Point Codex at our per-thread MCP server so it gets the same
                // bytequay tools as the Claude agent — create_task, read_task,
                // read_pr, … Without this a Codex trunk has no way
                // to cut a task in our system and improvises with its own
                // internal sub-agent fork (invisible to the task UI). `-c` is a
                // global override merged on top of the user's config.toml, so
                // it adds the server without disturbing their auth/settings.
                // NOTE: needs a Codex build with HTTP (streamable) MCP support;
                // if a turn errors with an MCP/config complaint, the surfaced
                // stderr will say so and this key/transport may need tuning.
                .add("-c", "mcp_servers.bytequay.url=\"" + mcpServerUrl() + "\"")
                // Auto-approve this server's tool calls. `codex exec` is
                // non-interactive and we close its stdin, so the per-MCP-call
                // approval prompt reads EOF and Codex records it as "cancelled
                // by the user" — which silently killed every create_task. The
                // value must be "approve" (unconditional skip), NOT "auto":
                // "auto" only skips the prompt when Codex has full-disk-write,
                // which our --sandbox workspace-write turn deliberately lacks,
                // so it falls back to prompting → EOF → cancel. "approve" lets
                // our tools run without an approver while leaving Codex's own
                // filesystem sandbox untouched; we only trust our own
                // localhost sidecar, not a blanket approvals/sandbox bypass.
                .add("-c", "mcp_servers.bytequay.default_tools_approval_mode=\"approve\"")
                // Use the rmcp client. Codex's default MCP client connects to
                // an HTTP server (we see `initialize`) but never enumerates its
                // tools (no `tools/list`), so the model never sees create_task
                // and improvises with its built-in multi_agent spawn. The rmcp
                // client does the streamable-HTTP tool discovery.
                .add("-c", "experimental_use_rmcp_client=true");
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            argv.add("-c", "model_reasoning_effort=\"" + reasoningEffort + "\"");
        }
        argv.add("exec");
        String resume = resumeSessionId();
        boolean firstTurn = resume == null || resume.isBlank();
        if (firstTurn) {
            // `codex exec [flags] <prompt>` — the sandbox, cwd, and model are
            // set once here and recorded on the session.
            argv.add("--json")
                    // Worktrees are detached checkouts; without this Codex
                    // refuses to run outside a "normal" git repo root.
                    .add("--skip-git-repo-check")
                    .add("--sandbox", SANDBOX_MODE)
                    .add("-C", workingDir);
            String model = model();
            if (model != null && !model.isBlank()) {
                argv.add("-m", model);
            }
            // The prompt is the trailing positional arg. Fold the role-skill
            // + workspace-memory context in front of it, since Codex has no
            // system-prompt flag.
            argv.add(composeFirstPrompt(userInput));
        }
        else {
            // `codex exec resume --json --skip-git-repo-check <SESSION_ID>
            // [PROMPT]` — resume continues the recorded session, which already
            // carries the sandbox / cwd / model / context, so passing those
            // flags again is rejected ("unexpected argument '--sandbox'").
            argv.add("resume")
                    .add("--json")
                    .add("--skip-git-repo-check")
                    .add(resume)
                    .add(userInput);
        }

        ProcessBuilder pb = new ProcessBuilder(argv.build());
        pb.directory(Path.of(workingDir).toFile());
        pb.redirectErrorStream(false);
        return pb;
    }

    /** Our per-agent MCP endpoint — matches McpController's stage-scoped
     *  route and the local sidecar port. The agent key scopes role /
     *  capability resolution to this agent's own running turn (the trunk
     *  uses the reserved "trunk" key). The Claude agent reaches the same
     *  server via --mcp-config; Codex reaches it via the -c override above. */
    private String mcpServerUrl()
    {
        return "http://127.0.0.1:53123/api/threads/" + threadId
                + "/agents/" + mcpAgentKey() + "/mcp";
    }

    /** Codex takes its prompt as an argv arg, so there's nothing to feed
     *  on stdin — close it immediately. (Codex still prints a "Reading
     *  additional input from stdin..." preamble; an empty close lets it
     *  proceed with just the argv prompt.) */
    @Override
    protected void deliverPrompt(Process process, String userInput)
            throws IOException
    {
        process.getOutputStream().close();
    }

    /** Prepend the role-skill body and workspace memory to the first
     *  turn's prompt, so a Codex session opens with the same project
     *  brain the Claude agent injects via {@code --append-system-prompt}.
     *  Both blocks are optional; when neither is present the user's prompt
     *  is returned unchanged. */
    private String composeFirstPrompt(String userInput)
    {
        StringBuilder preamble = new StringBuilder();
        if (roleSkillText != null && !roleSkillText.isBlank()) {
            preamble.append(roleSkillText.strip()).append("\n\n");
        }
        String workspaceMemory = workspaceMemoryProvider.get();
        if (workspaceMemory != null && !workspaceMemory.isBlank()) {
            preamble.append("# Workspace memory\n\n")
                    .append(workspaceMemory.strip())
                    .append("\n\n");
        }
        if (preamble.length() == 0) {
            return userInput;
        }
        return preamble.append("---\n\n").append(userInput).toString();
    }
}
