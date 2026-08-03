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
package com.bytequay.app.developmentflow;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionDispatcher;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.developmentflow.stage.PlanMcpService;
import com.bytequay.app.developmentflow.stage.V2PlanControlService;
import com.bytequay.app.developmentflow.task.creation.V2TaskCreationService;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.mcp.McpService;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.request.async.DeferredResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end spine of the agent development flow against the real Spring
 * context, the real SQLite schema, the real {@link ExecutionDispatcher}
 * and a real Git repository on disk. Only the provider wire is scripted:
 * {@link AgentTurnProviderSession} is replaced by an agent that drives the
 * same owner MCP tools a live CLI agent would — including
 * {@code record_development_result}, which is what makes the Turn's result
 * real rather than a string this test hands to itself.
 */
@SpringBootTest
class TestAgentDevelopmentE2E
{
    private static final String REPO = "acme/widget";
    private static final Duration PUMP_BUDGET = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    private Path tmp;

    @Autowired
    private WorkspaceService workspaces;
    @Autowired
    private ThreadService threadService;
    @Autowired
    private WatchedRepoStore watchedRepos;
    @Autowired
    private V2TaskCreationService taskCreation;
    @Autowired
    private ExecutionDispatcher dispatcher;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlanMcpService planMcp;
    @Autowired
    private McpService mcp;
    @Autowired
    private AgentTurnOperationHandler.Store turns;
    @Autowired
    private V2PlanControlService planControl;

    @MockitoBean
    private PullRequestRepository pullRequests;
    @MockitoBean
    private AgentTurnProviderSession provider;

    /** Every distinct ticket state seen while pumping. A stage that never
     *  arrives says nothing on its own; the ticket that failed on the way
     *  there says everything, and by then it has been overwritten. */
    private final Set<String> observed = new LinkedHashSet<>();
    private ScriptedAgent agent;
    private Path repositoryRoot;

    @BeforeEach
    void setUp()
            throws Exception
    {
        repositoryRoot = gitRepository();
        agent = new ScriptedAgent(planMcp, mcp, turns, jdbc, JSON);
        when(provider.open(any(), any()))
                .thenAnswer(call -> agent.open(call.getArgument(0), call.getArgument(1)));
    }

    @Test
    void aCreatedTaskPlansImplementsAndCommitsOnItsOwnBranch()
            throws Exception
    {
        Task task = createTask();

        // Provision the worktree, then draft and self-review the plan through
        // the real owner MCP tools.
        pumpUntil(() -> awaitingApproval(task.id()));
        assertThat(awaitingApproval(task.id()))
                .withFailMessage("the plan never reached approval; %s", diagnostics())
                .isTrue();

        // Approval is the user's, never the agent's — the flow parks here
        // until a human acts, so the test acts as that human.
        planControl.approve(openStageId(task.id(), "PLAN"));

        pumpUntil(() -> committed(task));
        assertThat(committed(task))
                .withFailMessage("the marker file was never committed; %s", diagnostics())
                .isTrue();

        assertThat(agent.profiles())
                .containsExactly(
                        AgentTurnProviderSession.ToolProfile.TASK_BRAIN_READ_ONLY,
                        AgentTurnProviderSession.ToolProfile.TASK_BRAIN_READ_ONLY,
                        AgentTurnProviderSession.ToolProfile.STAGE_DEVELOPMENT);

        // The Development Turn reported through record_development_result over
        // real MCP, and delivery read that row rather than its final message.
        // Without this the test passes on a git commit alone and says nothing
        // about the result contract — which is how it kept passing while the
        // contract it claimed to cover was replaced underneath it.
        assertThat(jdbc.queryForObject("""
                SELECT implemented_intent FROM stage_turn_development_submission
                """, String.class))
                .isEqualTo("Added the marker file");

        // The commit lands mid-Turn, so keep pumping until delivery has
        // consumed the submission — that is the half this change moved.
        pumpUntil(() -> reportedIntent() != null);
        assertThat(reportedIntent()).isEqualTo("Added the marker file");
    }

