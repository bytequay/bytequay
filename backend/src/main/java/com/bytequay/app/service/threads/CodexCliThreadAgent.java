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
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.agents.AgentContextCompiler;
import com.bytequay.app.service.workspaces.WorkspaceDocumentLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Wraps the {@code codex} CLI as a {@link Agent}.
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
    static final String DEFAULT_BINARY = "codex";

    /** Task agents write only inside their worktree; trunk agents stay
     *  read-only and hand implementation to a Task. */
    private static final String TASK_SANDBOX_MODE = "workspace-write";
    private static final String TRUNK_SANDBOX_MODE = "read-only";

    private final Supplier<String> workspaceMemoryProvider;
    private final String roleSkillText;
    /** Optional Codex reasoning-effort override for planning-heavy sessions. */
    private volatile String reasoningEffort;

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
    public void updateWorkModel(WorkModel workModel)
    {
        if (workModel != null && workModel.kind() == WorkModelKind.CLI) {
            updateModel(workModel.model());
            reasoningEffort = workModel.reasoningEffort();
        }
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
                .add("-c", "experimental_use_rmcp_client=true")
                // Do not ingest AGENTS.md into ByteQuay-managed sessions.
                // The role/workspace/skill contract below is authoritative.
                .add("-c", "project_doc_max_bytes=0");
        if (isReadOnlySession()) {
            // Repeat the policy as a config override so resumed sessions made
            // before this guard was added are tightened too; exec resume does
            // not accept the --sandbox flag itself.
            argv.add("-c", "sandbox_mode=\"read-only\"");
        }
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            argv.add("-c", "model_reasoning_effort=\"" + reasoningEffort + "\"");
        }
        argv.add("exec")
                // Personal config can add MCP servers and behaviour outside
                // the role contract. Auth remains available to Codex.
                .add("--ignore-user-config");
        String resume = resumeSessionId();
        boolean firstTurn = resume == null || resume.isBlank();
        if (firstTurn) {
            // `codex exec [flags] <prompt>` — the sandbox, cwd, and model are
            // set once here and recorded on the session.
            argv.add("--json")
                    // Worktrees are detached checkouts; without this Codex
                    // refuses to run outside a "normal" git repo root.
                    .add("--skip-git-repo-check")
                    .add("--sandbox", isReadOnlySession() ? TRUNK_SANDBOX_MODE : TASK_SANDBOX_MODE)
                    .add("-C", workingDir);
            String model = model();
            if (model != null && !model.isBlank()) {
                argv.add("-m", model);
            }
            // The prompt is the trailing positional arg. Fold hidden managed
            // skills, role-skill, and workspace memory in front of it, since
            // Codex has no system-prompt flag.
            argv.add(composePrompt(userInput));
        }
        else {
            // `codex exec resume --json --skip-git-repo-check <SESSION_ID>
            // [PROMPT]` — resume continues the recorded session, which already
            // carries the sandbox / cwd / model / context, so passing those
            // flags again is rejected ("unexpected argument '--sandbox'").
            argv.add("resume")
                    .add("--json")
                    .add("--skip-git-repo-check");
            String model = model();
            if (model != null && !model.isBlank()) {
                argv.add("-m", model);
            }
            argv.add(resume)
                    .add(composePrompt(userInput));
        }

        ProcessBuilder pb = new ProcessBuilder(argv.build());
        pb.directory(Path.of(workingDir).toFile());
        pb.redirectErrorStream(false);
        return pb;
    }

    @Override
    protected boolean shouldAutomaticallyRecover(String errorDetail)
    {
        if (errorDetail != null && errorDetail.contains("no rollout found for thread id")) {
            // The stored id may belong to a previously-selected CLI agent or
            // to a locally-pruned Codex rollout. Resume failed before the turn
            // began, so retrying once with a fresh Codex session is safe.
            clearResumeSessionId();
            return true;
        }
        return false;
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

    /** Compile the same ByteQuay-owned context used by Claude and the API lane. */
    private String composePrompt(String userInput)
    {
        String compiled = AgentContextCompiler.compilePrompt(
                roleSkillText,
                WorkspaceDocumentLoader.load(workingDir),
                workspaceMemoryProvider.get(),
                activeManagedSkills()).systemPrompt();
        if (compiled.isBlank()) {
            return userInput;
        }
        return compiled + "\n\n---\n\n" + userInput;
    }
}
