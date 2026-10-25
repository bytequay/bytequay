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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskBranches;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.workitem.GoalReferenceReader;
import com.bytequay.app.workspace.WorkspaceCodeSyncResult;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemEvent;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.integration.jira.JiraDtos;
import com.bytequay.integration.jira.JiraIssue;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAgentTaskHttpApi
        extends AbstractTestAgentHttpApi
{
    @Test
    void protectsAgentEndpointsAndReportsProviderStatus()
            throws Exception
    {
        assertThat(rest.getForEntity(url("/agent/providers/status"), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders originHeaders = headers();
        originHeaders.setOrigin("https://example.com");
        ResponseEntity<JsonNode> originResponse = rest.exchange(url("/agent/providers/status"), HttpMethod.GET, new HttpEntity<>(originHeaders), JsonNode.class);
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
                ImmutableList.of(), Optional.empty(), Optional.empty(), false, false, updated, Optional.empty(), Optional.empty(), ImmutableList.of(), false,
                Optional.empty(), Optional.of(new JiraDtos.WorkspaceLink(workspaceId, "Workspace")));
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
                "alice", "", source.url(), ImmutableList.of(), ImmutableList.of(), 0, updated, updated)));
        when(credentials.resolveGitHubToken()).thenReturn("pat");
        when(issueClient.fetchOpenIssueSource("pat", workspace.monitoredRepository(), 7)).thenReturn(source);
        String instruction = "Implement issue selection";
        Map<String, Object> request = ImmutableMap.<String, Object>builder().putAll(createTaskBody("codex", "test-model", instruction))
                .put("issueSource", ImmutableMap.of("issueId", issueId, "number", 7, "updatedAt", updated.toString())).buildOrThrow();
        ResponseEntity<JsonNode> response = post("/agent/tasks", request, null);
        assertThat(response.getStatusCode()).as("create response: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = response.getBody();
        String taskId = created.required("id").asText();
        assertThat(created.path("title").asText()).isEqualTo(instruction);
        assertThat(created.path("titleSource").asText()).isEqualTo("PROVISIONAL");
        assertThat(created.at("/issueSource/instructions").asText()).isEqualTo(instruction);
        assertThat(created.at("/issueSource/body").asText()).isEqualTo(source.body());
        JsonNode firstMessage = StreamSupport.stream(get("/agent/tasks/" + taskId + "/events?after=0&limit=20").getBody().spliterator(), false)
                .filter(event -> event.path("type").asText().equals("user_message")).findFirst().orElseThrow();
        assertThat(firstMessage.at("/payload/text").asText()).isEqualTo(instruction);
        assertThat(created.at("/issueSource/initialTurnId").asText()).isEqualTo(firstMessage.path("turnId").asText());
        awaitCondition(() -> get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId).getBody().path("titleSource").asText().equals("AGENT"));
        assertThat(get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId).getBody().path("title").asText()).isEqualTo("Name first task");
        issueRows.delete(workspace.monitoredRepositoryId(), 7);
        JsonNode retained = get("/agent/tasks/" + taskId + "?workspaceId=" + workspaceId).getBody();
        assertThat(retained.path("issueSource")).isEqualTo(created.path("issueSource"));
        assertThat(post("/agent/tasks", request, null).getBody().path("id").asText()).isEqualTo(taskId);
        verify(issueClient).fetchOpenIssueSource("pat", workspace.monitoredRepository(), 7);
    }

    @Test
    void aTaskForWorkWithoutASourceBecomesItsAttemptAndConcludedWorkRefusesOne()
            throws Exception
    {
        String planned = workWithoutSource(WorkItem.State.PLANNED);
        ResponseEntity<JsonNode> response = post("/agent/tasks", workLaunch(planned), null);
        assertThat(response.getStatusCode()).as("create response: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(workItems.findByIdentity(WorkItemLink.taskIdentity(response.getBody().required("id").asText()))).map(WorkItem::id).contains(planned);
        assertThat(workItems.find(planned).orElseThrow().state()).isEqualTo(WorkItem.State.WORKING);

        Map<String, Object> refusedLaunch = workLaunch(workWithoutSource(WorkItem.State.DONE));
        ResponseEntity<JsonNode> refused = post("/agent/tasks", refusedLaunch, null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().path("message").asText()).contains("Reopen the work item");
        assertThat(get("/agent/tasks/" + refusedLaunch.get("taskId") + "?workspaceId=" + workspaceId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void issueLaunchRecordsOneWorkItemAndRefusesConcludedOrForeignWorkBeforeFetching()
            throws Exception
    {
        Workspace workspace = workspaces.findById(workspaceId).orElseThrow();
        when(credentials.resolveGitHubToken()).thenReturn("pat");
        GitHubIssueSource started = cachedIssue(workspace, 8);
        ResponseEntity<JsonNode> response = post("/agent/tasks", issueLaunch(started), null);
        assertThat(response.getStatusCode()).as("create response: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        WorkItem work = workItems.findByIdentity(WorkItemLink.issueIdentity("github.com", started.issueId())).orElseThrow();
        assertThat(workItems.findByIdentity(WorkItemLink.taskIdentity(response.getBody().required("id").asText()))).contains(work);
        assertThat(work.workspaceId()).isEqualTo(workspaceId);
        assertThat(work.state()).isEqualTo(WorkItem.State.WORKING);

        GitHubIssueSource dropped = cachedIssue(workspace, 9);
        saveWorkItem(workspaceId, GoalReferenceReader.issueLink(dropped, Instant.now()), WorkItem.State.DROPPED);
        Map<String, Object> refusedLaunch = issueLaunch(dropped);
        ResponseEntity<JsonNode> refused = post("/agent/tasks", refusedLaunch, null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().path("message").asText()).contains("Reopen the work item");
        assertThat(get("/agent/tasks/" + refusedLaunch.get("taskId") + "?workspaceId=" + workspaceId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        GitHubIssueSource foreign = cachedIssue(workspace, 10);
        saveWorkItem("other-workspace", GoalReferenceReader.issueLink(foreign, Instant.now()), WorkItem.State.PLANNED);
        ResponseEntity<JsonNode> elsewhere = post("/agent/tasks", issueLaunch(foreign), null);
        assertThat(elsewhere.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(elsewhere.getBody().path("message").asText()).contains("belongs to workspace other-workspace");
        // Both refusals come before the issue fetch, and so before any worktree is prepared.
        verify(issueClient, never()).fetchOpenIssueSource("pat", workspace.monitoredRepository(), 9);
        verify(issueClient, never()).fetchOpenIssueSource("pat", workspace.monitoredRepository(), 10);
    }

    @Test
    void jiraLaunchRefusesDroppedWorkBeforeRefreshingTheTicket()
            throws Exception
    {
        Instant updated = Instant.parse("2026-09-16T00:00:00Z");
        String issueId = "jira-" + UUID.randomUUID();
        JiraIssue issue = new JiraIssue("connection", "cloud", issueId, "APP-8", "https://company.atlassian.net/browse/APP-8",
                "Fix selection", "Jira body", "Story", new JiraDtos.Status("3", "In progress", "indeterminate"), Optional.empty(),
                ImmutableList.of(), Optional.empty(), Optional.empty(), false, false, updated, Optional.empty(), Optional.empty(), ImmutableList.of(), false,
                Optional.empty(), Optional.of(new JiraDtos.WorkspaceLink(workspaceId, "Workspace")));
        when(jira.issue("connection", "cloud", issueId)).thenReturn(issue);
        JiraIssueSource source = new JiraIssueSource("connection", "cloud", issueId, issue.key(), issue.url(), issue.title(), issue.body(), updated);
        saveWorkItem(workspaceId, GoalReferenceReader.jiraLink(source, updated), WorkItem.State.DROPPED);
        Map<String, Object> request = ImmutableMap.<String, Object>builder().putAll(createTaskBody("codex", "test-model", "Implement Jira selection"))
                .put("jiraSource", ImmutableMap.<String, Object>builder().put("connectionId", "connection").put("cloudId", "cloud").put("issueId", issueId)
                        .put("key", issue.key()).put("title", issue.title()).put("body", issue.body()).put("updatedAt", updated.toString()).buildOrThrow()).buildOrThrow();
        ResponseEntity<JsonNode> response = post("/agent/tasks", request, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().path("message").asText()).contains("Reopen the work item");
        verify(jira, never()).refreshIssue("connection", "cloud", issueId);
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

        pullRequests.saveRepositorySyncPage(REPOSITORY, ImmutableList.of(fetchedPullRequest(482, branch), fetchedPullRequest(483, "feature")));

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
                        fetchedPullRequest(487, branch).withOrigin(PullRequest.Origin.OTHER)));

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
        pullRequests.saveRepositorySyncPage(REPOSITORY, ImmutableList.of(fetchedPullRequest(488, null)));
        String path = "/agent/tasks/" + taskId + "/pull-requests";
        assertThat(get(path + "?workspaceId=" + workspaceId).getBody()).isEmpty();
        when(credentials.resolveGitHubCredential()).thenReturn(new GitHubCredential("pat", new UserProfile("alice", "Alice", "", "", 0, 0, 0, "", "", "", "")));
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
        RepoRef fork = RepoRef.of("contributor", "repository-copy");
        RepoRef upstream = RepoRef.of("owner", "repository");
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
        String branch = "cache-table-stats";
        run("git", "-C", worktree.canonicalWorktree().toString(), "branch", "-m", branch);
        pullRequests.saveRepositorySyncPage(upstream.fullName(), ImmutableList.of(fetchedPullRequest(upstream.fullName(), 41, branch)));
        pullRequests.saveRepositorySyncPage(fork.fullName(), ImmutableList.of(fetchedPullRequest(fork.fullName(), 123, branch)));
        String path = "/agent/tasks/" + taskId + "/pull-requests";

        JsonNode cached = get(path + "?workspaceId=" + workspaceId).getBody();
        assertThat(cached).hasSize(1);
        assertThat(cached.get(0).get("repo").asText()).isEqualTo("owner/repository");
        assertThat(cached.get(0).get("number").asInt()).isEqualTo(41);
        when(credentials.resolveGitHubCredential()).thenReturn(new GitHubCredential("pat", new UserProfile("contributor", "Sam", "", "", 0, 0, 0, "", "", "", "")));
        // The user's own fork branch: its pull request is theirs, which a renamed-branch match requires.
        when(gitHub.fetchPullRequestsByHead("pat", upstream, "contributor:" + branch))
                .thenReturn(ImmutableList.of(fetchedPullRequest(upstream.fullName(), 42, branch, "contributor")));

        JsonNode refreshed = post(path + "/sync?workspaceId=" + workspaceId, ImmutableMap.of(), null).getBody();

        assertThat(refreshed).hasSize(2);
        assertThat(refreshed.get(0).get("repo").asText()).isEqualTo("owner/repository");
        assertThat(refreshed.get(0).get("number").asInt()).isEqualTo(42);
        verify(gitHub).fetchPullRequestsByHead("pat", upstream, "contributor:" + branch);
        verify(gitHub).fetchPullRequestsByHead("pat", upstream, "contributor:" + worktree.generatedBranch());
        verify(gitHub, never()).fetchPullRequestsByHead("pat", fork, "contributor:" + branch);
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
        ResponseEntity<JsonNode> invalid = post("/agent/tasks", ImmutableMap.of("workspaceId", "", "laneId", "codex", "message", "Invalid"), null);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> unsupported = post("/agent/tasks", createTaskBody("missing-lane"), null);
        assertThat(unsupported.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        ResponseEntity<JsonNode> unknownModel = post("/agent/tasks", createTaskBody("codex", "unknown-model", "Unknown model"), null);
        assertThat(unknownModel.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/agent/tasks?workspaceId=" + workspaceId).getBody()).isEmpty();
        assertThat(workspacePaths.worktrees(workspaceId)).doesNotExist();
    }

    @Test
    void rejectsTaskCreationWhenRepositorySynchronizationFails()
            throws Exception
    {
        when(provisioning.synchronizeCode(workspaceId)).thenReturn(new WorkspaceCodeSyncResult(workspaceId, false, Optional.of(Instant.now()), ImmutableSet.of()));

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
                        "developmentAutomation", ImmutableMap.of("level", "DRAFT_PR")));
        HttpHeaders updateHeaders = headers();
        updateHeaders.setContentType(MediaType.APPLICATION_JSON);
        for (Map<String, Object> defaults : unsupported) {
            ResponseEntity<JsonNode> refused = rest.exchange(
                    url("/agent/tasks/" + taskId + "/defaults?workspaceId=" + workspaceId),
                    HttpMethod.PUT,
                    new HttpEntity<>(objectMapper.writeValueAsString(ImmutableMap.of("expectedVersion", taskVersion(taskId), "defaults", defaults)), updateHeaders),
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
                        "attachments", ImmutableList.of(ImmutableMap.of("name", "screen.png", "mediaType", "image/png", "path", "/tmp/screen.png"))),
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

        ResponseEntity<JsonNode> stale = post("/agent/tasks/" + taskId + "/turns", ImmutableMap.of("message", "stale", "expectedVersion", observedVersion), "stale-defaults-start");

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

        ResponseEntity<JsonNode> wrongWorkspace = post("/agent/tasks/" + interactiveTaskId + "/complete?workspaceId=another-workspace", ImmutableMap.of(), null);
        assertThat(wrongWorkspace.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/agent/tasks/" + interactiveTaskId + "/cleanup?workspaceId=another-workspace", ImmutableMap.of(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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
        ResponseEntity<JsonNode> nonInteractive = post("/agent/tasks/" + workflowTaskId + "/complete?workspaceId=" + workspaceId, ImmutableMap.of(), null);
        assertThat(nonInteractive.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/agent/tasks/" + workflowTaskId + "/cleanup?workspaceId=" + workspaceId, ImmutableMap.of(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(tasks.get(workflowTaskId).state().name()).isEqualTo("OPEN");
        assertThat(workflowTask.taskWorktree().orElseThrow().canonicalWorktree()).exists();
        assertThat(get("/agent/tasks/" + workflowTaskId + "?workspaceId=" + workspaceId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/agent/tasks/" + workflowTaskId + "/turns",
                ImmutableMap.of("message", "Change the assignment", "expectedVersion", workflowTask.version()), "workflow-input")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        driver.holdNextTurn();
        var workflowTurn = turns.start(new StartAgentTurnCommand(workflowTaskId, "Research", ImmutableList.of(), "workflow-owned-turn"));
        try {
            assertThat(post("/agent/turns/" + workflowTurn.id() + "/steer", ImmutableMap.of("message", "Change the assignment"), "workflow-steer")
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        finally {
            driver.completeHeldTurn();
        }
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

        ResponseEntity<JsonNode> failed = post("/agent/tasks/" + cleanTaskId + "/mark-failed?workspaceId=" + workspaceId, ImmutableMap.of("reason", "giving up"), null);

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

        JsonNode retained = post("/agent/tasks/" + dirtyTaskId + "/mark-failed?workspaceId=" + workspaceId, ImmutableMap.of("reason", "giving up"), null).getBody();

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
        // Build output is no work of anyone's, so it goes with the checkout.
        assertThat(completed.get("checkoutRemovedAt").isNull()).isFalse();
        assertThat(worktree).doesNotExist();
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

        ResponseEntity<JsonNode> stopped = post(stopPath, ImmutableMap.of("generation", afterTurn.get("generation").asText()), null);

        assertThat(stopped.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stopped.getBody().get("state").asText()).isEqualTo("OPEN");
        assertThat(stopped.getBody().get("mutationClaim").isNull()).isTrue();
        assertThat(get(impactPath).getBody().get("liveProcess").asBoolean()).isFalse();
    }

    /** Caches open issue {@code number} in the workspace and serves it as the fresh GitHub read. */
    private GitHubIssueSource cachedIssue(Workspace workspace, int number)
    {
        Instant updated = Instant.parse("2026-09-16T00:00:00Z");
        long issueId = workspace.monitoredRepositoryId() * 1000 + number;
        String repo = workspace.monitoredRepository().fullName();
        GitHubIssueSource source = new GitHubIssueSource("github.com", issueId, repo, number, "https://github.com/" + repo + "/issues/" + number,
                "Fix issue " + number, "Issue body", updated);
        issueRows.saveSyncPage(workspace.monitoredRepositoryId(), ImmutableList.of(new RepositoryIssue(issueId, number, source.title(), source.body(),
                "alice", "", source.url(), ImmutableList.of(), ImmutableList.of(), 0, updated, updated)));
        when(issueClient.fetchOpenIssueSource("pat", workspace.monitoredRepository(), number)).thenReturn(source);
        return source;
    }

    private Map<String, Object> issueLaunch(GitHubIssueSource source)
    {
        return ImmutableMap.<String, Object>builder().putAll(createTaskBody("codex", "test-model", "Implement " + source.title()))
                .put("issueSource", ImmutableMap.of("issueId", source.issueId(), "number", source.number(), "updatedAt", source.updatedAt().toString())).buildOrThrow();
    }

    private Map<String, Object> workLaunch(String workItemId)
    {
        return ImmutableMap.<String, Object>builder().putAll(createTaskBody("codex", "test-model", "Profile the reader")).put("workItemId", workItemId).buildOrThrow();
    }

    private String workWithoutSource(WorkItem.State state)
    {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        workItems.insert(new WorkItem(id, workspaceId, "Profile the reader", WorkItem.Kind.DEVELOP, "", WorkItem.CompletionRule.MERGED_DELIVERY, state, 0,
                ImmutableList.of(), ImmutableList.of(new WorkItemEvent("planned", "", "CREATED", "Planned", now)), now, now, WorkItem.Priority.NORMAL, ImmutableList.of(),
                Optional.empty(), 0, ImmutableList.of()));
        return id;
    }

    private void saveWorkItem(String ownerWorkspaceId, WorkItemLink source, WorkItem.State state)
    {
        Instant now = Instant.now();
        WorkItemEvent planned = new WorkItemEvent("planned", "", "CREATED", "Planned", now);
        workItems.insert(new WorkItem(UUID.randomUUID().toString(), ownerWorkspaceId, source.reference().title(), WorkItem.Kind.DEVELOP, "",
                WorkItem.CompletionRule.MERGED_DELIVERY, state, 0, ImmutableList.of(source), ImmutableList.of(planned), now, now, WorkItem.Priority.NORMAL,
                ImmutableList.of(), Optional.empty(), 0, ImmutableList.of()));
    }

    private ResponseEntity<JsonNode> patch(String path, Object body)
            throws Exception
    {
        HttpHeaders requestHeaders = headers();
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), HttpMethod.PATCH, new HttpEntity<>(objectMapper.writeValueAsString(body), requestHeaders), JsonNode.class);
    }
}
