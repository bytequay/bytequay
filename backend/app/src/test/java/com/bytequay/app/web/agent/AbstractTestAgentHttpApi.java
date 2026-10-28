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
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.app.agent.DevelopmentGitInspector;
import com.bytequay.app.agent.DevelopmentPublicationFacts;
import com.bytequay.app.agent.InteractiveAgentTaskCreator;
import com.bytequay.app.agent.impl.DeterministicAgentDriver;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceCodeSyncResult;
import com.bytequay.app.workspace.WorkspaceHostingRead;
import com.bytequay.app.workspace.WorkspaceHostingService;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.app.workspace.WorkspaceProvisioningService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingCacheTarget;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.RemoteHostingAccount;
import com.bytequay.core.hosting.RemoteHostingRepository;
import com.bytequay.core.store.HostingIdentityStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static com.bytequay.app.git.GitTestSupport.configureUser;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.google.common.base.Preconditions.checkState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Spring context and fixtures shared by the agent HTTP tests. The tests are split across subclasses so
 * Surefire's forks share their runtime. Subclasses add no Spring configuration, so each fork boots
 * this context once.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.shutdown=immediate",
                "bytequay.agent.desktop-session-token=test-session",
                "bytequay.agent.runtime-root=${java.io.tmpdir}/bytequay-agent-http-${random.uuid}",
                "bytequay.workspace.root=${java.io.tmpdir}/bytequay-agent-workspace-${random.uuid}"
        })
