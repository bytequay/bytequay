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
package com.bytequay.app.workitem;

import com.bytequay.app.jira.JiraService;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.goal.WorkItemService;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemEvent;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestWorkItemPublicationService
{
    private final WorkItemService work = mock(WorkItemService.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final GitHubRepoClient github = mock(GitHubRepoClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final JiraService jira = mock(JiraService.class);
    private final WorkItemPublicationService service = new WorkItemPublicationService(work, workspaces, github, credentials, jira, Clock.systemUTC());
    private final WorkItemPublicationService.Publication request = new WorkItemPublicationService.Publication(UnifiedWorkItem.Source.GITHUB, 0,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    private final RepoRef repo = RepoRef.of("org", "repo");

    @BeforeEach
    void setUp()
    {
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.monitoredRepository()).thenReturn(repo);
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace));
        when(credentials.resolveGitHubToken("workspace")).thenReturn("token");
        when(work.requireItem("workspace", "local")).thenReturn(item());
        when(work.beginPublication(eq("workspace"), eq("local"), eq(0L), anyString(), eq("GITHUB org/repo"))).thenReturn(item());
    }

    @Test
    void aPublicationClaimsThePreviewBeforeSendingOnlyItsTitleAndBodyAndAttachesTheReturnedIdentity()
    {
        when(github.publishWorkItem("token", repo, "Private repair", "Full accepted context")).thenReturn(source());
        service.publish("workspace", "local", request);
        var order = inOrder(work, github);
        order.verify(work).beginPublication(eq("workspace"), eq("local"), eq(0L), anyString(), eq("GITHUB org/repo"));
        order.verify(github).publishWorkItem("token", repo, "Private repair", "Full accepted context");
        order.verify(work).published(eq("workspace"), eq("local"), argThat(link -> link.issueSource().orElseThrow().issueId() == 42), anyString());
        verifyNoInteractions(jira);
    }

    @Test
    void aStalePreviewNeverCreatesARemoteIssue()
    {
        when(work.beginPublication(anyString(), anyString(), anyLong(), anyString(), anyString())).thenThrow(new IllegalArgumentException("Work changed"));
        assertThatThrownBy(() -> service.publish("workspace", "local", request)).hasMessage("Work changed");
        verifyNoInteractions(github, jira);
    }

    @Test
    void anUncertainResponseKeepsItsPendingClaimButADefiniteRejectionSettlesIt()
    {
        when(github.publishWorkItem(anyString(), any(), anyString(), anyString())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> service.publish("workspace", "local", request)).isInstanceOf(ResponseStatusException.class);
        verify(work, never()).publicationRejected(anyString(), anyString(), anyString());
        verify(work, never()).published(anyString(), anyString(), any(), anyString());
        doThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY)).when(github).publishWorkItem(anyString(), any(), anyString(), anyString());
        assertThatThrownBy(() -> service.publish("workspace", "local", request)).isInstanceOf(ResponseStatusException.class);
        verify(work).publicationRejected(eq("workspace"), eq("local"), anyString());
    }

    @Test
    void recoveryMustMatchThePendingDestinationAndCanLinkAnAlreadyClosedIssueWithoutCreatingAgain()
    {
        when(work.requireItem("workspace", "local")).thenReturn(item(new WorkItemEvent("attempt", "", "PUBLISH_STARTED", "GITHUB org/repo", Instant.EPOCH)));
        var wrong = new WorkItemPublicationService.Publication(UnifiedWorkItem.Source.JIRA, 0,
                Optional.of("connection"), Optional.of("site"), Optional.of("ENG"), Optional.empty());
        assertThatThrownBy(() -> service.reconcile("workspace", "local", wrong, "ENG-42")).hasMessageContaining("same destination");
        verifyNoInteractions(github, jira);
        when(github.fetchIssueSource("token", repo, 9)).thenReturn(source());
        service.reconcile("workspace", "local", request, "#9");
        verify(work).published(eq("workspace"), eq("local"), argThat(link -> link.issueSource().orElseThrow().issueId() == 42), eq("attempt"));
        verify(github, never()).publishWorkItem(anyString(), any(), anyString(), anyString());
    }

    private static WorkItem item(WorkItemEvent... history)
    {
        return new WorkItem("local", "workspace", Optional.empty(), "Private repair", WorkItem.Kind.DEVELOP, "Full accepted context",
                WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.copyOf(history), Instant.EPOCH, Instant.EPOCH,
                WorkItem.Priority.NORMAL, 0, ImmutableList.of(), Optional.empty(), 0);
    }

    private static GitHubIssueSource source()
    {
        return new GitHubIssueSource("github.com", 42, "org/repo", 9, "https://github.com/org/repo/issues/9", "Private repair", "Full accepted context", Instant.EPOCH);
    }
}