    /** The intent delivery persisted, once it has consumed the submission. */
    private String reportedIntent()
    {
        return jdbc.query(
                "SELECT implemented_intent FROM dev_report WHERE workflow_version = 'V2'",
                (rs, row) -> rs.getString(1))
                .stream().findFirst().orElse(null);
    }

    /** True once MARKER.md is committed on the Task's own branch. */
    private boolean committed(Task task)
            throws IOException, InterruptedException
    {
        Path worktree = Path.of(reloadedWorktreePath(task.id()));
        if (!Files.isDirectory(worktree)) {
            return false;
        }
        return gitOutput(worktree, "log", "--oneline", "--", "MARKER.md")
                .contains("Add the marker file");
    }

    private String openStageId(String taskId, String kind)
    {
        return jdbc.queryForObject("""
                SELECT id FROM stage
                WHERE task_id = ? AND kind = ? AND completed_at_ms IS NULL
                """, String.class, taskId, kind);
    }

    private String reloadedWorktreePath(String taskId)
    {
        return jdbc.queryForObject(
                "SELECT worktree_path FROM task_code_identity WHERE task_id = ?",
                String.class,
                taskId);
    }

    private String diagnostics()
    {
        return "observed: " + observed + "; tickets: " + jdbc.queryForList("""
                SELECT operation_kind, status, attempt, last_error
                FROM dispatch_ticket
                """)
                + "; task turns: " + jdbc.queryForList(
                        "SELECT purpose, status FROM task_turn")
                + "; stage turns: " + jdbc.queryForList(
                        "SELECT purpose, status FROM stage_turn");
    }

