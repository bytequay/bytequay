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

import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.domain.JiraIssueTask;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final JiraConnections remote = mock(JiraConnections.class);
    private final AppSettingsStore settings = mock(AppSettingsStore.class);
    private final JiraIssueTaskStore tasks = mock(JiraIssueTaskStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final AppSettingsService appSettings = mock(AppSettingsService.class);
    private final ObjectProvider<AppSettingsService> settingsProvider = mock(ObjectProvider.class);
    private final JiraCache cache = new JiraCache(settings, mapper);
    private final JiraService jira = new JiraService(remote, cache, tasks, workspaces, settingsProvider);
    private final Map<String, String> saved = new HashMap<>();

    @BeforeEach
    void setup()
            throws Exception
    {
        when(settingsProvider.getObject()).thenReturn(appSettings);
        when(settings.get(anyString())).thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(0))));
        doAnswer(call -> {
            saved.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(settings).put(anyString(), anyString());
        when(tasks.findBySource(anyString(), anyString())).thenReturn(Optional.empty());
        when(remote.site("connection", "cloud")).thenReturn(new JiraDtos.Site("cloud", "Example", "https://example.atlassian.net"));
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
    void cacheReadsNeverFetchCredentialsOrRemoteData()
            throws Exception
    {
        JiraIssue issue = JiraMapping.issue("connection", "cloud", "https://example.atlassian.net", ticket());
        cache.write("connection.cloud.issue.1001", issue);
        assertThat(jira.issue("connection", "cloud", "1001").title()).isEqualTo("A ticket");
        verifyNoInteractions(remote, settingsProvider);
    }

    @Test
    void ticketLinkKeyResolvesToTheIssueIdItIsCachedUnder()
            throws Exception
    {
        doReturn(ticket()).when(remote).request(eq("connection"), eq("cloud"), startsWith("/rest/api/3/issue/ENG-7?"), eq("GET"), any());
        assertThat(jira.refreshIssueByKey("connection", "cloud", "ENG-7").id()).isEqualTo("1001");
        assertThat(jira.issue("connection", "cloud", "1001").key()).isEqualTo("ENG-7");
        assertThatThrownBy(() -> jira.issue("connection", "cloud", "ENG-7")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> jira.refreshIssueByKey("connection", "cloud", "1001")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workspaceAssignmentSurvivesRefreshAndIsSharedAcrossConnections()
            throws Exception
    {
        JiraIssue issue = JiraMapping.issue("connection", "cloud", "https://example.atlassian.net", ticket());
        cache.write("connection.cloud.issue.1001", issue);
        cache.write("other.cloud.issue.1001", JiraMapping.issue("other", "cloud", "https://example.atlassian.net", ticket()));
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace("workspace", "ByteQuay")));
        assertThatThrownBy(() -> jira.goal("connection", "cloud", "1001", true)).hasMessageContaining("Assign a ByteQuay workspace");
        assertThatThrownBy(() -> jira.assignWorkspace("connection", "cloud", "1001", "missing")).hasMessageContaining("missing");
        assertThat(jira.assignWorkspace("connection", "cloud", "1001", "workspace").issue().workspace())
                .contains(new JiraDtos.WorkspaceLink("workspace", "ByteQuay"));
        assertThat(jira.goal("connection", "cloud", "1001", true).issue().isGoal()).isTrue();
        verifyNoInteractions(remote);
        assertThat(jira.refreshIssue("connection", "cloud", "1001").workspace()).contains(new JiraDtos.WorkspaceLink("workspace", "ByteQuay"));
        JiraService reopened = new JiraService(remote, new JiraCache(settings, mapper), tasks, workspaces, settingsProvider);
        assertThat(reopened.issue("other", "cloud", "1001").workspace()).contains(new JiraDtos.WorkspaceLink("workspace", "ByteQuay"));
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace("workspace", "Renamed")));
        assertThat(reopened.issue("other", "cloud", "1001").workspace()).contains(new JiraDtos.WorkspaceLink("workspace", "Renamed"));
        when(workspaces.findById("workspace")).thenReturn(Optional.empty());
        assertThat(reopened.issue("other", "cloud", "1001").workspace()).isEmpty();
    }

    @Test
    void linkedTaskOwnsWorkspaceAndCannotBeReassigned()
            throws Exception
    {
        JiraIssue issue = JiraMapping.issue("connection", "cloud", "https://example.atlassian.net", ticket());
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
        assertThat(jira.issues("connection", "cloud", 82, Optional.of(9L), true, false, false).issues()).extracting(JiraIssue::id).containsExactly("1001");
        verify(remote).request(eq("connection"), eq("cloud"), startsWith("/rest/software/"), eq("GET"), any());
    }

    @Test
    void quietHoursKeepAutomaticDetailCachedButAllowManualRefresh()
            throws Exception
    {
        JiraIssue cached = JiraMapping.issue("connection", "cloud", "https://example.atlassian.net", ticket());
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
    void childSearchPreventsTreatingParentStoryAsLeaf()
            throws Exception
    {
        when(remote.request(eq("connection"), eq("cloud"), startsWith("/rest/api/3/search/jql"), eq("GET"), any()))
                .thenReturn(json("{\"issues\":[{\"id\":\"1002\"}]}"));
        assertThat(jira.refreshIssue("connection", "cloud", "1001").hasChildren()).isTrue();
        when(remote.request(eq("connection"), eq("cloud"), startsWith("/rest/api/3/search/jql"), eq("GET"), any()))
                .thenReturn(json("{\"issues\":[]}"));
        assertThat(jira.refreshIssue("connection", "cloud", "1001").hasChildren()).isFalse();
    }

    @Test
    void staleEditNeverSendsMutation()
    {
        assertThatThrownBy(() -> jira.update("connection", "cloud", "1001", UPDATED.minusSeconds(1), ImmutableMap.of("assignee", "alice")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(remote, never()).request(anyString(), anyString(), anyString(), eq("PUT"), any());
    }

    @Test
    void assigneeUsesAccountIdEvenWhenEditMetadataContainsUserChoices()
    {
        jira.update("connection", "cloud", "1001", UPDATED, ImmutableMap.of("assignee", "alice"));
        verify(remote).request("connection", "cloud", "/rest/api/3/issue/1001", "PUT",
                Optional.of(ImmutableMap.of("fields", ImmutableMap.of("assignee", ImmutableMap.of("accountId", "alice")))));
    }

    @Test
    void numericWorkflowFieldPreservesJsonNumber()
            throws Exception
    {
        when(remote.request(eq("connection"), eq("cloud"), org.mockito.ArgumentMatchers.contains("/transitions?"), eq("GET"), any()))
                .thenReturn(json("""
                        {"transitions":[{"id":"2","name":"Start","to":{"id":"2","name":"Working"},
                        "fields":{"customfield_1":{"name":"Estimate","required":true,"schema":{"type":"number"}}}}]}
                        """));
        jira.transition("connection", "cloud", "1001", UPDATED, "2", ImmutableMap.of("customfield_1", 3));
        verify(remote).request("connection", "cloud", "/rest/api/3/issue/1001/transitions", "POST",
                Optional.of(ImmutableMap.of("transition", ImmutableMap.of("id", "2"), "fields", ImmutableMap.of("customfield_1", 3))));
    }

    @Test
    void uncertainCommentIsNotRetriedAcrossConnections()
    {
        jira.refreshIssue("connection", "cloud", "1001");
        when(remote.request(eq("connection"), eq("cloud"), eq("/rest/api/3/issue/1001/comment"), eq("POST"), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Connection lost"));
        assertThatThrownBy(() -> jira.comment("connection", "cloud", "1001", "Please check", "operation-123456789"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> jira.comment("replacement", "cloud", "1001", "Please check", "operation-123456789"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("unknown result");
        verify(remote, never()).request(eq("replacement"), anyString(), anyString(), eq("POST"), any());
    }

    @Test
    void followsEnhancedPaginationAndPreservesPreviousPageAfterFailure()
            throws Exception
    {
        String response = "{\"issues\":[" + ticket() + "],\"nextPageToken\":\"next/token\"}";
        when(remote.request(eq("connection"), eq("cloud"), startsWith("/rest/software/"), eq("GET"), any()))
                .thenReturn(json(response), json("{\"issues\":[],\"isLast\":true}"));
        assertThat(jira.issues("connection", "cloud", 82, Optional.of(9L), true, false, true).issues()).hasSize(1);
        verify(remote).request(eq("connection"), eq("cloud"), org.mockito.ArgumentMatchers.contains("nextPageToken=next/token"), eq("GET"), any());
        when(remote.request(eq("connection"), eq("cloud"), startsWith("/rest/software/"), eq("GET"), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> jira.issues("connection", "cloud", 82, Optional.of(9L), true, false, true)).isInstanceOf(ResponseStatusException.class);
        assertThat(jira.issues("connection", "cloud", 82, Optional.of(9L), true, false, false).issues()).hasSize(1);
    }

    @Test
    void completedHistoryUsesBoardScopeAndDoesNotRegisterPollingOrAttention()
            throws Exception
    {
        List<String> requests = new ArrayList<>();
        when(remote.request(eq("connection"), eq("cloud"), startsWith("/rest/software/"), eq("GET"), any()))
                .thenAnswer(call -> {
                    requests.add(URLDecoder.decode(call.getArgument(2), StandardCharsets.UTF_8));
                    return requests.size() == 1
                            ? json("{\"issues\":[" + completedTicket("2001") + "],\"nextPageToken\":\"next/token\"}")
                            : json("{\"issues\":[" + completedTicket("2002") + "],\"isLast\":true}");
                });
        JiraDtos.IssuePage result = jira.issues("connection", "cloud", 82, Optional.of(9L), true, true, true);
        assertThat(result.issues()).extracting(JiraIssue::id).containsExactly("2001", "2002");
        assertThat(result.partial()).isFalse();
        assertThat(requests).allSatisfy(path -> {
            assertThat(path).startsWith("/rest/software/1.0/board/82/issue?").doesNotContain("/sprint/");
            assertThat(path).contains("statusCategory = Done AND assignee = currentUser()", "ORDER BY statusCategoryChangedDate DESC, resolved DESC", "resolutiondate");
        });
        assertThat(requests.get(1)).contains("nextPageToken=next/token");
        assertThat(jira.issues("connection", "cloud", 82, Optional.of(45L), true, true, false).issues()).hasSize(2);
        assertThat(jira.issues("connection", "cloud", 82, Optional.of(9L), true, false, false).issues()).isEmpty();
        assertThat(saved).doesNotContainKey("jira.scopes");
        assertThat(jira.attention()).isEmpty();
        jira.poll();
        assertThat(requests).hasSize(2);
        jira.issues("connection", "cloud", 82, Optional.empty(), false, true, true);
        assertThat(requests.getLast()).contains("statusCategory = Done").doesNotContain("assignee =");
    }

    @Test
    void completedHistoryShowsPageLimitAndDropsReopenedCachedTickets()
            throws Exception
    {
        AtomicInteger pages = new AtomicInteger();
        when(remote.request(eq("connection"), eq("cloud"), startsWith("/rest/software/"), eq("GET"), any()))
                .thenAnswer(call -> {
                    int page = pages.incrementAndGet();
                    return json("{\"issues\":[" + completedTicket("200" + page) + "],\"nextPageToken\":\"page-" + page + "\"}");
                });
        JiraDtos.IssuePage result = jira.issues("connection", "cloud", 82, Optional.empty(), true, true, true);
        assertThat(result.partial()).isTrue();
        assertThat(result.issues()).hasSize(20);
        assertThat(pages).hasValue(20);
        ObjectNode reopened = (ObjectNode) ticket();
        reopened.put("id", "2001");
        ((ObjectNode) reopened.path("fields")).put("updated", "2026-09-26T02:00:00Z");
        cache.write("connection.cloud.issue.2001", JiraMapping.issue("connection", "cloud", "https://example.atlassian.net", reopened));
        JiraDtos.IssuePage cached = jira.issues("connection", "cloud", 82, Optional.empty(), true, true, false);
        assertThat(cached.partial()).isTrue();
        assertThat(cached.issues()).hasSize(19).noneMatch(issue -> issue.id().equals("2001"));
        assertThat(jira.issue("connection", "cloud", "2001").completedAt()).isEmpty();
    }

    private JsonNode completedTicket(String id)
            throws Exception
    {
        ObjectNode ticket = (ObjectNode) ticket();
        ticket.put("id", id);
        ObjectNode fields = (ObjectNode) ticket.path("fields");
        ((ObjectNode) fields.path("status").path("statusCategory")).put("key", "done");
        fields.put("resolutiondate", "2026-09-25T01:00:00Z");
        fields.put("statuscategorychangedate", "2026-09-26T00:00:00Z");
        return ticket;
    }

    @Test
    void boardRefreshCachesTheRichTextDescriptionTicketReadsShow()
            throws Exception
    {
        List<String> requests = new ArrayList<>();
        ObjectNode card = (ObjectNode) ticket();
        ((ObjectNode) card.path("fields")).put("description", "[runbook|https://example.com/runbook]");
        when(remote.request(eq("connection"), eq("cloud"), startsWith("/rest/software/"), eq("GET"), any())).thenAnswer(call -> {
            requests.add(URLDecoder.decode(call.getArgument(2), StandardCharsets.UTF_8));
            return json("{\"issues\":[" + card + "],\"isLast\":true}");
        });
        when(remote.request(eq("connection"), eq("cloud"), startsWith("/rest/api/3/search/jql"), eq("GET"), any())).thenAnswer(call -> {
            requests.add(URLDecoder.decode(call.getArgument(2), StandardCharsets.UTF_8));
            return json("""
                    {"issues":[{"id":"1001","fields":{"description":{"type":"doc","content":[{"type":"paragraph","content":[
                    {"type":"text","text":"runbook","marks":[{"type":"link","attrs":{"href":"https://example.com/runbook"}}]}]}]}}}]}
                    """);
        });
        jira.issues("connection", "cloud", 82, Optional.of(9L), true, false, true);
        assertThat(requests).hasSize(2);
        assertThat(requests.getFirst()).doesNotContain("description");
        assertThat(requests.getLast()).contains("id in (1001)", "fields=description");
        assertThat(jira.detail("connection", "cloud", "1001", false).issue().body()).isEqualTo("[runbook](https://example.com/runbook)");
    }

    @Test
    void olderPollCannotReplaceNewerCachedRevision()
            throws Exception
    {
        JsonNode fresh = ticket().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) fresh.path("fields")).put("updated", "2026-09-26T02:00:00Z").put("summary", "Already edited");
        cache.write("connection.cloud.issue.1001", JiraMapping.issue("connection", "cloud", "https://example.atlassian.net", fresh));
        when(remote.request(eq("connection"), eq("cloud"), startsWith("/rest/software/"), eq("GET"), any()))
                .thenReturn(json("{\"issues\":[" + ticket() + "],\"isLast\":true}"));
        assertThat(jira.issues("connection", "cloud", 82, Optional.of(9L), true, false, true).issues().getFirst().title()).isEqualTo("Already edited");
        assertThat(jira.issue("connection", "cloud", "1001").title()).isEqualTo("Already edited");
    }

    @Test
    void commentMentionsKeepTheConnectedSiteInCachedContext()
            throws Exception
    {
        when(remote.site("connection", "cloud")).thenReturn(new JiraDtos.Site("cloud", "Team", "https://team.atlassian.net"));
        when(remote.request(eq("connection"), eq("cloud"), startsWith("/rest/api/3/issue/1001/comment?"), eq("GET"), any()))
                .thenReturn(json("""
                        {"comments":[{"id":"comment","author":{"accountId":"alice"},"body":{"type":"doc","content":[
                          {"type":"paragraph","content":[{"type":"mention","attrs":{"id":"712020:jack","text":"@Jack Chen"}}]}
                        ]},"created":"2026-09-26T01:00:00Z","updated":"2026-09-26T01:00:00Z"}],"total":1}
                        """));
        String expected = "[@Jack Chen](https://team.atlassian.net/jira/people/712020%3Ajack)";

        assertThat(jira.detail("connection", "cloud", "1001", true).comments().getFirst().body()).isEqualTo(expected);
        assertThat(jira.detail("connection", "cloud", "1001", false).comments().getFirst().body()).isEqualTo(expected);
        assertThat(jira.issue("connection", "cloud", "1001").comments().getFirst().body()).isEqualTo(expected);
    }

    @Test
    void commentPageLimitReturnsVisiblePartialResult()
            throws Exception
    {
        when(remote.request(eq("connection"), eq("cloud"), startsWith("/rest/api/3/issue/1001/comment?"), eq("GET"), any()))
                .thenReturn(json("""
                        {"comments":[{"id":"comment","author":{"accountId":"alice"},"body":{"type":"doc","content":[]},
                        "created":"2026-09-26T01:00:00Z","updated":"2026-09-26T01:00:00Z"}],"total":3000}
                        """));
        JiraDtos.Detail detail = jira.detail("connection", "cloud", "1001", true);
        assertThat(detail.commentsTruncated()).isTrue();
        assertThat(detail.comments()).hasSize(20);
        assertThat(jira.issue("connection", "cloud", "1001").commentsTruncated()).isTrue();
    }

    @Test
    void richTextPreservesUsefulLinksWithoutRenderingHtml()
            throws Exception
    {
        JsonNode text = json("""
                {"type":"doc","content":[{"type":"paragraph","content":[
                  {"type":"text","text":"<design>","marks":[{"type":"link","attrs":{"href":"https://example.com/design"}}]},
                  {"type":"text","text":" bad","marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}]}
                ]}]}
                """);
        assertThat(JiraMapping.markdown("https://example.atlassian.net", text)).contains("[&lt;design&gt;](https://example.com/design)").doesNotContain("javascript:", "<design>");
        assertThat(mapper.valueToTree(JiraMapping.document("first\n\nthird")).path("content")).hasSize(3);
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
