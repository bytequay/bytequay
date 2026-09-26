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
package com.bytequay.app.web.agent;

import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.CodeGraphService;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskBranches;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.UnsequencedAgentEvent;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.app.agent.DevelopmentGitInspector;
import com.bytequay.app.agent.DevelopmentPublicationFacts;
import com.bytequay.app.agent.InteractiveAgentTaskCreator;
import com.bytequay.app.agent.codegraph.FakeCodeGraphService;
import com.bytequay.app.agent.impl.DeterministicAgentDriver;
import com.bytequay.app.jira.JiraDtos;
import com.bytequay.app.jira.JiraIssue;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceCodeSyncResult;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.app.workspace.WorkspaceProvisioningService;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.google.common.base.Preconditions.checkState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.shutdown=immediate",
                "bytequay.agent.desktop-session-token=test-session",
                "bytequay.agent.runtime-root=${java.io.tmpdir}/bytequay-agent-http-${random.uuid}",
                "bytequay.workspace.root=${java.io.tmpdir}/bytequay-agent-workspace-${random.uuid}"
        })
class TestAgentHttpApi
{
    @TestConfiguration
    static class DeterministicLane
    {
        @Bean
        DeterministicAgentDriver deterministicAgentDriver()
        {
            return new DeterministicAgentDriver(AgentLaneId.CODEX_CLI);
        }

        @Bean
        DeterministicAgentDriver deterministicClaudeAgentDriver()
        {
            return new DeterministicAgentDriver(AgentLaneId.CLAUDE_CLI);
        }

        // The baseline must not depend on whether the vendored helper is installed on the machine running the gate.
        @Bean
        @Primary
        CodeGraphService absentCodeGraphService()
        {
            FakeCodeGraphService codeGraph = new FakeCodeGraphService();
            codeGraph.available = false;
            return codeGraph;
        }

        @Bean
        @Primary
        WorkspaceProvisioningService workspaceProvisioningService()
        {
            return mock(WorkspaceProvisioningService.class);
        }
    }

    private static final String SESSION_TOKEN = "test-session";
    private static final String REPOSITORY = "bytequay/agent-e2e";
    private static final String ORIGIN = "https://github.com/" + REPOSITORY + ".git";
    private static final AtomicLong NEXT_REPOSITORY_ID = new AtomicLong(10_000);
    private static final String BASELINE_FIXTURE = "/com/bytequay/app/web/agent/agent-http-baseline.json";
    private static final Set<String> TIMESTAMP_FIELDS = ImmutableSet.of(
            "createdAt",
            "updatedAt",
            "startedAt",
            "endedAt",
            "cancellationRequestedAt",
            "occurredAt",
            "terminalAt",
            "completedAt",
            "deleteAfter",
            "acquiredAt",
            "checkoutRemovedAt");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkspacePaths workspacePaths;

    @Autowired
    private WorkspaceStore workspaces;

    @Autowired
    private WorkspaceProvisioningService provisioning;

    @Autowired
    private ModelSettingsService models;

    @Autowired
    private AgentTaskService tasks;

    @Autowired
    private AgentTurnService turns;

    @Autowired
    private AgentEventService events;

    @Autowired
    private AgentApprovalService approvals;

    @Autowired
    private AgentResourceService resources;

    @Autowired
    private AgentCompletionService completions;

    @Autowired
    private InteractiveAgentTaskCreator taskCreator;

    @Autowired
    @Qualifier("deterministicAgentDriver")
    private DeterministicAgentDriver driver;

    @Autowired
    @Qualifier("deterministicClaudeAgentDriver")
    private DeterministicAgentDriver claudeDriver;

    @Autowired
    private PullRequestStore pullRequests;

    @Autowired
    private RepositoryIssueStore issueRows;

    @MockitoBean
    private GitHubRepoClient issueClient;

    @MockitoBean
    private JiraService jira;

    @MockitoBean
    private GitHubPullRequestReadClient gitHub;

    @MockitoBean
    private CredentialService credentials;

    @MockitoSpyBean
    private DevelopmentGitInspector developmentGit;

    private String workspaceId;
    private Path repository;

    @BeforeEach
    void setUp()
            throws IOException, InterruptedException
    {
        // The documentation dependency must not make this client retry a rejected HTTP action.
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        driver.reset();
        claudeDriver.reset();
        reset(provisioning);
        when(credentials.resolveGitHubCredential()).thenReturn(
                new GitHubCredential("pat", new UserProfile("chenjian2664", "Chen", "", "", 0, 0, 0, "", "", "", "")));
        doReturn(new DevelopmentPublicationFacts(Optional.empty(), ImmutableList.of())).when(developmentGit).inspectPublication(any(), any());
        models.refreshCatalogs();
        workspaceId = "agent-http-" + UUID.randomUUID();
        long repositoryId = NEXT_REPOSITORY_ID.incrementAndGet();
        repository = workspacePaths.repository(workspaceId);
        Files.createDirectories(repository);
        Files.writeString(
                workspacePaths.workspace(workspaceId).resolve(".bytequay-workspace"),
                workspaceId + "\n" + repositoryId + "\n");
        run("git", "init", "-b", "main", repository.toString());
        run("git", "-C", repository.toString(), "config", "user.name", "ByteQuay Test");
        run("git", "-C", repository.toString(), "config", "user.email", "test@bytequay.local");
        Files.writeString(repository.resolve("README.md"), "agent e2e\n");
        run("git", "-C", repository.toString(), "add", "README.md");
        run("git", "-C", repository.toString(), "commit", "-m", "Initial commit");
        run("git", "-C", repository.toString(), "remote", "add", "origin", ORIGIN);
        run("git", "-C", repository.toString(), "update-ref", "refs/remotes/origin/main", "HEAD");

        Instant now = Instant.now();
        RepoRef selected = RepoRef.of("bytequay", "agent-e2e");
        workspaces.createOrGet(new Workspace(
                workspaceId,
                "Agent HTTP test",
                repositoryId,
                selected,
                Optional.empty(),
                Optional.empty(),
                repositoryId,
                selected,
                Workspace.Topology.DIRECT,
                "main",
                ORIGIN,
                Optional.empty(),
                Optional.empty(),
                repository.toString(),
                Optional.of(now),
                Optional.of(now),
                0,
                now,
                now));
        when(provisioning.synchronizeCode(workspaceId)).thenReturn(new WorkspaceCodeSyncResult(workspaceId, true, Optional.of(now)));
    }