    private boolean awaitingApproval(String taskId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM stage
                WHERE task_id = ? AND kind = 'PLAN'
                  AND checkpoint = 'AWAITING_APPROVAL'
                """, Integer.class, taskId);
        return count != null && count > 0;
    }

    private Task createTask()
    {
        // A workspace only accepts a repository whose local clone is verified.
        watchedRepos.add("acme", "widget");
        watchedRepos.setLocalClonePath("acme", "widget", repositoryRoot.toString());
        String workspaceId = workspaces.create(new WorkspaceService.NewWorkspaceRequest(
                "E2E development", false, "", List.of(REPO))).id();
        // The trunk must be created through the real path: only that one
        // promotes the new thread to V2 inside its creation transaction.
        Thread trunk = threadService.create(new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT,
                "claude-code",
                "claude-sonnet-4.6",
                "E2E development trunk",
                repositoryRoot.toString(),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                ThreadFlow.BUILD,
                workspaceId,
                null));

        return taskCreation.create(trunk, new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT,
                "claude-code",
                "claude-sonnet-4.6",
                "Add a marker file",
                repositoryRoot.toString(),
                null,
                "Add a marker file to the repository root",
                List.of(),
                null,
                null,
                null,
                ThreadFlow.BUILD,
                workspaceId,
                null));
    }

    /**
     * Runs dispatcher maintenance until {@code done} or the deadline. The
     * dispatcher self-schedules every five seconds; driving it directly keeps
     * a multi-stage flow inside a test-sized budget.
     */
    private void pumpUntil(Progress done)
            throws Exception
    {
        Instant deadline = Instant.now().plus(PUMP_BUDGET);
        while (Instant.now().isBefore(deadline)) {
            dispatcher.runMaintenance();
            observed.addAll(jdbc.queryForList("""
                    SELECT operation_kind, status, last_error FROM dispatch_ticket
                    """).stream().map(Object::toString).toList());
            if (done.reached()) {
                return;
            }
            java.lang.Thread.sleep(200);
        }
        dispatcher.runMaintenance();
    }

    private interface Progress
    {
        boolean reached()
                throws Exception;
    }

    /**
     * A bare origin under {@code <tmp>/acme/widget.git} plus a working
     * clone. The bare path's last two segments are what GitRunner parses
     * back into the {@code acme/widget} slug provisioning expects, so the
     * whole flow resolves a real remote without touching the network.
     */
    private Path gitRepository()
            throws IOException, InterruptedException
    {
        Path origin = tmp.resolve("acme").resolve("widget.git");
        Files.createDirectories(origin.getParent());
        git(tmp, "init", "--quiet", "--bare", origin.toString());

        Path root = tmp.resolve("checkout");
        git(tmp, "init", "--quiet", "--initial-branch=main", root.toString());
        git(root, "config", "user.email", "e2e@bytequay.test");
        git(root, "config", "user.name", "ByteQuay E2E");
        Files.writeString(root.resolve("README.md"), "e2e fixture\n");
        git(root, "add", "README.md");
        git(root, "commit", "--quiet", "-m", "Seed the fixture repository");
        git(root, "remote", "add", "origin", origin.toString());
        git(root, "push", "--quiet", "origin", "main");
        git(root, "remote", "set-head", "origin", "main");
        return root;
    }

    private static void git(Path workingDirectory, String... args)
            throws IOException, InterruptedException
    {
        gitOutput(workingDirectory, args);
    }

    private static String gitOutput(Path workingDirectory, String... args)
            throws IOException, InterruptedException
    {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }

    /**
     * Stands in for a live agent. Plan turns drive the real owner MCP
     * tools; development turns write and commit in the worktree, then
     * return the strict JSON the stage runtime decodes.
     */
    private static final class ScriptedAgent
            implements AgentTurnProviderSession
    {
        private static final String RECORD_PLAN = "record_plan";
        private static final String RECORD_REVIEW = "record_plan_self_review";
        private static final String RECORD_RESULT = "record_development_result";
        private static final String MARKER = "MARKER.md";
        private static final String PLAN_CONTENT =
                "Add MARKER.md at the repository root with a single line of text.";

        private final PlanMcpService planMcp;
        private final McpService mcp;
        private final AgentTurnOperationHandler.Store turns;
        private final JdbcTemplate jdbc;
        private final ObjectMapper json;
        private final List<ToolProfile> profiles = new ArrayList<>();
        private int requestIds;

        private ScriptedAgent(
                PlanMcpService planMcp,
                McpService mcp,
                AgentTurnOperationHandler.Store turns,
                JdbcTemplate jdbc,
                ObjectMapper json)
        {
            this.planMcp = planMcp;
            this.mcp = mcp;
            this.turns = turns;
            this.jdbc = jdbc;
            this.json = json;
        }

        List<ToolProfile> profiles()
        {
            return List.copyOf(profiles);
        }

        @Override
        public Session open(Request request, Observer observer)
        {
            return new Session()
            {
                @Override
                public Result startAndAwait(WriterFence writerFence)
                        throws Exception
                {
                    profiles.add(request.toolEndpoint().profile());
                    observer.providerSession(request.provider(), "scripted-session");
                    return switch (request.toolEndpoint().profile()) {
                        case TASK_BRAIN_READ_ONLY -> planTurn(request);
                        case STAGE_DEVELOPMENT -> developmentTurn(request);
                        default -> throw new IllegalStateException(
                                "unscripted tool profile: "
                                        + request.toolEndpoint().profile());
                    };
                }

                @Override
                public void cancel() {}

                @Override
                public void close() {}
            };
        }

        /**
         * Asks the owner endpoint which plan tool this turn exposes and
         * calls it, the same way a live agent discovers its own toolset.
         */
        private Result planTurn(Request request)
        {
            OwnerToolEndpoint endpoint = request.toolEndpoint();
            call(endpoint, "initialize", json.createObjectNode());
            JsonNode tools = call(endpoint, "tools/list", json.createObjectNode())
                    .path("result").path("tools");
            String taskId = jdbc.queryForObject(
                    "SELECT task_id FROM task_turn WHERE id = ?",
                    String.class,
                    endpoint.ownerId());
            for (JsonNode tool : tools) {
                String name = tool.path("name").asText();
                if (RECORD_PLAN.equals(name)) {
                    call(endpoint, "tools/call", toolCall(name, arguments -> {
                        arguments.put("task_id", taskId);
                        arguments.put("goal", PLAN_CONTENT);
                        arguments.put("understanding",
                                "The repository root has no marker file.");
                        arguments.put("intent", "Add the file and commit it.");
                        ObjectNode step = arguments.putArray("steps").addObject();
                        step.put("action", "Add " + MARKER + " at the repository root");
                        step.putArray("files").add(MARKER);
                        arguments.put("validation",
                                "no build tooling in the fixture repository");
                    }));
                }
                else if (RECORD_REVIEW.equals(name)) {
                    call(endpoint, "tools/call", toolCall(name, arguments -> {
                        arguments.put("task_id", taskId);
                        arguments.put("verdict", "APPROVED");
                        arguments.putArray("concerns");
                        arguments.putArray("follow_ups");
                        arguments.putArray("stewardship");
                    }));
                }
            }
            return succeeded("plan turn complete");
        }

        /** Writes and commits one file, then reports it in strict JSON. */
        private Result developmentTurn(Request request)
                throws IOException, InterruptedException
        {
            Path worktree = request.workingDirectory();
            Files.writeString(worktree.resolve(MARKER), "written by the scripted agent\n");
            git(worktree, "add", MARKER);
            git(worktree, "commit", "--quiet", "-m", "Add the marker file");

            OwnerToolEndpoint endpoint = request.toolEndpoint();
            call(endpoint, "initialize", json.createObjectNode());
            call(endpoint, "tools/call", toolCall(RECORD_RESULT, arguments -> {
                arguments.put("implemented_intent", "Added the marker file");
                arguments.put("commit_summary", "Add the marker file");
                arguments.put("file_summary", MARKER);
                arguments.put(
                        "validation_summary",
                        "no build tooling in the fixture repository");
                arguments.put("known_risks", "none");
                arguments.put("unresolved_concerns", "none");
                arguments.put("context_refs", MARKER);
                arguments.put("pr_description", "## Summary" + System.lineSeparator()
                        + "Adds the marker file.");
            }));
            // Prose on purpose: the result is the tool call, and this proves
            // the final message is no longer parsed.
            return succeeded("Added the marker file and recorded the result.");
        }

        private JsonNode call(OwnerToolEndpoint endpoint, String method, JsonNode params)
        {
            ObjectNode request = json.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", ++requestIds);
            request.put("method", method);
            request.set("params", params);
            JsonNode response = endpoint.ownerKind()
                    == DispatchTicket.OwnerKind.STAGE_TURN
                    ? awaitDeferred(mcp.handle(
                            turns.authorizeMcp(
                                            DispatchTicket.OwnerKind.STAGE_TURN,
                                            endpoint.ownerId(),
                                            endpoint.operationId(), Instant.now())
                                    .orElseThrow(() -> new IllegalStateException(
                                            "StageTurn MCP endpoint is not active"))
                                    .trunkId(),
                            AgentTurnOperationHandler.mcpAgentKey(
                                    DispatchTicket.OwnerKind.STAGE_TURN,
                                    endpoint.ownerId(), endpoint.operationId()),
                            request))
                    : planMcp.handle(
                            endpoint.ownerId(), endpoint.operationId(), request);
            if (response != null && response.has("error")) {
                throw new IllegalStateException(
                        method + " failed: " + response.path("error"));
            }
            return response == null ? json.createObjectNode() : response;
        }

        private JsonNode awaitDeferred(DeferredResult<JsonNode> deferred)
        {
            long deadline = System.currentTimeMillis() + 10_000L;
            while (!deferred.hasResult() && System.currentTimeMillis() < deadline) {
                try {
                    java.lang.Thread.sleep(10);
                }
                catch (InterruptedException interrupted) {
                    java.lang.Thread.currentThread().interrupt();
                    throw new IllegalStateException("owner MCP call interrupted", interrupted);
                }
            }
            if (!deferred.hasResult()) {
                throw new IllegalStateException("owner MCP call did not answer");
            }
            return (JsonNode) deferred.getResult();
        }

        private JsonNode toolCall(String name, Consumer<ObjectNode> arguments)
        {
            ObjectNode params = json.createObjectNode();
            params.put("name", name);
            arguments.accept(params.putObject("arguments"));
            return params;
        }

        private static Result succeeded(String finalText)
        {
            return new Result(
                    Completion.SUCCEEDED, "scripted-session", finalText,
                    1, 1, 0, null, null);
        }
    }
}
