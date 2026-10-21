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
package com.bytequay.app.jira;

import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.workitem.WorkItemService;
import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.domain.JiraIssueTask;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraClient;
import com.bytequay.integration.jira.JiraDtos;
import com.bytequay.integration.jira.JiraIssue;
import com.bytequay.integration.jira.impl.DefaultJiraConnections;
import com.bytequay.integration.jira.impl.JiraActivityLog;
import com.bytequay.integration.jira.impl.JiraCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestJiraService
{
    private static final Instant UPDATED = Instant.parse("2026-09-26T01:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DefaultJiraConnections remote = mock(DefaultJiraConnections.class);
    private final AppSettingsStore settings = mock(AppSettingsStore.class);
    private final JiraIssueTaskStore tasks = mock(JiraIssueTaskStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final AppSettingsService appSettings = mock(AppSettingsService.class);
    private final ObjectProvider<AppSettingsService> settingsProvider = mock(ObjectProvider.class);
    private final JiraCache cache = new JiraCache(settings, mapper);
    private final RecentTouchRecorder recent = mock(RecentTouchRecorder.class);
    private final WorkItemStore workItems = mock(WorkItemStore.class);
    private final WorkItemService workService = mock(WorkItemService.class);
    private final ObjectProvider<WorkItemService> work = mock(ObjectProvider.class);
    private final JiraIssueContextImpl context = new JiraIssueContextImpl(new JiraCache(settings, mapper), tasks, workspaces, workItems, recent);
    private final JiraClient client = new JiraIntegrationConfiguration().jiraClient(remote, cache, new JiraActivityLog(cache), context);
    private final JiraService jira = new JiraService(client, context, tasks, workspaces, settingsProvider, workItems, work);
    private final Map<String, String> saved = new ConcurrentHashMap<>();

    @AfterEach
    void stopReads()
    {
        client.close();
    }

    @BeforeEach
    void setup()
            throws Exception
    {
        when(settingsProvider.getObject()).thenReturn(appSettings);
        when(work.getObject()).thenReturn(workService);
        when(settings.get(anyString())).thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(0))));
        doAnswer(call -> {
            saved.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(settings).put(anyString(), anyString());
        when(tasks.findBySource(anyString(), anyString())).thenReturn(Optional.empty());
        when(remote.site("connection", "cloud")).thenReturn(new JiraDtos.Site("cloud", "Example", "https://example.atlassian.net"));
        when(remote.get("connection")).thenReturn(new JiraDtos.Connection("connection", "connected", ImmutableList.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new JiraDtos.Account("me", "Me", Optional.empty())), Optional.empty(), "api_token", Optional.of("me@example.com")));
        when(remote.request(eq("connection"), eq("cloud"), anyString(), eq("GET"), any())).thenAnswer(call -> {
            String path = call.getArgument(2);
            if (path.startsWith("/rest/api/3/issue/1001?")) {
                return ticket();
            }
            if (path.startsWith("/rest/api/3/search/jql")) {
                return json("{\"issues\":[]}");
            }
            if (path.contains("/comment?")) {
                return json("{\"comments\":[],\"total\":0}");
            }
            if (path.contains("/transitions")) {
                return json("{\"transitions\":[]}");
            }
            if (path.endsWith("/editmeta")) {
                return json("{\"fields\":{\"assignee\":{\"schema\":{\"type\":\"user\"},\"allowedValues\":[{\"accountId\":\"alice\",\"displayName\":\"Alice\"}]}}}");
            }
            if (path.contains("/user/assignable")) {
                return json("[]");
            }
            throw new AssertionError("Unexpected request " + path);
        });
    }

    @Test
    void workspaceAssignmentSurvivesRefreshAndIsSharedAcrossConnections()
            throws Exception
    {
        JiraIssue issue = ticketIssue("connection");
        cache.write("connection.cloud.issue.1001", issue);
        cache.write("other.cloud.issue.1001", ticketIssue("other"));
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace("workspace", "ByteQuay")));
        assertThatThrownBy(() -> jira.epic("connection", "cloud", "1001", true)).hasMessageContaining("Assign a ByteQuay workspace");
        assertThatThrownBy(() -> jira.assignWorkspace("connection", "cloud", "1001", "missing")).hasMessageContaining("missing");
        assertThat(jira.assignWorkspace("connection", "cloud", "1001", "workspace").issue().workspace())
                .contains(new JiraDtos.WorkspaceLink("workspace", "ByteQuay"));
        assertThat(jira.epic("connection", "cloud", "1001", true).issue().isEpic()).isTrue();
        assertThat(jira.assignedWorkspace("cloud", "1001")).contains("workspace");
        verifyNoInteractions(workService);
        assertThat(jira.epic("connection", "cloud", "1001", false).issue().isEpic()).isFalse();
        verify(workService).releaseEpic("workspace", "JIRA:5:cloud:1001");
        verifyNoInteractions(remote);
        assertThat(jira.refreshIssue("connection", "cloud", "1001").workspace()).contains(new JiraDtos.WorkspaceLink("workspace", "ByteQuay"));
        JiraCache reopenedCache = new JiraCache(settings, mapper);
        JiraIssueContextImpl reopenedContext = new JiraIssueContextImpl(new JiraCache(settings, mapper), tasks, workspaces, workItems, recent);
        try (JiraClient reopenedClient = new JiraIntegrationConfiguration().jiraClient(remote, reopenedCache, new JiraActivityLog(reopenedCache), reopenedContext)) {
            JiraService reopened = new JiraService(reopenedClient, reopenedContext, tasks, workspaces, settingsProvider, workItems, work);
            assertThat(reopened.issue("other", "cloud", "1001").workspace()).contains(new JiraDtos.WorkspaceLink("workspace", "ByteQuay"));
            when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace("workspace", "Renamed")));
            assertThat(reopened.issue("other", "cloud", "1001").workspace()).contains(new JiraDtos.WorkspaceLink("workspace", "Renamed"));
            when(workspaces.findById("workspace")).thenReturn(Optional.empty());
            assertThat(reopened.issue("other", "cloud", "1001").workspace()).isEmpty();
        }
    }

    @Test
    void linkedTaskOwnsWorkspaceAndCannotBeReassigned()
            throws Exception
    {
        JiraIssue issue = ticketIssue("connection");
        cache.write("connection.cloud.issue.1001", issue);
        cache.write("workspace.cloud.1001", "old-workspace");
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace("workspace", "ByteQuay")));
        when(workspaces.findById("other-workspace")).thenReturn(Optional.of(workspace("other-workspace", "Other")));
        JiraIssueSource source = new JiraIssueSource("connection", "cloud", "1001", issue.key(), issue.url(), issue.title(), issue.body(), issue.updatedAt());
        when(tasks.findBySource("cloud", "1001")).thenReturn(Optional.of(new JiraIssueTask("task", "workspace", source, "Implement", UPDATED)));
        assertThat(jira.issue("connection", "cloud", "1001").workspace()).contains(new JiraDtos.WorkspaceLink("workspace", "ByteQuay"));
        assertThatThrownBy(() -> jira.assignWorkspace("connection", "cloud", "1001", "other-workspace")).hasMessageContaining("another workspace");
        assertThat(jira.assignWorkspace("connection", "cloud", "1001", "workspace").issue().task()).contains(new JiraDtos.TaskLink("task", "workspace"));
        verifyNoInteractions(remote);
    }

    @Test
    void workItemOwnsWorkspaceSoTheTicketStaysStartable()
            throws Exception
    {
        JiraIssue issue = ticketIssue("connection");
        cache.write("connection.cloud.issue.1001", issue);
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace("workspace", "ByteQuay")));
        when(workspaces.findById("other-workspace")).thenReturn(Optional.of(workspace("other-workspace", "Other")));
        WorkItem owner = new WorkItem("work", "workspace", issue.title(), WorkItem.Kind.DEVELOP, "", WorkItem.CompletionRule.MERGED_DELIVERY,
                WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.of(), UPDATED, UPDATED, WorkItem.Priority.NORMAL, ImmutableList.of(), Optional.empty(), 0,
                ImmutableList.of());
        when(workItems.findByIdentity(WorkItemLink.jiraIdentity("cloud", "1001"))).thenReturn(Optional.of(owner));
        assertThat(jira.assignedWorkspace("cloud", "1001")).contains("workspace");
        // Publication already binds the workspace; no separate ticket assignment is needed.
        assertThatThrownBy(() -> jira.assignWorkspace("connection", "cloud", "1001", "other-workspace")).hasMessageContaining("work item belongs to another workspace");
        assertThat(jira.issue("connection", "cloud", "1001").workspace()).contains(new JiraDtos.WorkspaceLink("workspace", "ByteQuay"));
        assertThat(jira.assignWorkspace("connection", "cloud", "1001", "workspace").issue().workspace()).contains(new JiraDtos.WorkspaceLink("workspace", "ByteQuay"));
        verifyNoInteractions(remote);
    }

    @Test
    void scheduledPollingPreservesCacheDuringQuietHoursAndResumesAfterward()
            throws Exception
    {
        saved.put("jira.scopes", """
                [{"connectionId":"connection","cloudId":"cloud","boardId":82,"sprintId":9,"mine":true,"completed":false}]
                """);
        Map<String, String> before = ImmutableMap.copyOf(saved);
        when(appSettings.inQuietHours()).thenReturn(true);
        jira.poll();
        verifyNoInteractions(remote);
        verify(settings, never()).put(anyString(), anyString());
        assertThat(saved).isEqualTo(before);

        when(appSettings.inQuietHours()).thenReturn(false);
        when(remote.list()).thenReturn(ImmutableList.of(new JiraDtos.Connection("connection", "connected",
                ImmutableList.of(new JiraDtos.Site("cloud", "Example", "https://example.atlassian.net")), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), "oauth", Optional.empty())));
        doReturn(json("{\"values\":[],\"isLast\":true}")).when(remote)
                .request(eq("connection"), eq("cloud"), startsWith("/rest/agile/"), eq("GET"), any());
        doReturn(json("{\"issues\":[" + ticket() + "],\"isLast\":true}")).when(remote)
                .request(eq("connection"), eq("cloud"), startsWith("/rest/software/"), eq("GET"), any());
        jira.poll();
        assertThat(jira.issues("connection", "cloud", 82, Optional.of(9L), true, false, false, false, Optional.empty()).issues()).extracting(JiraIssue::id).containsExactly("1001");
        verify(remote).request(eq("connection"), eq("cloud"), startsWith("/rest/software/"), eq("GET"), any());
    }

    @Test
    void quietHoursKeepAutomaticDetailCachedButAllowManualRefresh()
            throws Exception
    {
        JiraIssue cached = ticketIssue("connection");
        saved.put("jira.connection.cloud.issue.1001", mapper.writeValueAsString(cached));
        Map<String, String> before = ImmutableMap.copyOf(saved);
        when(appSettings.inQuietHours()).thenReturn(true);
        assertThat(jira.refreshDetail("connection", "cloud", "1001", true).issue()).isEqualTo(cached);
        verifyNoInteractions(remote);
        verify(settings, never()).put(anyString(), anyString());
        assertThat(saved).isEqualTo(before);

        assertThat(jira.refreshDetail("connection", "cloud", "1001", false).fetchedAt()).isPresent();
        verify(remote).request(eq("connection"), eq("cloud"), startsWith("/rest/api/3/issue/1001?"), eq("GET"), any());
    }

    @Test
    void automaticDetailRefreshResumesOutsideQuietHours()
    {
        when(appSettings.inQuietHours()).thenReturn(false);
        assertThat(jira.refreshDetail("connection", "cloud", "1001", true).fetchedAt()).isPresent();
        verify(remote).request(eq("connection"), eq("cloud"), startsWith("/rest/api/3/issue/1001?"), eq("GET"), any());
    }

    @Test
    void childrenAreLocalAndPublicationIsSeparate()
            throws Exception
    {
        jira.detail("connection", "cloud", "1001", true);
        cache.write("workspace.cloud.1001", "workspace");
        cache.write("goal.cloud.1001", true);
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace("workspace", "Workspace")));
        jira.createLocalChild("connection", "cloud", "1001", "child-id", "Verify the flag", "Preserve the parent context");
        verify(workService).createInEpic("workspace", "child-id", WorkItemLink.jiraIdentity("cloud", "1001"), "Verify the flag",
                WorkItem.Kind.DEVELOP, "Preserve the parent context", WorkItem.CompletionRule.MERGED_DELIVERY);
        verify(remote, never()).request(anyString(), anyString(), eq("/rest/api/3/issue"), eq("POST"), any());

        doReturn(json("{\"issueTypes\":[{\"id\":\"3\",\"name\":\"Task\"},{\"id\":\"4\",\"name\":\"Subtask\",\"subtask\":true}],\"isLast\":true}"))
                .when(remote).request(eq("connection"), eq("cloud"), startsWith("/rest/api/3/issue/createmeta/ENG/issuetypes?"), eq("GET"), any());
        assertThat(jira.publicationTypes("connection", "cloud", "ENG")).containsExactly(new JiraDtos.Choice("3", "Task"));
        doReturn(json("{\"id\":\"1002\",\"key\":\"ENG-2\"}")).when(remote).request(eq("connection"), eq("cloud"), eq("/rest/api/3/issue"), eq("POST"), any());
        JiraIssueSource published = jira.publishWorkItem("connection", "cloud", "ENG", "3", "Verify the flag", "Full local description");
        assertThat(published.body()).isEqualTo("Full local description");
        assertThat(published.url()).isEqualTo("https://example.atlassian.net/browse/ENG-2");
    }

    @Test
    void recordsTicketReadsFromCacheWithoutFetchingJira()
            throws Exception
    {
        JiraIssue cached = ticketIssue("connection");
        saved.put("jira.connection.cloud.issue.1001", mapper.writeValueAsString(cached));
        jira.recordRead("connection", "cloud", "1001", 65);
        verify(recent).jiraRead(cached, 65);
        verifyNoInteractions(remote);
    }

    private static JiraIssue ticketIssue(String connectionId)
    {
        return new JiraIssue(connectionId, "cloud", "1001", "ENG-7", "https://example.atlassian.net/browse/ENG-7", "A ticket", "", "Story",
                new JiraDtos.Status("1", "To do", "new"), Optional.empty(), ImmutableList.of(), Optional.empty(), Optional.empty(), false, false,
                UPDATED, Optional.empty(), Optional.empty(), ImmutableList.of(), false, Optional.empty(), Optional.empty());
    }

    private JsonNode ticket()
            throws Exception
    {
        return json("""
                {"id":"1001","key":"ENG-7","fields":{"summary":"A ticket","description":{"type":"doc","content":[]},
                "updated":"2026-09-26T01:00:00.000+0000","issuetype":{"name":"Story","hierarchyLevel":0},"subtasks":[],"labels":[],
                "status":{"id":"1","name":"To do","statusCategory":{"key":"new"}}}}
                """);
    }

    private static Workspace workspace(String id, String name)
    {
        RepoRef repository = new RepoRef("bytequay", "bytequay");
        return new Workspace(id, name, 1, repository, Optional.empty(), Optional.empty(), 1, repository, Workspace.Topology.DIRECT,
                "main", "https://github.com/bytequay/bytequay.git", Optional.empty(), Optional.empty(), "/tmp/bytequay", Optional.empty(), Optional.empty(), 0, UPDATED, UPDATED);
    }

    private JsonNode json(String value)
            throws Exception
    {
        return mapper.readTree(value);
    }
}