    @Test
    void protectsAgentEndpointsAndReportsProviderStatus()
            throws Exception
    {
        assertThat(rest.getForEntity(url("/agent/providers/status"), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders originHeaders = headers();
        originHeaders.setOrigin("https://example.com");
        ResponseEntity<JsonNode> originResponse = rest.exchange(
                url("/agent/providers/status"),
                HttpMethod.GET,
                new HttpEntity<>(originHeaders),
                JsonNode.class);
        assertThat(originResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> status = get("/agent/providers/status");
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody()).isNotNull();
        assertThat(status.getBody().get(0).get("laneId").asText()).isEqualTo("codex");
        assertThat(status.getBody().get(0).get("readiness").asText()).isEqualTo("READY");
        assertThat(status.getBody().get(0).has("health")).isFalse();
    }

    @Test
    void jiraLaunchUsesTheCanonicalTurnAndRetainsSourceOnRetry()
            throws Exception
    {
        Instant updated = Instant.parse("2026-09-16T00:00:00Z");
        String issueId = "jira-" + UUID.randomUUID();
        JiraIssue issue = new JiraIssue("connection", "cloud", issueId, "APP-7", "https://company.atlassian.net/browse/APP-7",
                "Fix selection", "Jira body kept in local metadata", "Story", new JiraDtos.Status("3", "In progress", "indeterminate"), Optional.empty(),
                ImmutableList.of(), Optional.empty(), Optional.empty(), false, false, updated, Optional.empty(), Optional.empty(), ImmutableList.of(), false, Optional.empty());
        when(jira.issue("connection", "cloud", issueId)).thenReturn(issue);
        when(jira.refreshIssue("connection", "cloud", issueId)).thenReturn(issue);
        String instruction = "Implement Jira selection";
        Map<String, Object> request = ImmutableMap.<String, Object>builder().putAll(createTaskBody("codex", "test-model", instruction))
                .put("jiraSource", ImmutableMap.<String, Object>builder().put("connectionId", "connection").put("cloudId", "cloud").put("issueId", issueId)
                        .put("key", issue.key()).put("title", issue.title()).put("body", issue.body()).put("updatedAt", updated.toString()).buildOrThrow()).buildOrThrow();
        ResponseEntity<JsonNode> response = post("/agent/tasks", request, null);
        assertThat(response.getStatusCode()).as("create response: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = response.getBody();
        String taskId = created.required("id").asText();
        assertThat(created.at("/jiraSource/instructions").asText()).isEqualTo(instruction);
        assertThat(created.at("/jiraSource/issueId").asText()).isEqualTo(issueId);
        assertThat(created.at("/jiraSource/body").asText()).isEqualTo(issue.body());
        assertThat(created.path("issueSource").isNull()).isTrue();
        JsonNode firstMessage = StreamSupport.stream(get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody().spliterator(), false)
                .filter(event -> event.path("type").asText().equals("user_message")).findFirst().orElseThrow();
        assertThat(firstMessage.at("/payload/text").asText()).isEqualTo(instruction);
        assertThat(created.at("/jiraSource/initialTurnId").asText()).isEqualTo(firstMessage.path("turnId").asText());
        when(jira.issue("connection", "cloud", issueId)).thenThrow(new AssertionError("retry must use the durable source"));
        assertThat(post("/agent/tasks", request, null).getBody().path("id").asText()).isEqualTo(taskId);
        assertThat(get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId).getBody().path("jiraSource")).isEqualTo(created.path("jiraSource"));
        verify(jira).refreshIssue("connection", "cloud", issueId);
    }

    @Test
    void issueLaunchKeepsUserInstructionVisibleAndProjectsStableSourceTurn()
            throws Exception
    {
        Workspace workspace = workspaces.findById(workspaceId).orElseThrow();
        Instant updated = Instant.parse("2026-09-16T00:00:00Z");
        long issueId = workspace.monitoredRepositoryId() * 1000 + 7;
        GitHubIssueSource source = new GitHubIssueSource("github.com", issueId, workspace.monitoredRepository().fullName(), 7,
                "https://github.com/" + workspace.monitoredRepository().fullName() + "/issues/7", "Fix issue selection", "Long source description kept in local metadata", updated);
        issueRows.saveSyncPage(workspace.monitoredRepositoryId(), ImmutableList.of(new RepositoryIssue(issueId, 7, source.title(), source.body(),
                "alice", "", source.url(), ImmutableList.of(), ImmutableList.of(), 0, updated, updated)), "issue-test");
        when(credentials.resolveGitHubToken()).thenReturn("pat");
        when(issueClient.fetchOpenIssueSource("pat", workspace.monitoredRepository(), 7)).thenReturn(source);
        String instruction = "Implement issue selection";
        Map<String, Object> request = ImmutableMap.<String, Object>builder().putAll(createTaskBody("codex", "test-model", instruction))
                .put("issueSource", ImmutableMap.of("issueId", issueId, "number", 7, "updatedAt", updated.toString())).buildOrThrow();
        ResponseEntity<JsonNode> response = post("/agent/tasks", request, null);
        assertThat(response.getStatusCode()).as("create response: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = response.getBody();
        String taskId = created.required("id").asText();
        assertThat(created.path("title").asText()).isEqualTo("Name first task");
        assertThat(created.path("titleSource").asText()).isEqualTo("AGENT");
        assertThat(created.at("/issueSource/instructions").asText()).isEqualTo(instruction);
        assertThat(created.at("/issueSource/body").asText()).isEqualTo(source.body());
        JsonNode firstMessage = StreamSupport.stream(get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody().spliterator(), false)
                .filter(event -> event.path("type").asText().equals("user_message")).findFirst().orElseThrow();
        assertThat(firstMessage.at("/payload/text").asText()).isEqualTo(instruction);
        assertThat(created.at("/issueSource/initialTurnId").asText()).isEqualTo(firstMessage.path("turnId").asText());
        issueRows.delete(workspace.monitoredRepositoryId(), 7);
        JsonNode retained = get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId).getBody();
        assertThat(retained.path("issueSource")).isEqualTo(created.path("issueSource"));
        assertThat(post("/agent/tasks", request, null).getBody().path("id").asText()).isEqualTo(taskId);
        verify(issueClient).fetchOpenIssueSource("pat", workspace.monitoredRepository(), 7);
    }

    @Test
    void startupPhaseIsEmptyOutsideAnInFlightCreation()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();

        assertThat(get("/agent/tasks/" + taskId + "/startup").getBody().required("phase").isNull()).isTrue();
        assertThat(get("/agent/tasks/" + UUID.randomUUID() + "/startup").getBody().required("phase").isNull()).isTrue();
    }

    @Test
    void listsThePullRequestsOnTheTasksBranch()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        AgentTask task = tasks.get(TaskId.valueOf(taskId));
        String branch = task.taskWorktree().orElseThrow().generatedBranch();
        String path = "/agent/tasks/" + taskId + "/pull-requests?workspaceId=";
        assertThat(get(path + workspaceId).getBody()).isEmpty();

        pullRequests.saveRepositorySyncPage(
                REPOSITORY,
                ImmutableList.of(fetchedPullRequest(482, branch), fetchedPullRequest(483, "feature")),
                "seed");

        JsonNode listed = get(path + workspaceId).getBody();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("number").asInt()).isEqualTo(482);
        assertThat(listed.get(0).get("headRef").asText()).isEqualTo(branch);
        // Another workspace cannot see this task, let alone its pull requests.
        assertThat(get(path + "another-workspace").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listsPullRequestsAfterTheAgentRenamesItsBranch()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        TaskWorktree worktree = tasks.get(TaskId.valueOf(taskId)).taskWorktree().orElseThrow();
        String branch = "published-" + taskId;
        run("git", "-C", worktree.canonicalWorktree().toString(), "branch", "-m", branch);
        pullRequests.saveRepositorySyncPage(
                REPOSITORY,
                ImmutableList.of(fetchedPullRequest(484, worktree.generatedBranch()), fetchedPullRequest(485, branch), fetchedPullRequest(486, "unrelated"),
                        // Another author's fork on the same renamed branch name is not the task's pull request.
                        fetchedPullRequest(487, branch).withOrigin(PullRequest.Origin.OTHER)),
                "renamed-task");

        JsonNode listed = get("/agent/tasks/" + taskId + "/pull-requests?workspaceId=" + workspaceId).getBody();
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).get("number").asInt()).isEqualTo(485);
        assertThat(listed.get(0).get("headRef").asText()).isEqualTo(branch);
        assertThat(listed.get(1).get("number").asInt()).isEqualTo(484);
        assertThat(tasks.get(TaskId.valueOf(taskId)).taskWorktree()).contains(worktree);

        run("git", "-C", worktree.canonicalWorktree().toString(), "switch", "--detach");
        assertThat(get("/agent/tasks/" + taskId + "/pull-requests?workspaceId=" + workspaceId).getBody()).hasSize(1);
    }

    @Test
    void refreshesARenamedTaskBranchBeforeSearchHasItsHeadRef()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        TaskWorktree worktree = tasks.get(TaskId.valueOf(taskId)).taskWorktree().orElseThrow();
        String branch = "new-pr-" + taskId;
        run("git", "-C", worktree.canonicalWorktree().toString(), "branch", "-m", branch);
        pullRequests.saveRepositorySyncPage(REPOSITORY, ImmutableList.of(fetchedPullRequest(488, null)), "search-without-head");
        String path = "/agent/tasks/" + taskId + "/pull-requests";
        assertThat(get(path + "?workspaceId=" + workspaceId).getBody()).isEmpty();
        when(credentials.resolveGitHubCredential()).thenReturn(
                new GitHubCredential("pat", new UserProfile("alice", "Alice", "", "", 0, 0, 0, "", "", "", "")));
        when(gitHub.fetchPullRequestsByHead("pat", RepoRef.of("bytequay", "agent-e2e"), "bytequay:" + branch))
                .thenReturn(ImmutableList.of(fetchedPullRequest(488, branch)));

        ResponseEntity<JsonNode> response = post(path + "/sync?workspaceId=" + workspaceId, ImmutableMap.of(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).get("number").asInt()).isEqualTo(488);
        assertThat(response.getBody().get(0).get("headRef").asText()).isEqualTo(branch);
        assertThat(get(path + "?workspaceId=" + workspaceId).getBody()).hasSize(1);
        assertThat(post(path + "/sync?workspaceId=another-workspace", ImmutableMap.of(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(gitHub, times(1)).fetchPullRequestsByHead("pat", RepoRef.of("bytequay", "agent-e2e"), "bytequay:" + branch);
    }

    @Test
    void discoversUpstreamPullRequestsForATaskInADifferentlyNamedFork()
            throws Exception
    {
        RepoRef fork = RepoRef.of("chenjian2664", "trino_new");
        RepoRef upstream = RepoRef.of("trinodb", "trino");
        String origin = "https://github.com/" + fork.fullName() + ".git";
        String upstreamUrl = "https://github.com/" + upstream.fullName() + ".git";
        Workspace previous = workspaces.findById(workspaceId).orElseThrow();
        long upstreamId = NEXT_REPOSITORY_ID.incrementAndGet();
        workspaces.save(new Workspace(
                workspaceId, previous.name(), previous.selectedRepositoryId(), fork,
                Optional.of(upstreamId), Optional.of(upstream), upstreamId, upstream, Workspace.Topology.FORK,
                "main", origin, Optional.of("master"), Optional.of(upstreamUrl), repository.toString(),
                previous.lastCodeSyncAt(), previous.lastMetadataSyncAt(), previous.displayOrder(), previous.createdAt(), Instant.now()));
        run("git", "-C", repository.toString(), "remote", "set-url", "origin", origin);
        run("git", "-C", repository.toString(), "remote", "add", "upstream", upstreamUrl);
        run("git", "-C", repository.toString(), "update-ref", "refs/remotes/upstream/master", "HEAD");
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        AgentTask task = tasks.get(TaskId.valueOf(taskId));
        TaskWorktree worktree = task.taskWorktree().orElseThrow();
        assertThat(task.repository()).isEqualTo(new RepositoryIdentity(fork.owner(), fork.repo()));
        assertThat(worktree.physicalRepository()).isEqualTo(task.repository());
        String branch = "reuse-resolved-field-index";
        run("git", "-C", worktree.canonicalWorktree().toString(), "branch", "-m", branch);
        pullRequests.saveRepositorySyncPage(upstream.fullName(), ImmutableList.of(fetchedPullRequest(upstream.fullName(), 31022, branch)), "upstream-pr");
        pullRequests.saveRepositorySyncPage(fork.fullName(), ImmutableList.of(fetchedPullRequest(fork.fullName(), 123, branch)), "unrelated-fork-pr");
        String path = "/agent/tasks/" + taskId + "/pull-requests";

        JsonNode cached = get(path + "?workspaceId=" + workspaceId).getBody();
        assertThat(cached).hasSize(1);
        assertThat(cached.get(0).get("repo").asText()).isEqualTo("trinodb/trino");
        assertThat(cached.get(0).get("number").asInt()).isEqualTo(31022);
        when(credentials.resolveGitHubCredential()).thenReturn(
                new GitHubCredential("pat", new UserProfile("chenjian2664", "Chen", "", "", 0, 0, 0, "", "", "", "")));
        // The user's own fork branch: its pull request is theirs, which a renamed-branch match requires.
        when(gitHub.fetchPullRequestsByHead("pat", upstream, "chenjian2664:" + branch))
                .thenReturn(ImmutableList.of(fetchedPullRequest(upstream.fullName(), 31023, branch, "chenjian2664")));

        JsonNode refreshed = post(path + "/sync?workspaceId=" + workspaceId, ImmutableMap.of(), null).getBody();

        assertThat(refreshed).hasSize(2);
        assertThat(refreshed.get(0).get("repo").asText()).isEqualTo("trinodb/trino");
        assertThat(refreshed.get(0).get("number").asInt()).isEqualTo(31023);
        verify(gitHub).fetchPullRequestsByHead("pat", upstream, "chenjian2664:" + branch);
        verify(gitHub).fetchPullRequestsByHead("pat", upstream, "chenjian2664:" + worktree.generatedBranch());
        verify(gitHub, never()).fetchPullRequestsByHead("pat", fork, "chenjian2664:" + branch);
    }

    private static FetchedPullRequest fetchedPullRequest(int number, String headRef)
    {
        return fetchedPullRequest(REPOSITORY, number, headRef);
    }

    private static FetchedPullRequest fetchedPullRequest(String repository, int number, String headRef)
    {
        return fetchedPullRequest(repository, number, headRef, "alice");
    }

    private static FetchedPullRequest fetchedPullRequest(String repository, int number, String headRef, String author)
    {
        Instant at = Instant.parse("2026-09-01T00:00:00Z");
        return new FetchedPullRequest(
                (long) number,
                repository,
                number,
                "PR " + number,
                author,
                "https://github.com/" + repository + "/pull/" + number,
                at,
                at,
                PullRequest.Origin.AUTHORED,
                ImmutableList.of(),
                ImmutableMap.of(),
                false,
                "open",
                null,
                null,
                ImmutableList.of(),
                null,
                null,
                headRef);
    }

    @Test
    void rejectsInvalidAndUnavailableTaskRequests()
            throws Exception
    {
        ResponseEntity<JsonNode> invalid = post(
                "/agent/tasks",
                ImmutableMap.of(
                        "workspaceId", "",
                        "laneId", "codex",
                        "message", "Invalid"),
                null);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> unsupported = post(
                "/agent/tasks",
                createTaskBody("missing-lane"),
                null);
        assertThat(unsupported.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        ResponseEntity<JsonNode> unknownModel = post(
                "/agent/tasks",
                createTaskBody("codex", "unknown-model", "Unknown model"),
                null);
        assertThat(unknownModel.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/agent/tasks?workspaceId=" + workspaceId).getBody()).isEmpty();
        assertThat(workspacePaths.worktrees(workspaceId)).doesNotExist();
    }

    @Test
    void rejectsTaskCreationWhenRepositorySynchronizationFails()
            throws Exception
    {
        when(provisioning.synchronizeCode(workspaceId)).thenReturn(new WorkspaceCodeSyncResult(workspaceId, false, Optional.of(Instant.now())));

        ResponseEntity<JsonNode> response = post("/agent/tasks", createTaskBody("codex"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().required("message").asText()).contains("synchronization failed");
        assertThat(get("/agent/tasks?workspaceId=" + workspaceId).getBody()).isEmpty();
        assertThat(workspacePaths.worktrees(workspaceId)).doesNotExist();
        verify(provisioning).synchronizeCode(workspaceId);
    }

    @Test
    void rejectsUnsupportedTaskDefaultSelections()
            throws Exception
    {
        JsonNode created = post("/agent/tasks", createTaskBody("codex"), null).getBody();
        String taskId = created.required("id").asText();
        List<Map<String, Object>> unsupported = ImmutableList.of(
                ImmutableMap.of("model", "unknown-model", "effort", "high", "permissionPolicy", "AUTO"),
                ImmutableMap.of("model", "test-model", "effort", "low", "permissionPolicy", "AUTO"),
                ImmutableMap.of("model", "test-model", "effort", "high", "permissionPolicy", "AUTO", "serviceTier", "fast"),
                ImmutableMap.of("model", "test-model", "effort", "high", "permissionPolicy", "AUTO",
                        "developmentAutomation", ImmutableMap.of("autoDraftPr", true, "autoCiFix", false)));
        HttpHeaders updateHeaders = headers();
        updateHeaders.setContentType(MediaType.APPLICATION_JSON);
        for (Map<String, Object> defaults : unsupported) {
            ResponseEntity<JsonNode> refused = rest.exchange(
                    url("/agent/tasks/" + taskId + "/defaults?workspaceId=" + workspaceId),
                    HttpMethod.PUT,
                    new HttpEntity<>(objectMapper.writeValueAsString(ImmutableMap.of(
                            "expectedVersion", taskVersion(taskId),
                            "defaults", defaults)), updateHeaders),
                    JsonNode.class);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId).getBody().required("defaults"))
                    .isEqualTo(created.required("defaults"));
        }
    }

    @Test
    void rejectsRendererSuppliedAttachmentPaths()
            throws Exception
    {
        ResponseEntity<JsonNode> createResponse = post("/agent/tasks", createTaskBody("codex"), null);
        String taskId = createResponse.getBody().required("id").asText();

        ResponseEntity<JsonNode> response = post(
                "/agent/tasks/" + taskId + "/turns",
                ImmutableMap.of(
                        "message", "inspect this image",
                        "expectedVersion", taskVersion(taskId),
                        "attachments", ImmutableList.of(ImmutableMap.of(
                                "name", "screen.png",
                                "mediaType", "image/png",
                                "path", "/tmp/screen.png"))),
                "attachment-request");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsAStartWhenTaskDefaultsChangedAfterTheRendererObservedThem()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        long observedVersion = taskVersion(taskId);
        updateDefaults(taskId, "text-only-model");

        ResponseEntity<JsonNode> stale = post(
                "/agent/tasks/" + taskId + "/turns",
                ImmutableMap.of("message", "stale", "expectedVersion", observedVersion),
                "stale-defaults-start");

        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody())
                .filteredOn(event -> event.required("type").asText().equals("user_message"))
                .hasSize(1);
    }

    @Test
    void completesOnlyInteractiveTasksFromTheirWorkspace()
            throws Exception
    {
        JsonNode interactive = post("/agent/tasks", createTaskBody("codex"), null).getBody();
        String interactiveTaskId = interactive.required("id").asText();

        ResponseEntity<JsonNode> wrongWorkspace = post(
                "/agent/tasks/" + interactiveTaskId + "/complete?workspaceId=another-workspace",
                ImmutableMap.of(),
                null);
        assertThat(wrongWorkspace.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(
                "/agent/tasks/" + interactiveTaskId + "/cleanup?workspaceId=another-workspace",
                ImmutableMap.of(),
                null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/agent/tasks/" + interactiveTaskId + "?workspaceId=" + workspaceId)
                .getBody().required("state").asText()).isEqualTo("OPEN");

        TaskId workflowTaskId = TaskId.random();
        var workflowTask = tasks.create(new CreateAgentTaskCommand(
                workflowTaskId,
                workspaceId,
                AgentLaneId.CODEX_CLI,
                RepositoryIdentity.valueOf(REPOSITORY),
                run("git", "-C", repository.toString(), "rev-parse", "--verify", "HEAD^{commit}"),
                TaskBranches.generate(workflowTaskId, "user", "Workflow-owned task"),
                "Workflow-owned task",
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO)));
        ResponseEntity<JsonNode> nonInteractive = post(
                "/agent/tasks/" + workflowTaskId + "/complete?workspaceId=" + workspaceId,
                ImmutableMap.of(),
                null);
        assertThat(nonInteractive.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(
                "/agent/tasks/" + workflowTaskId + "/cleanup?workspaceId=" + workspaceId,
                ImmutableMap.of(),
                null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(tasks.get(workflowTaskId).state().name()).isEqualTo("OPEN");
        assertThat(workflowTask.taskWorktree().orElseThrow().canonicalWorktree()).exists();
    }

    @Test
    void archivesRestoresAndDeletesATaskWithItsBranch()
            throws Exception
    {
        JsonNode created = post("/agent/tasks", createTaskBody("codex"), null).getBody();
        String taskId = created.required("id").asText();
        assertThat(created.required("archivedAt").isNull()).isTrue();
        AgentTask task = tasks.get(TaskId.valueOf(taskId));
        String branch = task.taskWorktree().orElseThrow().generatedBranch();
        Path worktree = task.taskWorktree().orElseThrow().canonicalWorktree();
        String taskPath = "/agent/tasks/" + taskId + "?workspaceId=" + workspaceId;
        awaitCondition(() -> get(taskPath).getBody().path("mutationClaim").isNull());
        long version = get(taskPath).getBody().required("version").asLong();

        JsonNode archived = patch(taskPath, ImmutableMap.of("expectedVersion", version, "archived", true)).getBody();
        assertThat(archived.required("archivedAt").isTextual()).isTrue();
        assertThat(archived.required("state").asText()).isEqualTo("OPEN");
        assertThat(get("/agent/tasks?workspaceId=" + workspaceId).getBody()).hasSize(1);
        // The shelf is versioned like a rename: a stale observer cannot flip it.
        assertThat(patch(taskPath, ImmutableMap.of("expectedVersion", version, "archived", false)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        JsonNode restored = patch(taskPath, ImmutableMap.of("expectedVersion", archived.required("version").asLong(), "archived", false)).getBody();
        assertThat(restored.required("archivedAt").isNull()).isTrue();
        assertThat(patch("/agent/tasks/" + taskId + "?workspaceId=another-workspace", ImmutableMap.of("expectedVersion", 0, "archived", true)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Deleting the task is deleting its work: uncommitted files do not stop it.
        Files.writeString(worktree.resolve("scratch.txt"), "half-finished\n");
        assertThat(delete(taskPath).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(get(taskPath).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/agent/tasks?workspaceId=" + workspaceId).getBody()).isEmpty();
        assertThat(worktree).doesNotExist();
        assertThat(run("git", "-C", repository.toString(), "branch", "--list", branch)).isEmpty();
        assertThatThrownBy(() -> resources.require(TaskId.valueOf(taskId))).isInstanceOf(RuntimeException.class);
        assertThat(delete(taskPath).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void marksAnIdleTaskFailedAndRemovesOnlyACleanWorktree()
            throws Exception
    {
        JsonNode clean = post("/agent/tasks", createTaskBody("codex"), null).getBody();
        String cleanTaskId = clean.required("id").asText();
        Path cleanWorktree = tasks.get(TaskId.valueOf(cleanTaskId)).taskWorktree().orElseThrow().canonicalWorktree();
        assertThat(post(
                "/agent/tasks/" + cleanTaskId + "/mark-failed?workspaceId=another-workspace",
                ImmutableMap.of("reason", "wrong workspace"),
                null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> failed = post(
                "/agent/tasks/" + cleanTaskId + "/mark-failed?workspaceId=" + workspaceId,
                ImmutableMap.of("reason", "giving up"),
                null);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(failed.getBody().get("state").asText()).isEqualTo("FAILED");
        assertThat(failed.getBody().get("terminalReason").asText()).isEqualTo("giving up");
        assertThat(failed.getBody().get("mutationClaim").isNull()).isTrue();
        // Nothing was written, so the checkout goes while its branch stays behind as the result.
        assertThat(failed.getBody().get("checkoutRemovedAt").isTextual()).isTrue();
        assertThat(cleanWorktree).doesNotExist();
        assertThat(run("git", "-C", repository.toString(), "branch", "--list", clean.required("generatedBranch").asText()))
                .contains(clean.required("generatedBranch").asText());

        JsonNode dirty = post("/agent/tasks", createTaskBody("codex"), null).getBody();
        String dirtyTaskId = dirty.required("id").asText();
        Path dirtyWorktree = tasks.get(TaskId.valueOf(dirtyTaskId)).taskWorktree().orElseThrow().canonicalWorktree();
        Files.writeString(dirtyWorktree.resolve("notes.txt"), "half-finished\n");

        JsonNode retained = post(
                "/agent/tasks/" + dirtyTaskId + "/mark-failed?workspaceId=" + workspaceId,
                ImmutableMap.of("reason", "giving up"),
                null).getBody();

        // Dirty work is evidence, not garbage: the task fails but its checkout stays for inspection.
        assertThat(retained.get("state").asText()).isEqualTo("FAILED");
        assertThat(retained.get("checkoutRemovedAt").isNull()).isTrue();
        assertThat(dirtyWorktree.resolve("notes.txt")).exists();
    }

    @Test
    void completesATaskWhoseAgentLeftIgnoredBuildOutput()
            throws Exception
    {
        JsonNode created = post("/agent/tasks", createTaskBody("codex"), null).getBody();
        String taskId = created.required("id").asText();
        Path worktree = tasks.get(TaskId.valueOf(taskId)).taskWorktree().orElseThrow().canonicalWorktree();
        Path exclude = repository.resolve(".git/info/exclude");
        Files.writeString(exclude, Files.readString(exclude) + "target/\n");
        Path buildOutput = worktree.resolve("target/classes/Main.class");
        Files.createDirectories(buildOutput.getParent());
        Files.writeString(buildOutput, "compiled\n");
        assertThat(run("git", "-C", worktree.toString(), "status", "--porcelain=v1", "--untracked-files=all")).isEmpty();

        ResponseEntity<JsonNode> completion = post("/agent/tasks/" + taskId + "/complete?workspaceId=" + workspaceId, ImmutableMap.of(), null);

        assertThat(completion.getStatusCode()).as("completion: %s", completion.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode completed = get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId).getBody();
        assertThat(completed.get("state").asText()).isEqualTo("COMPLETED");
        // Removing the checkout would delete the build output, so the checkout stays and says why.
        assertThat(completed.get("checkoutRemovedAt").isNull()).isTrue();
        assertThat(buildOutput).hasContent("compiled\n");
        ResponseEntity<JsonNode> cleanup = post("/agent/tasks/" + taskId + "/cleanup?workspaceId=" + workspaceId, ImmutableMap.of(), null);
        assertThat(cleanup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(cleanup.getBody().get("message").asText()).contains("ignored files");
    }

    @Test
    void stopsATasksRuntimeOnlyWithTheConfirmedGeneration()
            throws Exception
    {
        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        JsonNode created = post("/agent/tasks", createTaskBody("codex"), null).getBody();
        String taskId = created.required("id").asText();
        String impactPath = "/agent/tasks/" + taskId + "/runtime/stop-impact?workspaceId=" + workspaceId;
        String stopPath = "/agent/tasks/" + taskId + "/runtime/stop?workspaceId=" + workspaceId;

        JsonNode idle = get(impactPath).getBody();
        assertThat(idle.get("activeTurnId").isNull()).isTrue();
        assertThat(idle.get("liveProcess").asBoolean()).isTrue();
        assertThat(post(stopPath, ImmutableMap.of("generation", "task:999"), null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        startTurn(taskId, "first", "request-1");
        JsonNode afterTurn = get(impactPath).getBody();
        // The deterministic turn completes at once, but its session, and so its process, stays open.
        assertThat(afterTurn.get("activeTurnId").isNull()).isTrue();
        assertThat(afterTurn.get("liveProcess").asBoolean()).isTrue();
        // A confirmation from before the turn is stale: the turn moved the task on.
        assertThat(post(stopPath, ImmutableMap.of("generation", idle.get("generation").asText()), null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<JsonNode> stopped = post(
                stopPath,
                ImmutableMap.of("generation", afterTurn.get("generation").asText()),
                null);

        assertThat(stopped.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stopped.getBody().get("state").asText()).isEqualTo("OPEN");
        assertThat(stopped.getBody().get("mutationClaim").isNull()).isTrue();
        assertThat(get(impactPath).getBody().get("liveProcess").asBoolean()).isFalse();
    }

    @Test
    void mapsBoundedHistoryPagesAndOpaqueCursorsInBothDirections()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        awaitCondition(() -> get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId)
                .getBody().required("titleSource").asText().equals("AGENT"));
        long latestSequence = 0;
        for (int index = 0; index < 2001; index++) {
            latestSequence = events.append(new UnsequencedAgentEvent(
                    EventId.random(), TaskId.valueOf(taskId), Optional.empty(), AgentEventSource.root(),
                    new AgentEventPayload.AssistantMessage(
                            MessageKind.ANSWER, MessagePhase.FINAL, "history " + index))).sequence();
        }
        String historyPath = "/agent/tasks/" + taskId + "/history";

        ResponseEntity<JsonNode> response = get(historyPath + "?limit=1000000");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode latest = response.getBody();
        assertThat(latest.required("events")).hasSize(1000);
        assertThat(latest.required("latestSequence").asLong()).isEqualTo(latestSequence);
        assertThat(latest.required("beforeCursor").isTextual()).isTrue();
        assertThat(latest.required("afterCursor").isNull()).isTrue();
        assertThat(latest.required("events").get(0).required("sequence").asLong()).isEqualTo(latestSequence - 999);
        assertThat(latest.required("events").get(999).required("sequence").asLong()).isEqualTo(latestSequence);
        assertThat(latest.required("events").get(999).required("type").asText()).isEqualTo("assistant_message");
        assertThat(latest.required("events").get(999).required("payload").required("text").asText()).isEqualTo("history 2000");

        JsonNode older = get(historyPath + "?before=" + latest.required("beforeCursor").asText()).getBody();
        assertThat(older.required("events")).hasSize(1000);
        assertThat(older.required("events").get(999).required("sequence").asLong()).isEqualTo(latestSequence - 1000);
        assertThat(older.required("latestSequence").asLong()).isEqualTo(latestSequence);
        assertThat(older.required("afterCursor").isTextual()).isTrue();
        JsonNode forward = get(historyPath + "?after=" + older.required("afterCursor").asText()).getBody();
        assertThat(forward).isEqualTo(latest);

        JsonNode oldest = get(historyPath + "?before=" + older.required("beforeCursor").asText()).getBody();
        assertThat(oldest.required("beforeCursor").isNull()).isTrue();
        assertThat(oldest.required("events").get(0).required("sequence").asLong()).isEqualTo(1);
        assertThat(get("/agent/tasks/" + taskId + "/events?after=0&limit=2").getBody()).hasSize(2);
        assertThat(get("/agent/tasks/" + taskId + "/events?after=0&limit=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsInvalidHistoryCursorsAtTheHttpBoundary()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        String historyPath = "/agent/tasks/" + taskId + "/history";

        for (String query : ImmutableList.of("before=-1", "after=-1", "before=0&after=0", "before=not-a-cursor", "after=1", "after=" + Long.MAX_VALUE)) {
            assertThat(get(historyPath + "?" + query).getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(get("/agent/tasks/" + TaskId.random() + "/history").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void exposesDurablePendingApprovalsOnlyUnderTheirOwningTaskAndRemovesResolvedRequests()
            throws Exception
    {
        String ownerTaskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        String otherTaskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        driver.holdNextTurn();
        String turnId = startTurn(ownerTaskId, "wait for approval", "approval-turn").required("id").asText();
        Instant requestedAt = Instant.now();
        ApprovalRequest request = new ApprovalRequest(
                ApprovalId.random(), TaskId.valueOf(ownerTaskId), TurnId.valueOf(turnId),
                new ProviderApprovalRequest(
                        ProviderApprovalRequest.Kind.COMMAND,
                        "Run command",
                        "git status",
                        ImmutableList.of("command:git status"),
                        ImmutableList.of(ProviderApprovalRequest.Decision.DENY),
                        new ProviderAgentEventSource(Optional.of("child-thread"), Optional.of("root-thread"), Optional.of("root/child"),
                                Optional.of("provider-turn"), Optional.of("provider-item")),
                        requestedAt),
                requestedAt);
        try {
            approvals.request(request, Duration.ofMinutes(1));
            String path = "/agent/tasks/" + ownerTaskId + "/pending-approvals?turnId=" + turnId;

            JsonNode pending = get(path).getBody();

            assertThat(pending).hasSize(1);
            JsonNode approval = pending.get(0);
            assertThat(approval.required("approvalId").asText()).isEqualTo(request.id().toString());
            assertThat(approval.required("taskId").asText()).isEqualTo(ownerTaskId);
            assertThat(approval.required("turnId").asText()).isEqualTo(turnId);
            assertThat(approval.required("source").required("providerThreadId").asText()).isEqualTo("child-thread");
            assertThat(approval.required("title").asText()).isEqualTo("Run command");
            assertThat(approval.required("approvalKind").asText()).isEqualTo("COMMAND");
            assertThat(approval.required("availableDecisions").get(0).asText()).isEqualTo("DENY");
            assertThat(approval.required("actionable").asBoolean()).isTrue();
            assertThat(get("/agent/tasks/" + otherTaskId + "/pending-approvals?turnId=" + turnId).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            assertThat(post("/agent/approvals/" + request.id(), ImmutableMap.of("decision", "DENY"), null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get(path).getBody()).isEmpty();
        }
        finally {
            approvals.denyPending(TurnId.valueOf(turnId));
            driver.completeHeldTurn();
        }
    }

    @Test
    void anUnreportedTurnThatChangedNothingSettlesWithoutStoppingAutomation()
            throws Exception
    {
        driver.holdNextTurn();
        Map<String, Object> request = new HashMap<>(createTaskBody("codex"));
        request.put("developmentAutomation", ImmutableMap.of("autoDraftPr", true, "autoCiFix", true));
        JsonNode created = post("/agent/tasks", request, null).getBody();
        String taskId = created.required("id").asText();
        String taskPath = "/agent/tasks/" + taskId + "?workspaceId=" + workspaceId;
        String eventsPath = "/agent/tasks/" + taskId + "/events?after=0&limit=100";
        TaskWorktree worktree = tasks.get(TaskId.valueOf(taskId)).taskWorktree().orElseThrow();
        String unchangedHead = run("git", "-C", worktree.canonicalWorktree().toString(), "rev-parse", "HEAD");
        driver.completeHeldTurn();

        // The turn answered without reporting and left the checkout alone, so automation holds nothing
        // back and has nothing to announce; only an unreported turn that changed code is an error.
        awaitContinuationSettled(taskId);
        JsonNode settled = get(taskPath).getBody();
        assertThat(settled.required("state").asText()).isEqualTo("OPEN");
        assertThat(settled.required("developmentContinuationUnfinished").asBoolean()).isFalse();
        assertThat(settled.path("mutationClaim").isNull()).isTrue();
        assertThat(settled.path("terminalReason").isNull()).isTrue();
        assertThat(get(eventsPath).getBody().findValuesAsText("message"))
                .noneMatch(message -> message.startsWith("Automation stopped."));
        assertThat(tasks.get(TaskId.valueOf(taskId)).taskWorktree()).contains(worktree);
        assertThat(worktree.canonicalWorktree()).isDirectory();

        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        ResponseEntity<JsonNode> followup = post("/agent/tasks/" + taskId + "/turns", ImmutableMap.of(
                "message", "Continue this task",
                "expectedVersion", taskVersion(taskId),
                "developmentAutomation", request.get("developmentAutomation")), "after-missing-readiness");
        assertThat(followup.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        awaitCondition(() -> {
            JsonNode current = get(taskPath).getBody();
            return current.path("mutationClaim").isNull() && !current.required("developmentContinuationUnfinished").asBoolean();
        });
        assertThat(get(taskPath).getBody().required("state").asText()).isEqualTo("OPEN");
        assertThat(tasks.get(TaskId.valueOf(taskId)).taskWorktree()).contains(worktree);
        assertThat(run("git", "-C", worktree.canonicalWorktree().toString(), "rev-parse", "HEAD")).isEqualTo(unchangedHead);
    }

    @Test
    void enabledQuestionKeepsTaskOpenAndPausesPreviouslyQueuedWork()
            throws Exception
    {
        driver.reportNextTurn(new DevelopmentResultReport(false, Optional.of("Which implementation?")));
        driver.holdNextTurn();
        Map<String, Object> request = new HashMap<>(createTaskBody("codex"));
        request.put("defaults", ImmutableMap.of("model", "test-model", "effort", "high", "permissionPolicy", "AUTO",
                "developmentAutomation", ImmutableMap.of("autoDraftPr", true, "autoCiFix", true)));
        JsonNode created = post("/agent/tasks", request, null).getBody();
        String taskId = created.required("id").asText();
        String taskPath = "/agent/tasks/" + taskId + "?workspaceId=" + workspaceId;
        String queuePath = "/agent/tasks/" + taskId + "/queue?workspaceId=" + workspaceId;
        assertThat(post(queuePath, ImmutableMap.of("message", "Previously queued work", "expectedVersion", taskVersion(taskId)), "queued-before-question")
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        driver.completeHeldTurn();
        awaitCondition(() -> get(queuePath).getBody().get(0).path("state").asText().equals("PAUSED"));
        awaitContinuationSettled(taskId);
        JsonNode waiting = get(taskPath).getBody();
        assertThat(waiting.required("state").asText()).isEqualTo("OPEN");
        assertThat(waiting.required("developmentContinuationUnfinished").asBoolean()).isFalse();
        assertThat(waiting.path("mutationClaim").isNull()).isTrue();
        assertThat(post("/agent/tasks/" + taskId + "/queue/resume?workspaceId=" + workspaceId, ImmutableMap.of(), null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        JsonNode history = get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody();
        assertThat(history).anySatisfy(event -> {
            assertThat(event.path("type").asText()).isEqualTo("development_result");
            assertThat(event.path("payload").path("message").asText()).isEqualTo("Which implementation?");
        });

        Path worktree = tasks.get(TaskId.valueOf(taskId)).worktree();
        String unchangedHead = run("git", "-C", worktree.toString(), "rev-parse", "HEAD");
        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        startTurn(taskId, "Use the first implementation", "answer-question");
        awaitCondition(() -> {
            JsonNode current = get(taskPath).getBody();
            return current.path("mutationClaim").isNull() && !current.required("developmentContinuationUnfinished").asBoolean();
        });
        assertThat(run("git", "-C", worktree.toString(), "rev-parse", "HEAD")).isEqualTo(unchangedHead);
        assertThat(get(taskPath).getBody().required("state").asText()).isEqualTo("OPEN");
        assertThat(get(queuePath).getBody()).singleElement().satisfies(input -> assertThat(input.path("state").asText()).isEqualTo("PAUSED"));

        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        assertThat(post("/agent/tasks/" + taskId + "/queue/resume?workspaceId=" + workspaceId, ImmutableMap.of(), null).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        awaitCondition(() -> {
            JsonNode current = get(taskPath).getBody();
            return get(queuePath).getBody().isEmpty() && current.path("mutationClaim").isNull()
                    && !current.required("developmentContinuationUnfinished").asBoolean();
        });
        assertThat(post("/agent/tasks/" + taskId + "/queue/resume?workspaceId=" + workspaceId, ImmutableMap.of(), null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(get("/agent/tasks/" + taskId + "/events?after=0&limit=100").getBody())
                .filteredOn(event -> event.path("type").asText().equals("user_message")
                        && event.path("payload").path("text").asText().equals("Previously queued work"))
                .hasSize(1);
    }

    @Test
    void createsTaskRunsIdempotentTurnsAndReplaysSse()
            throws Exception
    {
        Map<String, Object> createRequest = new HashMap<>(createTaskBody("codex"));
        createRequest.put("message", "first");
        createRequest.put("idempotencyKey", "request-1");
        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        ResponseEntity<JsonNode> createResponse = post("/agent/tasks", createRequest, null);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode createdTask = createResponse.getBody();
        assertThat(createdTask).isNotNull();
        String taskId = createdTask.get("id").asText();
        assertThat(createdTask.get("workspaceId").asText()).isEqualTo(workspaceId);
        assertThat(createdTask.required("generatedBranch").asText())
                .isEqualTo(tasks.get(TaskId.valueOf(taskId)).taskWorktree().orElseThrow().generatedBranch());
        assertThat(createdTask.has("worktree")).isFalse();
        assertThat(createdTask.has("repository")).isFalse();
        assertThat(createdTask.has("sessionReference")).isFalse();

        JsonNode listed = get("/agent/tasks?workspaceId=" + workspaceId + "&limit=10").getBody();
        assertThat(listed).hasSize(1);
        assertThat(get("/agent/tasks/" + taskId + "?workspaceId=another-workspace").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        String firstTurnId = get("/agent/tasks/" + taskId + "/events?after=0&limit=10")
                .getBody().get(0).required("turnId").asText();
        JsonNode first = get("/agent/turns/" + firstTurnId).getBody();
        JsonNode repeated = startTurn(taskId, "ignored", "request-1");
        assertThat(repeated.get("id").asText()).isEqualTo(first.get("id").asText());
        JsonNode firstTerminal = get("/agent/turns/" + first.get("id").asText()).getBody();
        assertThat(firstTerminal.get("state").asText()).isEqualTo("COMPLETED");
        awaitCondition(() -> get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId)
                .getBody().required("titleSource").asText().equals("AGENT"));

        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        JsonNode second = startTurn(taskId, "second", "request-2");
        JsonNode secondTerminal = get("/agent/turns/" + second.get("id").asText()).getBody();
        assertThat(secondTerminal.get("state").asText()).isEqualTo("COMPLETED");

        JsonNode events = get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody();
        assertThat(events).isNotNull();
        assertThat(events).hasSize(13);
        assertThat(events)
                .extracting(event -> event.get("type").asText())
                .containsExactly(
                        "user_message",
                        "lifecycle",
                        "lifecycle",
                        "assistant_message",
                        "development_result",
                        "lifecycle",
                        "lifecycle",
                        "user_message",
                        "lifecycle",
                        "assistant_message",
                        "development_result",
                        "lifecycle",
                        "lifecycle");
        assertThat(events.get(0).get("payload").get("text").asText()).isEqualTo("first");
        assertThat(events.get(1).get("payload").get("phase").asText()).isEqualTo("SESSION_OPENED");
        assertThat(events.get(2).get("payload").get("phase").asText()).isEqualTo("TURN_STARTED");
        assertThat(events.get(3).get("payload").get("text").asText()).isEqualTo("deterministic: first");
        assertThat(events.get(4).get("payload").get("readyForDelivery").asBoolean()).isTrue();
        assertThat(events.get(5).get("payload").get("phase").asText()).isEqualTo("TURN_COMPLETED");
        assertThat(events.get(6).get("payload").get("phase").asText()).isEqualTo("DEVELOPMENT_AUTOMATION_UPDATED");
        assertThat(events.get(7).get("payload").get("text").asText()).isEqualTo("second");
        assertThat(events.get(8).get("payload").get("phase").asText()).isEqualTo("TURN_STARTED");
        assertThat(events.get(9).get("payload").get("text").asText()).isEqualTo("deterministic: second");
        assertThat(events.get(10).get("payload").get("readyForDelivery").asBoolean()).isTrue();
        assertThat(events.get(11).get("payload").get("phase").asText()).isEqualTo("TURN_COMPLETED");
        assertThat(events.get(12).get("payload").get("phase").asText()).isEqualTo("DEVELOPMENT_AUTOMATION_UPDATED");
        long firstSequence = events.get(6).get("sequence").asLong();
        assertThat(events.get(0).get("schemaVersion").asInt()).isEqualTo(2);
        assertThat(events.get(0).get("eventId").asText()).isNotBlank();
        assertThat(events.get(0).get("source").isObject()).isTrue();
        assertThat(events.get(0).get("source").get("logicalAgentPath").isNull()).isTrue();

        JsonNode afterFirst = get("/agent/tasks/" + taskId + "/events?after=" + firstSequence + "&limit=10")
                .getBody();
        assertThat(afterFirst).isNotNull();
        assertThat(afterFirst).hasSize(6);
        assertThat(afterFirst.get(0).get("type").asText()).isEqualTo("user_message");
        assertThat(afterFirst.get(0).get("payload").get("text").asText()).isEqualTo("second");
        assertThat(afterFirst.get(1).get("type").asText()).isEqualTo("lifecycle");
        assertThat(afterFirst.get(1).get("payload").get("phase").asText()).isEqualTo("TURN_STARTED");
        assertThat(afterFirst.get(2).get("type").asText()).isEqualTo("assistant_message");
        assertThat(afterFirst.get(2).get("payload").get("text").asText()).isEqualTo("deterministic: second");
        assertThat(afterFirst.get(3).get("type").asText()).isEqualTo("development_result");
        assertThat(afterFirst.get(3).get("payload").get("readyForDelivery").asBoolean()).isTrue();
        assertThat(afterFirst.get(4).get("type").asText()).isEqualTo("lifecycle");
        assertThat(afterFirst.get(4).get("payload").get("phase").asText()).isEqualTo("TURN_COMPLETED");
        assertThat(afterFirst.get(5).get("payload").get("phase").asText()).isEqualTo("DEVELOPMENT_AUTOMATION_UPDATED");
        JsonNode sseEvent = objectMapper.readTree(firstSseData(taskId, firstSequence));
        assertThat(sseEvent).isEqualTo(afterFirst.get(0));

        Map<String, String> completionRequest = ImmutableMap.of("notes", "ready for review");
        ResponseEntity<JsonNode> completion = post(
                "/agent/tasks/" + taskId + "/complete?workspaceId=" + workspaceId,
                completionRequest,
                null);
        assertThat(completion.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completion.getBody().get("summary").get("finalResponse").asText())
                .isEqualTo("deterministic: second");
        assertThat(completion.getBody().get("summary").get("notes").asText())
                .isEqualTo("ready for review");
        JsonNode finalTask = get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId).getBody();
        assertThat(finalTask.get("state").asText()).isEqualTo("COMPLETED");

        ObjectNode transcript = objectMapper.createObjectNode();
        transcript.set("createRequest", objectMapper.valueToTree(createRequest));
        transcript.set("createResponse", createdTask);
        ArrayNode turns = transcript.putArray("turns");
        addTurnTranscript(turns, "request-1", "first", first, firstTerminal);
        addTurnTranscript(turns, "request-1", "ignored", repeated, firstTerminal);
        addTurnTranscript(turns, "request-2", "second", second, secondTerminal);
        transcript.set("replay", events);
        transcript.set("replayAfterFirst", afterFirst);
        transcript.set("sse", sseEvent);
        transcript.set("completionRequest", objectMapper.valueToTree(completionRequest));
        transcript.set("completionResponse", completion.getBody());
        transcript.set("finalTask", finalTask);

        JsonNode sanitizedTranscript = sanitizeTranscript(
                transcript,
                taskId,
                first.get("id").asText(),
                second.get("id").asText());
        try (InputStream fixture = getClass().getResourceAsStream(BASELINE_FIXTURE)) {
            assertThat(fixture).as("baseline fixture %s", BASELINE_FIXTURE).isNotNull();
            assertThat(sanitizedTranscript).isEqualTo(objectMapper.readTree(fixture));
        }

        ResponseEntity<JsonNode> repeatedCleanup = post(
                "/agent/tasks/" + taskId + "/cleanup?workspaceId=" + workspaceId,
                ImmutableMap.of(),
                null);
        assertThat(repeatedCleanup.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeatedCleanup.getBody().required("state").asText()).isEqualTo("COMPLETED");
        assertThat(repeatedCleanup.getBody().required("checkoutRemovedAt").isTextual()).isTrue();
    }

    @Test
    void retriesImmediateWorktreeCleanupWithoutWaitingForRuntimeRetention()
            throws Exception
    {
        JsonNode created = post("/agent/tasks", createTaskBody("codex"), null).getBody();
        TaskId taskId = TaskId.valueOf(created.required("id").asText());
        Path worktree = tasks.get(taskId).taskWorktree().orElseThrow().canonicalWorktree();
        completions.complete(new CompleteAgentTaskCommand(taskId, Optional.empty()));
        Path retained = worktree.resolve("inspect-me.txt");
        Files.writeString(retained, "retained\n");

        ResponseEntity<JsonNode> failed = post(
                "/agent/tasks/" + taskId + "/cleanup?workspaceId=" + workspaceId,
                ImmutableMap.of(),
                null);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tasks.get(taskId).state().name()).isEqualTo("COMPLETED");
        assertThat(tasks.get(taskId).worktreeRemovedAt()).isEmpty();

        Files.delete(retained);
        ResponseEntity<JsonNode> retried = post(
                "/agent/tasks/" + taskId + "/cleanup?workspaceId=" + workspaceId,
                ImmutableMap.of(),
                null);

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody().required("checkoutRemovedAt").isTextual()).isTrue();
        assertThat(worktree).doesNotExist();
    }

    @Test
    void createsRunsAndReplaysAClaudeTaskWithoutCrossLaneEvents()
            throws Exception
    {
        ResponseEntity<JsonNode> created = post(
                "/agent/tasks",
                createTaskBody("claude", "test-model", "Claude first"),
                null);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String claudeTaskId = created.getBody().required("id").asText();
        assertThat(created.getBody().required("laneId").asText()).isEqualTo("claude");
        awaitCondition(() -> get("/agent/tasks/" + claudeTaskId + "?workspaceId=" + workspaceId)
                .getBody().required("titleSource").asText().equals("AGENT"));
        assertThat(claudeDriver.openCount()).isEqualTo(2);
        assertThat(driver.openCount()).isZero();

        JsonNode claudeEvents = get("/agent/tasks/" + claudeTaskId + "/events?after=0&limit=20").getBody();
        assertThat(claudeEvents)
                .extracting(event -> event.required("type").asText())
                .containsExactly(
                        "user_message", "lifecycle", "lifecycle", "assistant_message", "lifecycle", "lifecycle");
        assertThat(claudeEvents.get(0).required("payload").required("text").asText()).isEqualTo("Claude first");
        assertThat(claudeEvents.get(3).required("payload").required("text").asText()).isEqualTo("deterministic: Claude first");
        String turnId = claudeEvents.get(0).required("turnId").asText();
        assertThat(get("/agent/turns/" + turnId).getBody().required("state").asText()).isEqualTo("COMPLETED");

        long userSequence = claudeEvents.get(0).required("sequence").asLong();
        JsonNode replay = get("/agent/tasks/" + claudeTaskId + "/events?after=" + userSequence + "&limit=20").getBody();
        assertThat(replay).hasSize(5);
        assertThat(objectMapper.readTree(firstSseData(claudeTaskId, userSequence))).isEqualTo(replay.get(0));

        ResponseEntity<JsonNode> codexCreated = post(
                "/agent/tasks",
                createTaskBody("codex", "test-model", "Codex first"),
                null);
        assertThat(codexCreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String codexTaskId = codexCreated.getBody().required("id").asText();
        awaitCondition(() -> get("/agent/tasks/" + codexTaskId + "?workspaceId=" + workspaceId)
                .getBody().required("titleSource").asText().equals("AGENT"));
        assertThat(driver.openCount()).isEqualTo(2);
        assertThat(claudeDriver.openCount()).isEqualTo(2);
        assertThat(get("/agent/tasks/" + claudeTaskId + "/events?after=0&limit=20").getBody())
                .extracting(event -> event.required("payload").path("text").asText())
                .doesNotContain("Codex first", "deterministic: Codex first");
    }

    @Test
    void replaysCompositeTaskCreationAndRejectsAChangedIntent()
            throws Exception
    {
        Map<String, Object> request = createTaskBody("codex");

        ResponseEntity<JsonNode> first = post("/agent/tasks", request, null);
        verify(provisioning).synchronizeCode(workspaceId);
        when(provisioning.synchronizeCode(workspaceId)).thenReturn(new WorkspaceCodeSyncResult(workspaceId, false, Optional.empty()));
        ResponseEntity<JsonNode> replay = post("/agent/tasks", request, null);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().required("id").asText()).isEqualTo(request.get("taskId"));
        assertThat(replay.getBody().required("id").asText()).isEqualTo(first.getBody().required("id").asText());
        assertThat(get("/agent/tasks?workspaceId=" + workspaceId).getBody()).hasSize(1);
        assertThat(get("/agent/tasks/" + request.get("taskId") + "/events?after=0&limit=20").getBody())
                .filteredOn(event -> event.required("type").asText().equals("user_message"))
                .hasSize(1);

        Map<String, Object> changed = new HashMap<>(request);
        changed.put("message", "a different creation request");
        assertThat(post("/agent/tasks", changed, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        changed = new HashMap<>(request);
        changed.put("idempotencyKey", "a-different-first-turn-key");
        assertThat(post("/agent/tasks", changed, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(provisioning).synchronizeCode(workspaceId);
    }

    @Test
    void compositeCreationTrustsItsCreationProbeButLaterTurnsStillProbe()
            throws Exception
    {
        Map<String, Object> request = createTaskBody("codex");
        long probesBeforeCreation = driver.probeCount();
        driver.probeReadiness(
                AgentProviderStatus.Readiness.READY,
                AgentProviderStatus.Readiness.READY,
                AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);

        ResponseEntity<JsonNode> created = post("/agent/tasks", request, null);
        ResponseEntity<JsonNode> replay = post("/agent/tasks", request, null);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(driver.probeCount()).isEqualTo(probesBeforeCreation + 2);
        String taskId = created.getBody().required("id").asText();
        assertThat(get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody())
                .filteredOn(event -> event.required("type").asText().equals("user_message"))
                .hasSize(1);

        ResponseEntity<JsonNode> laterTurn = post(
                "/agent/tasks/" + taskId + "/turns",
                ImmutableMap.of("message", "Later request", "expectedVersion", taskVersion(taskId)),
                "later-turn");

        assertThat(laterTurn.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(laterTurn.getBody().required("readiness").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(driver.probeCount()).isEqualTo(probesBeforeCreation + 3);
    }

    @Test
    void orphanCompositeReplayReprobesBeforeAddingATurnOrResources()
            throws Exception
    {
        Map<String, Object> request = createTaskBody("codex");
        long probesBeforeCreation = driver.probeCount();
        TaskId taskId = TaskId.valueOf((String) request.get("taskId"));
        String idempotencyKey = (String) request.get("idempotencyKey");
        AgentTask orphan = taskCreator.create(
                taskId,
                workspaceId,
                "Agent HTTP test",
                AgentLaneId.CODEX_CLI,
                new AgentTaskDefaults(Optional.of("test-model"), Optional.of("high"), AgentPermissionPolicy.AUTO)
                        .withDevelopmentAutomation(DevelopmentAutomation.LOCAL_ONLY.forDevelopment()),
                idempotencyKey,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        TaskResources existingResources = resources.require(taskId);
        long runtimeEntries = entryCount(existingResources.runtimeDirectory());
        long worktrees = entryCount(workspacePaths.worktrees(workspaceId));
        assertThat(driver.probeCount()).isEqualTo(probesBeforeCreation + 2);
        assertThat(turns.findTurnByIdempotencyKey(taskId, idempotencyKey)).isEmpty();
        driver.probeReadiness(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);

        ResponseEntity<JsonNode> failedReplay = post("/agent/tasks", request, null);

        assertThat(failedReplay.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(failedReplay.getBody().required("readiness").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(driver.probeCount()).isEqualTo(probesBeforeCreation + 3);
        assertThat(turns.findTurnByIdempotencyKey(taskId, idempotencyKey)).isEmpty();
        assertThat(resources.require(taskId)).isEqualTo(existingResources);
        assertThat(entryCount(existingResources.runtimeDirectory())).isEqualTo(runtimeEntries);
        assertThat(entryCount(workspacePaths.worktrees(workspaceId))).isEqualTo(worktrees);
        assertThat(tasks.get(taskId)).isEqualTo(orphan);
    }

    private void addTurnTranscript(
            ArrayNode turns,
            String idempotencyKey,
            String message,
            JsonNode acceptedResponse,
            JsonNode terminalResponse)
    {
        ObjectNode turn = turns.addObject();
        turn.put("idempotencyKey", idempotencyKey);
        turn.set("request", objectMapper.valueToTree(ImmutableMap.of("message", message)));
        turn.set("acceptedResponse", acceptedResponse);
        turn.set("terminalResponse", terminalResponse);
    }

    private JsonNode sanitizeTranscript(
            JsonNode transcript,
            String taskId,
            String firstTurnId,
            String secondTurnId)
    {
        Map<String, String> values = ImmutableMap.<String, String>builder()
                .put(workspaceId, "<workspace-id>")
                .put(tasks.get(TaskId.valueOf(taskId)).taskWorktree().orElseThrow().generatedBranch(), "<generated-branch>")
                .put(taskId, "<task-id>")
                .put(firstTurnId, "<turn-1>")
                .put(secondTurnId, "<turn-2>")
                .buildOrThrow();
        JsonNode sanitized = transcript.deepCopy();
        sanitizeNode(sanitized, values);
        return sanitized;
    }

    private static void sanitizeNode(JsonNode node, Map<String, String> replacements)
    {
        if (node.isArray()) {
            node.forEach(child -> sanitizeNode(child, replacements));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        ObjectNode object = (ObjectNode) node;
        List<String> fields = new ArrayList<>();
        object.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            JsonNode value = object.get(field);
            if ((field.equals("head") || field.equals("resultHead")) && value.isTextual()) {
                object.put(field, "<head>");
                continue;
            }
            if (field.equals("elapsed") && value.isTextual()) {
                object.put(field, "<duration>");
                continue;
            }
            if (field.equals("eventId") && value.isTextual()) {
                object.put(field, "<event-id>");
                continue;
            }
            if (TIMESTAMP_FIELDS.contains(field) && value.isTextual()) {
                object.put(field, "<timestamp>");
                continue;
            }
            if (value.isTextual()) {
                String text = value.asText();
                for (Map.Entry<String, String> replacement : replacements.entrySet()) {
                    text = text.replace(replacement.getKey(), replacement.getValue());
                }
                object.put(field, text);
                continue;
            }
            sanitizeNode(value, replacements);
        }
    }

    private Map<String, Object> createTaskBody(String laneId)
    {
        return createTaskBody(laneId, "test-model", "Agent HTTP test");
    }

    private Map<String, Object> createTaskBody(String laneId, String model, String message)
    {
        return ImmutableMap.of(
                "taskId", UUID.randomUUID().toString(),
                "workspaceId", workspaceId,
                "laneId", laneId,
                "message", message,
                "idempotencyKey", "create-" + UUID.randomUUID(),
                "defaults", ImmutableMap.of(
                        "model", model,
                        "effort", "high",
                        "permissionPolicy", "AUTO",
                        "developmentAutomation", ImmutableMap.of("autoDraftPr", false, "autoCiFix", false)));
    }

    private JsonNode startTurn(String taskId, String message, String idempotencyKey)
            throws Exception
    {
        ResponseEntity<JsonNode> response = post(
                "/agent/tasks/" + taskId + "/turns",
                ImmutableMap.of("message", message, "expectedVersion", taskVersion(taskId)),
                idempotencyKey);
        assertThat(response.getStatusCode()).as("start response: %s", response.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody();
    }

    private long taskVersion(String taskId)
    {
        return get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId)
                .getBody()
                .required("version")
                .asLong();
    }

    private String uploadImage(String taskId, String name)
            throws Exception
    {
        HttpHeaders upload = headers();
        upload.setContentType(MediaType.IMAGE_PNG);
        upload.set("X-Attachment-Name", name);
        return rest.exchange(
                url("/agent/tasks/" + taskId + "/attachments?workspaceId=" + workspaceId),
                HttpMethod.POST,
                new HttpEntity<>(png(3, 3), upload),
                JsonNode.class).getBody().required("id").asText();
    }

    private void deleteAttachment(String taskId, String attachmentId)
    {
        ResponseEntity<Void> deleted = rest.exchange(
                url("/agent/tasks/" + taskId + "/attachments/" + attachmentId + "?workspaceId=" + workspaceId),
                HttpMethod.DELETE,
                new HttpEntity<>(headers()),
                Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private void updateDefaults(String taskId, String model)
            throws Exception
    {
        HttpHeaders updateHeaders = headers();
        updateHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> updated = rest.exchange(
                url("/agent/tasks/" + taskId + "/defaults?workspaceId=" + workspaceId),
                HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(ImmutableMap.of(
                        "expectedVersion", taskVersion(taskId),
                        "defaults", ImmutableMap.of(
                                "model", model,
                                "effort", "high",
                                "permissionPolicy", "AUTO"))), updateHeaders),
                JsonNode.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void deleteQueued(String taskId, String queuedInputId)
    {
        ResponseEntity<JsonNode> deleted = rest.exchange(
                url("/agent/tasks/" + taskId + "/queue/" + queuedInputId + "?workspaceId=" + workspaceId),
                HttpMethod.DELETE,
                new HttpEntity<>(headers()),
                JsonNode.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<JsonNode> get(String path)
    {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers()), JsonNode.class);
    }

    private ResponseEntity<JsonNode> patch(String path, Object body)
            throws Exception
    {
        HttpHeaders requestHeaders = headers();
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(
                url(path),
                HttpMethod.PATCH,
                new HttpEntity<>(objectMapper.writeValueAsString(body), requestHeaders),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> delete(String path)
    {
        return rest.exchange(url(path), HttpMethod.DELETE, new HttpEntity<>(headers()), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String path, Object body, String idempotencyKey)
            throws Exception
    {
        HttpHeaders requestHeaders = headers();
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            requestHeaders.set("Idempotency-Key", idempotencyKey);
        }
        ResponseEntity<JsonNode> response = rest.exchange(
                url(path),
                HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), requestHeaders),
                JsonNode.class);
        JsonNode result = response.getBody();
        // Immediate provider completion still leaves the real Git checker running; later
        // fixture actions require its ownership to settle. Held provider turns remain immediate.
        if (response.getStatusCode().is2xxSuccessful() && result != null) {
            if (path.equals("/agent/tasks") && result.path("mutationClaim").isNull()) {
                awaitContinuationSettled(result.required("id").asText());
            }
            else if (result.path("state").asText().equals("COMPLETED") && result.hasNonNull("taskId")) {
                awaitContinuationSettled(result.required("taskId").asText());
            }
        }
        return response;
    }

    private void awaitContinuationSettled(String taskId)
    {
        // A terminal provider turn still has real Git inspections to finish across the four test JVMs.
        // This is an integration completion bound, separate from the short in-memory fixture waits.
        await().alias("development continuation for task " + taskId)
                .pollInSameThread()
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(50))
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> {
                    ResponseEntity<JsonNode> response = get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId);
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    JsonNode current = response.getBody();
                    assertThat(current.required("developmentContinuationUnfinished").asBoolean())
                            .as("Unfinished continuation; last task response: %s", current).isFalse();
                    assertThat(current.required("mutationClaim").isNull())
                            .as("Retained mutation claim; last task response: %s", current).isTrue();
                });
    }

    @Test
    void queuesInputBehindARunningTurnAndStartsItAfterSuccess()
            throws Exception
    {
        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        JsonNode created = post("/agent/tasks", createTaskBody("codex"), null).getBody();
        String taskId = created.required("id").asText();
        String queuePath = "/agent/tasks/" + taskId + "/queue?workspaceId=" + workspaceId;
        assertThat(post(queuePath, ImmutableMap.of("message", "too early", "expectedVersion", created.required("version").asLong()), "queue-idle").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        driver.holdNextTurn();
        JsonNode running = startTurn(taskId, "first", "request-1");
        assertThat(running.required("state").asText()).isEqualTo("RUNNING");
        long runningVersion = get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId).getBody().required("version").asLong();

        ResponseEntity<JsonNode> queued = post(queuePath, ImmutableMap.of("message", "later", "expectedVersion", runningVersion), "queue-1");
        assertThat(queued.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(queued.getBody().required("state").asText()).isEqualTo("QUEUED");
        assertThat(queued.getBody().required("preview").asText()).isEqualTo("later");
        assertThat(queued.getBody().required("settings").required("model").asText()).isEqualTo("test-model");
        // Idempotent by key, and refused with the version this screen no longer holds.
        ResponseEntity<JsonNode> repeated = post(queuePath, ImmutableMap.of("message", "ignored", "expectedVersion", runningVersion), "queue-1");
        assertThat(repeated.getBody().required("id").asText()).isEqualTo(queued.getBody().required("id").asText());
        assertThat(post(queuePath, ImmutableMap.of("message", "stale", "expectedVersion", runningVersion), "queue-2").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(get(queuePath).getBody()).hasSize(1);
        assertThat(get("/agent/tasks/" + taskId + "/queue?workspaceId=another-workspace").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Completing must wait for the queue; the successful running turn starts its head once.
        assertThat(post("/agent/tasks/" + taskId + "/complete?workspaceId=" + workspaceId, ImmutableMap.of(), null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        driver.completeHeldTurn();
        await().pollInSameThread()
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(50))
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(get(queuePath).getBody()).isEmpty());
        awaitContinuationSettled(taskId);
        JsonNode events = get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody();
        assertThat(events).anySatisfy(event -> {
            assertThat(event.required("type").asText()).isEqualTo("user_message");
            assertThat(event.required("payload").required("text").asText()).isEqualTo("later");
            assertThat(event.required("payload").required("deliveryKind").asText()).isEqualTo("QUEUED");
        });
        assertThat(post("/agent/tasks/" + taskId + "/queue/resume?workspaceId=" + workspaceId, ImmutableMap.of(), null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        driver.reportNextTurn(new DevelopmentResultReport(true, Optional.empty()));
        driver.holdNextTurn();
        startTurn(taskId, "hold removal", "request-2");
        long version = get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId).getBody().required("version").asLong();
        String removable = post(queuePath, ImmutableMap.of("message", "never mind", "expectedVersion", version), "queue-3").getBody().required("id").asText();
        ResponseEntity<JsonNode> removed = rest.exchange(
                url("/agent/tasks/" + taskId + "/queue/" + removable + "?workspaceId=" + workspaceId),
                HttpMethod.DELETE,
                new HttpEntity<>(headers()),
                JsonNode.class);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(removed.getBody().required("state").asText()).isEqualTo("REMOVED");
        assertThat(get(queuePath).getBody()).isEmpty();
        driver.completeHeldTurn();
        awaitContinuationSettled(taskId);
    }

    @Test
    void cannotRemoveAnotherTasksQueuedInput()
            throws Exception
    {
        String routeTaskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        String ownerTaskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        String ownerQueuePath = "/agent/tasks/" + ownerTaskId + "/queue?workspaceId=" + workspaceId;
        driver.holdNextTurn();
        startTurn(ownerTaskId, "hold", "owner-turn");
        long version = get("/agent/tasks/" + ownerTaskId + "?workspaceId=" + workspaceId).getBody().required("version").asLong();
        String queuedId = post(ownerQueuePath, ImmutableMap.of("message", "owned", "expectedVersion", version), "owner-queue")
                .getBody().required("id").asText();

        ResponseEntity<JsonNode> foreignDelete = rest.exchange(
                url("/agent/tasks/" + routeTaskId + "/queue/" + queuedId + "?workspaceId=" + workspaceId),
                HttpMethod.DELETE,
                new HttpEntity<>(headers()),
                JsonNode.class);

        assertThat(foreignDelete.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(get(ownerQueuePath).getBody()).singleElement().satisfies(input ->
                assertThat(input.required("id").asText()).isEqualTo(queuedId));
        rest.exchange(
                url("/agent/tasks/" + ownerTaskId + "/queue/" + queuedId + "?workspaceId=" + workspaceId),
                HttpMethod.DELETE,
                new HttpEntity<>(headers()),
                JsonNode.class);
        driver.completeHeldTurn();
    }

    @Test
    void refusesToSteerATurnThatIsNotRunning()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        JsonNode finished = startTurn(taskId, "first", "request-1");

        ResponseEntity<JsonNode> steer = post("/agent/turns/" + finished.required("id").asText() + "/steer", ImmutableMap.of("message", "late"), "steer-1");

        assertThat(steer.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody())
                .noneSatisfy(event -> assertThat(event.required("payload").path("deliveryKind").asText()).isEqualTo("STEER"));
    }

    @Test
    void validatesSteerImagesAgainstTheRunningTurnsModelSnapshot()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex", "text-only-model", "Text only"), null)
                .getBody().required("id").asText();
        HttpHeaders upload = headers();
        upload.setContentType(MediaType.IMAGE_PNG);
        upload.set("X-Attachment-Name", "shot.png");
        String attachmentId = rest.exchange(
                url("/agent/tasks/" + taskId + "/attachments?workspaceId=" + workspaceId),
                HttpMethod.POST,
                new HttpEntity<>(png(3, 3), upload),
                JsonNode.class).getBody().required("id").asText();
        driver.holdNextTurn();
        JsonNode running = startTurn(taskId, "keep running", "running-text-turn");
        long version = get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId).getBody().required("version").asLong();
        HttpHeaders updateHeaders = headers();
        updateHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> updated = rest.exchange(
                url("/agent/tasks/" + taskId + "/defaults?workspaceId=" + workspaceId),
                HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(ImmutableMap.of(
                        "expectedVersion", version,
                        "defaults", ImmutableMap.of("model", "test-model", "effort", "high", "permissionPolicy", "AUTO"))), updateHeaders),
                JsonNode.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> refused = post(
                "/agent/turns/" + running.required("id").asText() + "/steer",
                ImmutableMap.of("message", "look", "attachments", ImmutableList.of(attachmentId)),
                "steer-image");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().path("message").asText()).contains("text-only-model").contains("does not accept images");
        driver.completeHeldTurn();
    }

    @Test
    void refusesImagesWhenTheLaneDoesNotAdvertiseTheirMediaType()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        String attachmentId = uploadImage(taskId, "unsupported.png");
        driver.disableImageAttachments();

        ResponseEntity<JsonNode> refused = post(
                "/agent/tasks/" + taskId + "/turns",
                ImmutableMap.of(
                        "message", "look",
                        "attachments", ImmutableList.of(attachmentId),
                        "expectedVersion", taskVersion(taskId)),
                "unsupported-provider-image");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().path("message").asText())
                .contains("Provider codex").contains("image/png");
    }

    @Test
    void replaysAnImageTurnBeforeReadingChangedDefaultsOrADeletedAttachment()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        String attachmentId = uploadImage(taskId, "start.png");
        Map<String, Object> body = ImmutableMap.of(
                "message", "look",
                "attachments", ImmutableList.of(attachmentId),
                "expectedVersion", taskVersion(taskId));

        ResponseEntity<JsonNode> first = post("/agent/tasks/" + taskId + "/turns", body, "image-turn-replay");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        deleteAttachment(taskId, attachmentId);
        updateDefaults(taskId, "text-only-model");

        ResponseEntity<JsonNode> replay = post("/agent/tasks/" + taskId + "/turns", body, "image-turn-replay");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getBody().required("id").asText()).isEqualTo(first.getBody().required("id").asText());
    }

    @Test
    void replaysSteerAndQueueBeforeReadingChangedDefaultsOrADeletedAttachment()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        String attachmentId = uploadImage(taskId, "running.png");
        driver.holdNextTurn();
        JsonNode running = startTurn(taskId, "hold", "held-for-replay");
        Map<String, Object> steerBody = ImmutableMap.of(
                "message", "steer image",
                "attachments", ImmutableList.of(attachmentId));
        Map<String, Object> queueBody = ImmutableMap.of(
                "message", "queue image",
                "attachments", ImmutableList.of(attachmentId),
                "expectedVersion", taskVersion(taskId));

        ResponseEntity<JsonNode> firstSteer = post(
                "/agent/turns/" + running.required("id").asText() + "/steer",
                steerBody,
                "steer-image-replay");
        ResponseEntity<JsonNode> firstQueue = post(
                "/agent/tasks/" + taskId + "/queue?workspaceId=" + workspaceId,
                queueBody,
                "queue-image-replay");
        assertThat(firstSteer.getBody().required("state").asText()).isEqualTo("DELIVERED");
        assertThat(firstQueue.getBody().required("state").asText()).isEqualTo("QUEUED");
        deleteAttachment(taskId, attachmentId);
        updateDefaults(taskId, "text-only-model");

        ResponseEntity<JsonNode> replaySteer = post(
                "/agent/turns/" + running.required("id").asText() + "/steer",
                steerBody,
                "steer-image-replay");
        ResponseEntity<JsonNode> replayQueue = post(
                "/agent/tasks/" + taskId + "/queue?workspaceId=" + workspaceId,
                queueBody,
                "queue-image-replay");
        assertThat(replaySteer.getBody().required("id").asText())
                .isEqualTo(firstSteer.getBody().required("id").asText());
        assertThat(replayQueue.getBody().required("id").asText())
                .isEqualTo(firstQueue.getBody().required("id").asText());

        deleteQueued(taskId, replayQueue.getBody().required("id").asText());
        driver.completeHeldTurn();
    }

    @Test
    void createsATaskWithItsFirstImageAndReplaysWithoutUploadingAgain()
            throws Exception
    {
        Map<String, Object> request = createTaskBody("codex");
        byte[] image = png(9, 4);
        ResponseEntity<JsonNode> created = createTaskWithImage(request, "failure.png", MediaType.IMAGE_PNG, image);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String taskId = created.getBody().required("id").asText();
        JsonNode events = get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody();
        JsonNode message = StreamSupport.stream(events.spliterator(), false)
                .filter(event -> event.path("type").asText().equals("user_message"))
                .findFirst().orElseThrow();
        String attachmentId = message.required("payload").required("attachments").get(0).required("id").asText();
        assertThat(message.required("payload").required("attachments").get(0).required("name").asText()).isEqualTo("failure.png");
        assertThat(Files.readAllBytes(resources.readAttachment(TaskId.valueOf(taskId), attachmentId).path())).isEqualTo(image);
        assertThat(turns.findTurnByIdempotencyKey(TaskId.valueOf(taskId), request.get("idempotencyKey").toString()).orElseThrow()
                .settings().orElseThrow().attachments()).singleElement().satisfies(attachment -> assertThat(attachment.id()).isEqualTo(attachmentId));
        deleteAttachment(taskId, attachmentId);
        assertThat(createTaskWithImage(request, "failure.png", MediaType.IMAGE_PNG, image).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createTaskWithImage(request, "failure.png", MediaType.IMAGE_PNG, png(10, 4)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody())
                .filteredOn(event -> event.path("type").asText().equals("user_message"))
                .hasSize(1);
        assertThat(get("/agent/tasks?workspaceId=" + workspaceId).getBody()).hasSize(1);
    }

    @Test
    void rejectsInvalidInitialImagesBeforeAllocatingTasksOrWorktrees()
            throws Exception
    {
        assertThat(createTaskWithImage(createTaskBody("codex"), "bad.png", MediaType.IMAGE_PNG, new byte[] {1, 2, 3}).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createTaskWithImage(createTaskBody("codex", "text-only-model", "Look"), "image.png", MediaType.IMAGE_PNG, png(3, 3)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createTaskWithImage(createTaskBody("codex"), "wrong.jpg", MediaType.IMAGE_JPEG, png(3, 3)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/agent/tasks?workspaceId=" + workspaceId).getBody()).isEmpty();
        assertThat(workspacePaths.worktrees(workspaceId)).doesNotExist();
    }

    private ResponseEntity<JsonNode> createTaskWithImage(Map<String, Object> request, String name, MediaType mediaType, byte[] bytes)
    {
        HttpHeaders metadataHeaders = new HttpHeaders();
        metadataHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpHeaders imageHeaders = new HttpHeaders();
        imageHeaders.setContentType(mediaType);
        imageHeaders.setContentDispositionFormData("images", name);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("request", new HttpEntity<>(request, metadataHeaders));
        parts.add("images", new HttpEntity<>(bytes, imageHeaders));
        HttpHeaders requestHeaders = headers();
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange(url("/agent/tasks"), HttpMethod.POST, new HttpEntity<>(parts, requestHeaders), JsonNode.class);
    }

    @Test
    void storesATaskOwnedImageAndSendsItWithATurn()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();

        HttpHeaders upload = headers();
        upload.setContentType(MediaType.IMAGE_PNG);
        upload.set("X-Attachment-Name", "failing-test.png");
        ResponseEntity<JsonNode> stored = rest.exchange(
                url("/agent/tasks/" + taskId + "/attachments?workspaceId=" + workspaceId),
                HttpMethod.POST,
                new HttpEntity<>(png(9, 4), upload),
                JsonNode.class);
        assertThat(stored.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String attachmentId = stored.getBody().required("id").asText();
        assertThat(stored.getBody().required("name").asText()).isEqualTo("failing-test.png");
        assertThat(stored.getBody().required("width").asInt()).isEqualTo(9);
        assertThat(stored.getBody().required("height").asInt()).isEqualTo(4);

        ResponseEntity<byte[]> bytes = rest.exchange(
                url("/agent/tasks/" + taskId + "/attachments/" + attachmentId + "?workspaceId=" + workspaceId),
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                byte[].class);
        assertThat(bytes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bytes.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(bytes.getBody()).isEqualTo(png(9, 4));

        // The turn's durable user message names the attachment by the reference the upload returned.
        JsonNode turn = post(
                "/agent/tasks/" + taskId + "/turns",
                ImmutableMap.of(
                        "message", "What is in this screenshot?",
                        "attachments", ImmutableList.of(attachmentId),
                        "expectedVersion", taskVersion(taskId)),
                "attach-1").getBody();
        assertThat(turn.required("settings").required("attachments").get(0).required("id").asText()).isEqualTo(attachmentId);
        JsonNode events = get("/agent/tasks/" + taskId + "/events?after=0&limit=10").getBody();
        assertThat(events)
                .filteredOn(event -> event.path("payload").path("attachments").size() == 1)
                .singleElement()
                .satisfies(userMessage -> {
                    assertThat(userMessage.required("type").asText()).isEqualTo("user_message");
                    assertThat(userMessage.required("payload").required("attachments").get(0).required("id").asText())
                            .isEqualTo(attachmentId);
                });

        // A reference nobody issued, and a task that does not own the image, get nothing.
        assertThat(rest.exchange(
                url("/agent/tasks/" + taskId + "/attachments/not-an-attachment?workspaceId=" + workspaceId),
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                byte[].class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String otherTask = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        assertThat(rest.exchange(
                url("/agent/tasks/" + otherTask + "/attachments/" + attachmentId + "?workspaceId=" + workspaceId),
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                byte[].class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void refusesAnImageForAModelThatDoesNotAcceptOne()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex", "text-only-model", "Text only"), null)
                .getBody().required("id").asText();
        HttpHeaders upload = headers();
        upload.setContentType(MediaType.IMAGE_PNG);
        upload.set("X-Attachment-Name", "shot.png");
        String attachmentId = rest.exchange(
                url("/agent/tasks/" + taskId + "/attachments?workspaceId=" + workspaceId),
                HttpMethod.POST,
                new HttpEntity<>(png(3, 3), upload),
                JsonNode.class).getBody().required("id").asText();

        ResponseEntity<JsonNode> refused = post(
                "/agent/tasks/" + taskId + "/turns",
                ImmutableMap.of(
                        "message", "look",
                        "attachments", ImmutableList.of(attachmentId),
                        "expectedVersion", taskVersion(taskId)),
                "attach-2");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().path("message").asText()).contains("does not accept images");

        // Bytes that are not the claimed image never become an attachment.
        upload.setContentType(MediaType.IMAGE_JPEG);
        assertThat(rest.exchange(
                url("/agent/tasks/" + taskId + "/attachments?workspaceId=" + workspaceId),
                HttpMethod.POST,
                new HttpEntity<>(png(3, 3), upload),
                JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static byte[] png(int width, int height)
            throws Exception
    {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    @Test
    void commitsTheStreamBeforeTheTaskHasAnyEvent()
            throws Exception
    {
        String taskId = post("/agent/tasks", createTaskBody("codex"), null).getBody().required("id").asText();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url("/agent/tasks/" + taskId + "/stream?after=0")))
                .header(AgentController.SESSION_HEADER, SESSION_TOKEN)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<Stream<String>> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofLines());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        try (Stream<String> lines = response.body()) {
            assertThat(lines.findFirst()).contains(":connected");
        }
    }

    private String firstSseData(String taskId, long after)
            throws IOException, InterruptedException
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(
                        "/agent/tasks/" + taskId + "/stream?after=" + after)))
                .header(AgentController.SESSION_HEADER, SESSION_TOKEN)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<Stream<String>> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        try (Stream<String> lines = response.body()) {
            return lines.filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring("data:".length()).stripLeading())
                    .findFirst()
                    .orElseThrow();
        }
    }

    private HttpHeaders headers()
    {
        HttpHeaders headers = new HttpHeaders();
        headers.set(AgentController.SESSION_HEADER, SESSION_TOKEN);
        return headers;
    }

    private static void awaitCondition(BooleanSupplier condition)
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            checkState(System.nanoTime() < deadline, "condition was not met before the deadline");
            try {
                Thread.sleep(5);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting", e);
            }
        }
    }

    private static long entryCount(Path directory)
            throws IOException
    {
        try (Stream<Path> entries = Files.walk(directory)) {
            return entries.count();
        }
    }

    private String url(String path)
    {
        return "http://127.0.0.1:" + port + path;
    }

    private static String run(String... command)
            throws IOException, InterruptedException
    {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        checkState(exitCode == 0, "Command failed: " + output);
        return output.strip();
    }
}