@Import(AbstractTestAgentHttpApi.DeterministicLane.class)
abstract class AbstractTestAgentHttpApi
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

        @Bean
        @Primary
        WorkspaceProvisioningService testWorkspaceProvisioningService()
        {
            return mock(WorkspaceProvisioningService.class);
        }
    }

    protected static final String SESSION_TOKEN = "test-session";
    protected static final String REPOSITORY = "bytequay/agent-e2e";
    private static final String ORIGIN = "https://github.com/" + REPOSITORY + ".git";
    protected static final AtomicLong NEXT_REPOSITORY_ID = new AtomicLong(10_000);

    @LocalServerPort
    private int port;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected WorkspacePaths workspacePaths;

    @Autowired
    protected WorkspaceStore workspaces;

    @Autowired
    protected WorkspaceProvisioningService provisioning;

    @Autowired
    private ModelSettingsService models;

    @Autowired
    protected AgentTaskService tasks;

    @Autowired
    protected AgentTurnService turns;

    @Autowired
    protected AgentEventService events;

    @Autowired
    protected AgentApprovalService approvals;

    @Autowired
    protected AgentResourceService resources;

    @Autowired
    protected AgentCompletionService completions;

    @Autowired
    protected InteractiveAgentTaskCreator taskCreator;

    @Autowired
    @Qualifier("deterministicAgentDriver")
    protected DeterministicAgentDriver driver;

    @Autowired
    @Qualifier("deterministicClaudeAgentDriver")
    protected DeterministicAgentDriver claudeDriver;

    @Autowired
    protected PullRequestStore pullRequests;

    @Autowired
    protected RepositoryIssueStore issueRows;

    @Autowired
    protected HostingIdentityStore hostingIdentities;

    @Autowired
    protected WorkItemStore workItems;

    @MockitoBean
    protected GitHubRepoClient issueClient;

    @MockitoBean
    protected JiraService jira;

    @MockitoBean
    protected GitHubPullRequestReadClient gitHub;

    @MockitoBean
    protected CredentialService credentials;

    @MockitoBean
    protected WorkspaceHostingService workspaceHosting;

    @MockitoSpyBean
    private DevelopmentGitInspector developmentGit;

    protected String workspaceId;
    protected Path repository;

    @BeforeEach
    void setUp()
            throws IOException, InterruptedException
    {
        // The documentation dependency must not make this client retry a rejected HTTP action.
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        driver.reset();
        claudeDriver.reset();
        reset(provisioning);
        when(credentials.resolveGitHubCredential()).thenReturn(new GitHubCredential("pat", new UserProfile("contributor", "Sam", "", "", 0, 0, 0, "", "", "", "")));
        doReturn(new DevelopmentPublicationFacts(Optional.empty(), ImmutableList.of())).when(developmentGit).inspectPublication(any(), any());
        models.refreshCatalogs();
        workspaceId = "agent-http-" + UUID.randomUUID();
        long repositoryId = NEXT_REPOSITORY_ID.incrementAndGet();
        repository = workspacePaths.repository(workspaceId);
        Files.createDirectories(repository);
        Files.writeString(workspacePaths.workspace(workspaceId).resolve(".bytequay-workspace"), workspaceId + "\n" + repositoryId + "\n");
        run("git", "init", "-b", "main", repository.toString());
        configureUser(repository, "ByteQuay Test", "test@bytequay.local");
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
        useWorkspaceHosting("contributor");
        when(provisioning.synchronizeCode(workspaceId)).thenReturn(new WorkspaceCodeSyncResult(workspaceId, true, Optional.of(now), ImmutableSet.of()));
    }

    protected void useWorkspaceHosting(String login)
    {
        Workspace workspace = workspaces.findById(workspaceId).orElseThrow();
        var instance = hostingIdentities.upsertInstance(new HostingServer(GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com")));
        var account = hostingIdentities.upsertAccount(instance.id(), new RemoteHostingAccount(login, login));
        HostingReadSession session = new HostingReadSession(account.id(), "test-workspace:" + workspaceId, new HostingAccess(instance, "pat"));
        String path = workspace.monitoredRepository().fullName();
        var hosted = hostingIdentities.upsertRepository(instance.id(), new RemoteHostingRepository(Long.toString(workspace.monitoredRepositoryId()), path,
                URI.create("https://github.com/" + path), URI.create("https://github.com/" + path + ".git"), Optional.empty(),
                Optional.of(workspace.upstreamDefaultBranch().orElse(workspace.defaultBranch()))));
        workspaces.saveHostingCacheTarget(workspaceId, new HostingCacheTarget(account.id(), hosted.id(), session.credentialScope()));
        when(workspaceHosting.captureRead(workspaceId)).thenReturn(new WorkspaceHostingRead(session, hosted, login));
        when(workspaceHosting.viewerLogin(workspaceId)).thenReturn(Optional.of(login));
        when(workspaceHosting.cachedRepositoryId(workspaceId)).thenReturn(Optional.of(hosted.id()));
    }

    protected Map<String, Object> createTaskBody(String laneId)
    {
        return createTaskBody(laneId, "test-model", "Agent HTTP test");
    }

    protected Map<String, Object> createTaskBody(String laneId, String model, String message)
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
                        "developmentAutomation", ImmutableMap.of("level", "COMMIT")));
    }

    protected JsonNode startTurn(String taskId, String message, String idempotencyKey)
            throws Exception
    {
        ResponseEntity<JsonNode> response = post("/agent/tasks/" + taskId + "/turns", ImmutableMap.of("message", message, "expectedVersion", taskVersion(taskId)), idempotencyKey);
        assertThat(response.getStatusCode()).as("start response: %s", response.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody();
    }

    protected long taskVersion(String taskId)
    {
        return get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId)
                .getBody()
                .required("version")
                .asLong();
    }

    protected void updateDefaults(String taskId, String model)
            throws Exception
    {
        HttpHeaders updateHeaders = headers();
        updateHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> updated = rest.exchange(
                url("/agent/tasks/" + taskId + "/defaults?workspaceId=" + workspaceId),
                HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(ImmutableMap.of(
                        "expectedVersion", taskVersion(taskId),
                        "defaults", ImmutableMap.of("model", model, "effort", "high", "permissionPolicy", "AUTO"))), updateHeaders),
                JsonNode.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    protected ResponseEntity<JsonNode> get(String path)
    {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers()), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> delete(String path)
    {
        return rest.exchange(url(path), HttpMethod.DELETE, new HttpEntity<>(headers()), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> post(String path, Object body, String idempotencyKey)
            throws Exception
    {
        HttpHeaders requestHeaders = headers();
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            requestHeaders.set("Idempotency-Key", idempotencyKey);
        }
        ResponseEntity<JsonNode> response = rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(objectMapper.writeValueAsString(body), requestHeaders), JsonNode.class);
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

    protected void awaitContinuationSettled(String taskId)
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

    protected HttpHeaders headers()
    {
        HttpHeaders headers = new HttpHeaders();
        headers.set(AgentController.SESSION_HEADER, SESSION_TOKEN);
        return headers;
    }

    protected static void awaitCondition(BooleanSupplier condition)
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

    protected String url(String path)
    {
        return "http://127.0.0.1:" + port + path;
    }

    protected static String run(String... command)
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
