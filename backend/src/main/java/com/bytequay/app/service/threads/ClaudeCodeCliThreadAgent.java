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
import com.bytequay.app.service.skills.SkillMaterializer;
import com.bytequay.app.service.workspaces.WorkspaceDocumentLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Wraps the {@code claude} CLI as a {@link ThreadAgent}.
 *
 * <p>Each {@link #send} spawns a fresh {@code claude -p
 * --output-format stream-json ... [--resume <session-id>]}, feeds the
 * user's prompt on stdin, and reads its {@code stream-json} stdout. All
 * the provider-agnostic lifecycle (state machine, event persistence,
 * metrics, permission gate, snapshotting) lives in
 * {@link AbstractCliThreadAgent}; this subclass supplies only the
 * Claude-specific bits: the argv (with the MCP permission-prompt tool,
 * appended ByteQuay-managed context and the Node heap cap) plus the
 * per-session MCP config it cleans up on stop.
 */
public class ClaudeCodeCliThreadAgent
        extends AbstractCliThreadAgent
{
    private static final String MISSING_PERMISSION_MCP_TOOL =
            "MCP tool mcp__bytequay__approval_prompt (passed via --permission-prompt-tool) "
                    + "not found. Available MCP tools: none";

    /** Inline settings replace Claude's MCP-incompatible safe mode while
     *  keeping provider-native auto-memory and commit attribution out of
     *  ByteQuay sessions. */
    private static final String ISOLATED_SETTINGS =
            "{\"autoMemoryEnabled\":false,\"attribution\":{\"commit\":\"\"}}";

    /** Built-in tools exposed to the read-only trunk. MCP tools such as
     *  create_task are configured separately and remain available. */
    private static final String TRUNK_BUILTIN_TOOLS = "Read,Glob,Grep,WebFetch,WebSearch";

    /** {@code claude}'s default binary name on PATH. Overridable in case
     *  the user installed it under a different name. */
    private static final String DEFAULT_BINARY = "claude";

    /** Resolves the thread's workspace memory_md at spawn time. Empty
     *  string when nothing's there yet. The CLI sees the result via
     *  --append-system-prompt on each session bootstrap. */
    private final Supplier<String> workspaceMemoryProvider;
    /** Role skill text resolved at session construction (null when no
     *  role block applies). Frozen once so the system prefix stays
     *  byte-stable across turns, which keeps the prompt cache warm. */
    private final String roleSkillText;
    /** Optional Claude Code effort level for planning-heavy sessions. */
    private volatile String reasoningEffort;
    /** Lazily-written MCP config file Claude reads via {@code
     *  --mcp-config}. Reused while the agent key stays unchanged. */
    private final AtomicReference<Path> mcpConfigPath = new AtomicReference<>();
    /** Agent key embedded in {@link #mcpConfigPath}. A task-brain session
     *  moves between the trunk key and stage-scoped review keys. */
    private final AtomicReference<String> mcpConfigAgentKey = new AtomicReference<>();

    public ClaudeCodeCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            StreamJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            @SuppressWarnings("unused") SkillMaterializer skillMaterializer,
            String roleSkillText,
            Task boundTask)
    {
        this(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, skillMaterializer, roleSkillText, DEFAULT_BINARY,
                (String) null, boundTask, (String) null, (String) null);
    }

    /**
     * Stage-scoped constructor carrying the resolved work-model cascade's
     * model id (stage → task → thread → workspace → global), so a stage or
     * task override reaches the {@code --model} spawn arg instead of only
     * the thread's frozen {@link Thread#model()}. Null/blank means no
     * override — falls back to {@code thread.model()} like the constructor
     * above.
     */
    public ClaudeCodeCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            StreamJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            SkillMaterializer skillMaterializer,
            String roleSkillText,
            Task boundTask,
            String modelOverride)
    {
        this(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, skillMaterializer, roleSkillText,
                boundTask, modelOverride, null);
    }

    /** Stage-scoped constructor with model and provider-native effort. */
    public ClaudeCodeCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            StreamJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            SkillMaterializer skillMaterializer,
            String roleSkillText,
            Task boundTask,
            String modelOverride,
            String reasoningEffort)
    {
        this(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, skillMaterializer, roleSkillText, DEFAULT_BINARY,
                (String) null, boundTask, modelOverride, reasoningEffort);
    }

    /**
     * Trunk-mode constructor: the agent runs without a focused Task, cwd
     * defaulting to {@code trunkCwd} (a watched-repo clone root), with
     * {@code thread.agentSessionId} as the {@code --resume} id.
     */
    public ClaudeCodeCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            StreamJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            SkillMaterializer skillMaterializer,
            String roleSkillText,
            String trunkCwd,
            @SuppressWarnings("unused") TrunkMode trunkMode)
    {
        this(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, skillMaterializer, roleSkillText, DEFAULT_BINARY,
                trunkCwd, (Task) null, (String) null, (String) null);
    }

    /** Trunk / brain constructor with an explicit provider-native effort. */
    public ClaudeCodeCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            StreamJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            SkillMaterializer skillMaterializer,
            String roleSkillText,
            String trunkCwd,
            @SuppressWarnings("unused") TrunkMode trunkMode,
            String reasoningEffort)
    {
        this(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, skillMaterializer, roleSkillText,
                trunkCwd, trunkMode, null, reasoningEffort);
    }

    /** Trunk / brain constructor with explicit model and effort overrides. */
    public ClaudeCodeCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            StreamJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            SkillMaterializer skillMaterializer,
            String roleSkillText,
            String trunkCwd,
            @SuppressWarnings("unused") TrunkMode trunkMode,
            String modelOverride,
            String reasoningEffort)
    {
        this(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, skillMaterializer, roleSkillText, DEFAULT_BINARY,
                trunkCwd, (Task) null, modelOverride, reasoningEffort);
    }

    /** Marker enum disambiguating the two-argument trailing-string
     *  constructor overloads. {@link #ENABLED} = trunk mode. */
    public enum TrunkMode { ENABLED }

    ClaudeCodeCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            StreamJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            SkillMaterializer skillMaterializer,
            String roleSkillText,
            String binary,
            Task boundTask)
    {
        this(thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                workspaceMemoryProvider, skillMaterializer, roleSkillText, binary,
                (String) null, boundTask, (String) null, (String) null);
    }

    private ClaudeCodeCliThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            StreamJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            @SuppressWarnings("unused") SkillMaterializer skillMaterializer,
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
                .add("-p")
                .add("--output-format", "stream-json")
                .add("--verbose")
                // ByteQuay owns instructions, skills, hooks and browser
                // integrations. Unlike --safe-mode, these isolation flags
                // leave the explicit ByteQuay MCP server available.
                .add("--setting-sources", "")
                .add("--disable-slash-commands")
                .add("--no-chrome")
                .add("--settings", ISOLATED_SETTINGS)
                // Surface the upstream Anthropic stream events (text
                // deltas, content_block_start/stop, message_delta) so the
                // parser can emit AssistantTextDelta events for the
                // in-flight assistant card. The fully assembled assistant
                // message still lands at message_stop and takes precedence
                // for persistence.
                .add("--include-partial-messages")
                .add("--mcp-config", ensureMcpConfig().toString())
                // Only the explicit ByteQuay MCP file above may contribute
                // external tools to this provider session.
                .add("--strict-mcp-config")
                .add("--permission-prompt-tool", "mcp__bytequay__approval_prompt");
        if (isReadOnlySession()) {
            // The role prompt says the trunk is read-only, but a prompt is not
            // a security boundary. Remove Bash/Edit/Write/Task from Claude's
            // built-in catalog while leaving ByteQuay's create_task MCP tool.
            argv.add("--tools", TRUNK_BUILTIN_TOOLS)
                    // Repository reads are already trusted through the CLI's
                    // working directory. Pasted images live outside it, so
                    // pre-approve only ByteQuay's managed attachment root.
                    // Claude's Read matcher also checks symlink targets.
                    .add("--allowed-tools", absoluteReadRule(ChatAttachmentStore.attachmentsRoot()));
        }
        // Resolved work-model cascade (or a CLI-reported model from a prior
        // turn) — mirrors CodexCliThreadAgent's -m flag. Sent on every turn,
        // same as --append-system-prompt below; --resume tolerates it.
        String modelId = model();
        if (modelId != null && !modelId.isBlank()) {
            argv.add("--model", modelId);
        }
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            argv.add("--effort", reasoningEffort);
        }
        String workspaceMemory = workspaceMemoryProvider.get();
        String compiled = AgentContextCompiler.compilePrompt(
                roleSkillText,
                WorkspaceDocumentLoader.load(workingDir),
                workspaceMemory,
                activeManagedSkills()).systemPrompt();
        if (!compiled.isBlank()) {
            argv.add("--append-system-prompt", compiled);
        }
        String resume = resumeSessionId();
        if (resume != null && !resume.isBlank()) {
            argv.add("--resume", resume);
        }
        ProcessBuilder pb = new ProcessBuilder(argv.build());
        pb.directory(Path.of(workingDir).toFile());
        pb.redirectErrorStream(false);
        // Cap the Node.js heap inside the Claude CLI subprocess so a
        // single thread can't blow through the app-wide budget on its
        // own. 512 MB is enough for the streaming JSON pipeline + tool-use
        // buffering we observe in practice; multiplied by the scheduler's
        // 4-way CLI concurrency lane this keeps the combined CLI heap
        // around ~2 GB even with the lane full. NODE_OPTIONS rides through
        // env so it applies whether claude was installed as a global npm
        // bin or via npx/yarn.
        pb.environment().merge(
                "NODE_OPTIONS",
                "--max-old-space-size=512",
                (existing, ours) -> existing.contains("--max-old-space-size") ? existing : existing + " " + ours);
        // An app launched from a shell can inherit either flag. Both disable
        // the explicit MCP bridge, so the child must use the argv isolation
        // policy above instead.
        pb.environment().remove("CLAUDE_CODE_SAFE_MODE");
        pb.environment().remove("CLAUDE_CODE_SIMPLE");
        return pb;
    }

    /** Claude permission patterns use {@code //path} for an absolute path;
     *  a single leading slash means project-relative. ByteQuay is macOS-only,
     *  so an absolute {@link Path} starts with the slash added below. */
    private static String absoluteReadRule(Path directory)
    {
        String absolute = directory.toAbsolutePath().normalize().toString();
        return "Read(/" + absolute + "/**)";
    }

    @Override
    protected void cleanupProviderResources()
    {
        cleanupMcpConfig();
    }

    @Override
    protected boolean shouldAutomaticallyRecover(String errorDetail)
    {
        // Retrying arbitrary coding-agent exits could repeat remote or file
        // side effects. This startup/configuration failure happens before the
        // permission-gated tool runs, so one fresh-MCP retry is safe.
        return errorDetail != null && errorDetail.contains(MISSING_PERMISSION_MCP_TOOL);
    }

    /** Lazily writes the per-thread MCP config to a temp file Claude
     *  reads. Rewrites when the temp file disappears or a task-brain turn
     *  moves to a different agent key. */
    private Path ensureMcpConfig()
    {
        String agentKey = mcpAgentKey();
        Path existing = mcpConfigPath.get();
        if (existing != null && Files.isRegularFile(existing)
                && agentKey.equals(mcpConfigAgentKey.get())) {
            return existing;
        }
        cleanupMcpConfig();
        try {
            Path tmp = Files.createTempFile("bytequay-mcp-" + threadId + "-", ".json");
            // Per-agent MCP URL: the agent key scopes role / capability
            // resolution to THIS agent's running turn, so concurrent Task
            // agents on one thread don't read each other's scope. The trunk
            // agent's key is the reserved "trunk" sentinel.
            String json = "{\"mcpServers\":{\"bytequay\":{"
                    + "\"type\":\"http\","
                    + "\"url\":\"http://127.0.0.1:53123/api/threads/" + threadId
                    + "/agents/" + agentKey + "/mcp\""
                    + "}}}";
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            tmp.toFile().deleteOnExit();
            mcpConfigAgentKey.set(agentKey);
            mcpConfigPath.set(tmp);
            return tmp;
        }
        catch (IOException e) {
            throw new IllegalStateException("Failed to write MCP config for thread " + threadId, e);
        }
    }

    private void cleanupMcpConfig()
    {
        mcpConfigAgentKey.set(null);
        Path p = mcpConfigPath.getAndSet(null);
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            }
            catch (IOException ignored) {
                // Best-effort — temp file gets cleaned on JVM exit anyway.
            }
        }
    }
}
