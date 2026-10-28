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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.IssueContextBridge;
import com.bytequay.app.service.ai.TextOnlyHelper;
import com.bytequay.app.service.hosting.HostingConnectionService;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.app.workspace.PublicationContent;
import com.bytequay.app.workspace.WorkspaceHostingRead;
import com.bytequay.app.workspace.WorkspaceHostingService;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.domain.JiraIssueTask;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.RepositoryVisibility;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestPublicationPrivacyService
{
    private final TaskId id = TaskId.random();
    private final RepoRef repository = RepoRef.of("owner", "repo");
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final IssueContextBridgeResolverImpl contexts = mock(IssueContextBridgeResolverImpl.class);
    private final IssueDevelopmentStore issues = mock(IssueDevelopmentStore.class);
    private final JiraIssueTaskStore jira = mock(JiraIssueTaskStore.class);
    private final WorkspaceHostingService hosting = mock(WorkspaceHostingService.class);
    private final HostingConnectionService connections = mock(HostingConnectionService.class);
    private final TextOnlyHelper reviewer = mock(TextOnlyHelper.class);
    private final PublicationPrivacyService privacy = new PublicationPrivacyService(tasks, contexts, issues, jira, hosting, connections,
            mock(PublicationContent.class), reviewer, new ObjectMapper());
    private final AtomicReference<String> source = new AtomicReference<>("Private customer Example Customer; internal stack: com.example.privateproduct.Trace");

    @BeforeEach
    void setup()
    {
        AgentTask task = mock(AgentTask.class, withSettings().mockMaker(MockMakers.INLINE));
        when(tasks.require(id)).thenReturn(task);
        when(task.workspaceId()).thenReturn(Optional.of("workspace"));
        when(task.laneId()).thenReturn(AgentLaneId.CODEX_CLI);
        when(task.defaults()).thenReturn(AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO));
        when(contexts.resolve(id.toString())).thenReturn(Optional.of(new IssueContextBridge(source::get, "Read source")));
        when(hosting.captureRead("workspace")).thenReturn(HostingTestFixture.route("owner/repo", "token"));
        when(connections.visibility(any(), anyString())).thenReturn(RepositoryVisibility.PUBLIC);
        when(reviewer.answer(any(), any(), any(), anyString(), anyString(), anyString())).thenReturn("SAFE");
        var ticket = new JiraIssueSource("connection", "cloud", "42", "CASE-42", "https://issues.example.invalid/browse/CASE-42", "Fix example", "Private detail", Instant.EPOCH);
        when(jira.findByTaskId(id.toString())).thenReturn(Optional.of(new JiraIssueTask(id.toString(), "workspace", ticket, "Fix the example", Instant.EPOCH)));
    }

    @Test
    void blocksKnownPrivateLinksEvenWhenModelWouldPass()
    {
        assertThatThrownBy(() -> privacy.checkText(id, repository, "Fixes CASE-42")).hasMessageContaining("Privacy check blocked");
        verify(reviewer, never()).answer(any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void internalReferencesAreAllowedOnlyInTheSelectedPrivateRepository()
    {
        when(connections.visibility(any(), anyString())).thenReturn(RepositoryVisibility.PRIVATE);
        privacy.checkText(id, repository, "Fixes CASE-42");
        assertThatThrownBy(() -> privacy.checkText(id, RepoRef.of("other", "repo"), "Fixes CASE-42")).hasMessageContaining("Privacy check blocked");
    }

    @Test
    void anotherHostCannotGrantAnInternalExemptionToGitHubWriters()
    {
        var original = HostingTestFixture.route(repository.fullName(), "token");
        var instance = new HostingInstance(1, HostingProvider.GITLAB, URI.create("https://git.example.invalid"), URI.create("https://git.example.invalid/api/v4"));
        var session = new HostingReadSession(2, "selected", new HostingAccess(instance, "token"));
        when(hosting.captureRead("workspace")).thenReturn(new WorkspaceHostingRead(session, original.repository(), original.login()));
        when(connections.visibility(any(), anyString())).thenReturn(RepositoryVisibility.PRIVATE);
        assertThatThrownBy(() -> privacy.checkText(id, repository, "Fixes CASE-42")).hasMessageContaining("destination");
        verify(reviewer, never()).answer(any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void unknownDestinationAndInvalidOrUnavailableVerdictCannotPass()
    {
        when(connections.visibility(any(), anyString())).thenReturn(RepositoryVisibility.UNKNOWN);
        assertThatThrownBy(() -> privacy.checkText(id, repository, "Generic change")).hasMessageContaining("visibility");
        when(connections.visibility(any(), anyString())).thenReturn(RepositoryVisibility.PUBLIC);
        when(reviewer.answer(any(), any(), any(), anyString(), anyString(), anyString())).thenReturn("SAFE but incomplete");
        assertThatThrownBy(() -> privacy.checkText(id, repository, "Generic change")).hasMessageContaining("invalid verdict");
        when(reviewer.answer(any(), any(), any(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("Reviewer unavailable"));
        assertThatThrownBy(() -> privacy.checkText(id, repository, "Generic change")).hasMessageContaining("Reviewer unavailable");
    }

    @Test
    void privateOrUnavailableIssueVisibilityCannotBeTreatedAsPublic()
    {
        when(jira.findByTaskId(id.toString())).thenReturn(Optional.empty());
        var issue = new GitHubIssueSource("github.com", 42, "example/private-source", 7,
                "https://github.com/example/private-source/issues/7", "Example issue", "Private detail", Instant.EPOCH);
        when(issues.findByTaskId(id.toString())).thenReturn(Optional.of(new IssueTask(id.toString(), "workspace", issue, Optional.empty(), Instant.EPOCH)));
        when(connections.visibility(any(), eq(issue.repo()))).thenReturn(RepositoryVisibility.PRIVATE);
        assertThatThrownBy(() -> privacy.checkText(id, repository, issue.url())).hasMessageContaining("Privacy check blocked");
        when(connections.visibility(any(), eq(issue.repo()))).thenThrow(new IllegalStateException("Unavailable"));
        assertThatThrownBy(() -> privacy.checkText(id, repository, issue.repo())).hasMessageContaining("Privacy check blocked");
        doReturn(RepositoryVisibility.PUBLIC).when(connections).visibility(any(), eq(issue.repo()));
        privacy.checkText(id, repository, issue.url());
        var otherHost = new GitHubIssueSource("git.example.invalid", 42, issue.repo(), 7,
                "https://git.example.invalid/example/private-source/issues/7", issue.title(), issue.body(), Instant.EPOCH);
        when(issues.findByTaskId(id.toString())).thenReturn(Optional.of(new IssueTask(id.toString(), "workspace", otherHost, Optional.empty(), Instant.EPOCH)));
        assertThatThrownBy(() -> privacy.checkText(id, repository, otherHost.repo())).hasMessageContaining("Privacy check blocked");
    }

    @Test
    void changedSourceOrVisibilityInvalidatesTheReview()
    {
        when(reviewer.answer(any(), any(), any(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            source.set("New private context");
            return "SAFE";
        });
        assertThatThrownBy(() -> privacy.checkText(id, repository, "Generic change")).hasMessageContaining("inputs changed");
        when(reviewer.answer(any(), any(), any(), anyString(), anyString(), anyString())).thenReturn("SAFE");
        when(connections.visibility(any(), anyString())).thenReturn(RepositoryVisibility.PRIVATE, RepositoryVisibility.PUBLIC);
        assertThatThrownBy(() -> privacy.checkText(id, repository, "Generic change")).hasMessageContaining("inputs changed");
    }

    @Test
    void passingEarlierTextDoesNotAuthorizeLaterTextOrInternalCredentials()
    {
        privacy.checkText(id, repository, "Generic change");
        when(reviewer.answer(any(), any(), any(), anyString(), anyString(), anyString())).thenReturn("BLOCKED");
        assertThatThrownBy(() -> privacy.checkText(id, repository, "Example Customer's proprietary stack")).hasMessageContaining("blocked");
        when(connections.visibility(any(), anyString())).thenReturn(RepositoryVisibility.PRIVATE);
        assertThatThrownBy(() -> privacy.checkText(id, repository, "password=example-secret")).hasMessageContaining("blocked");
    }
}
