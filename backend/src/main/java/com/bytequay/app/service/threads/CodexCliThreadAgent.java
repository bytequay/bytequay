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
            String roleSkillText)
    {
        this(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, roleSkillText, DEFAULT_BINARY, (String) null);
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
                workspaceMemoryProvider, roleSkillText, DEFAULT_BINARY, trunkCwd);
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
            String binary)
    {
        this(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, roleSkillText, binary, (String) null);
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
            String trunkCwd)
    {
        super(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                binary, trunkCwd);
        this.workspaceMemoryProvider = requireNonNull(workspaceMemoryProvider, "workspaceMemoryProvider is null");
        this.roleSkillText = roleSkillText;
    }

    @Override
    protected ProcessBuilder buildCommand(String userInput)
    {
        ImmutableList.Builder<String> argv = ImmutableList.<String>builder()
                .add(binary)
                .add("exec");
        String resume = resumeSessionId();
        boolean firstTurn = resume == null || resume.isBlank();
        if (!firstTurn) {
            // `codex exec resume <id> [flags] <prompt>` continues the
            // recorded session so the model keeps its prior context.
            argv.add("resume", resume);
        }
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
        // The prompt is the trailing positional arg. On the first turn we
        // fold the role-skill + workspace-memory context in front of it,
        // since Codex has no system-prompt flag; resumed turns already
        // carry that context in the recorded session.
        argv.add(firstTurn ? composeFirstPrompt(userInput) : userInput);

        ProcessBuilder pb = new ProcessBuilder(argv.build());
        pb.directory(Path.of(workingDir).toFile());
        pb.redirectErrorStream(false);
        return pb;
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
