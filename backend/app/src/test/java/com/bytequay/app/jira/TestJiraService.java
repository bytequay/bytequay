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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
    void detailListsChildrenAndLinksAsThisTicketReadsThem()
            throws Exception
    {
        assertThat(jira.detail("connection", "cloud", "1001", true).children()).isEmpty();
        verify(remote).request(eq("connection"), eq("cloud"), startsWith("/rest/api/3/search/jql"), eq("GET"), any());

        ObjectNode parent = (ObjectNode) ticket();
        ((ObjectNode) parent.path("fields")).set("subtasks", json("[{\"id\":\"1002\"}]"));
        ((ObjectNode) parent.path("fields")).set("issuelinks", json("""
                [{"id":"1","type":{"inward":"is blocked by","outward":"blocks"},"outwardIssue":{"id":"2001","key":"ENG-9","fields":{"summary":"Blocked"}}},
                 {"id":"2","type":{"inward":"is blocked by","outward":"blocks"},"inwardIssue":{"id":"2002","key":"ENG-10","fields":{"summary":"Blocker"}}}]
                """));
        doReturn(parent).when(remote).request(eq("connection"), eq("cloud"), startsWith("/rest/api/3/issue/1001?"), eq("GET"), any());
        List<String> searches = new ArrayList<>();
        when(remote.request(eq("connection"), eq("cloud"), startsWith("/rest/api/3/search/jql"), eq("GET"), any())).thenAnswer(call -> {
            searches.add(URLDecoder.decode(call.getArgument(2), StandardCharsets.UTF_8));
            return json("""
                    {"issues":[{"id":"1002","key":"ENG-8","fields":{"summary":"Add the flag","issuetype":{"name":"Subtask"},"priority":{"id":"2","name":"P2"},
                      "status":{"id":"3","name":"In Progress","statusCategory":{"key":"indeterminate"}},"assignee":{"accountId":"alice","displayName":"Alice"}}}],
                     "nextPageToken":"next"}
                    """);
        });

        JiraDtos.Detail detail = jira.detail("connection", "cloud", "1001", true);
        assertThat(searches).singleElement().asString().contains("parent = 1001 ORDER BY created ASC", "fields=summary,issuetype,status,priority,assignee");
        assertThat(detail.children()).containsExactly(new JiraDtos.RelatedIssue("1002", "ENG-8", "Add the flag", "Subtask", new JiraDtos.Status("3", "In Progress", "indeterminate"),
                Optional.of(new JiraDtos.Choice("2", "P2")), Optional.of(new JiraDtos.Person("alice", "Alice", Optional.empty()))));
        assertThat(detail.childrenTruncated()).isTrue();
        assertThat(detail.links()).extracting(link -> link.relation() + " " + link.issue().key()).containsExactly("blocks ENG-9", "is blocked by ENG-10");
        JiraDtos.Detail cached = jira.detail("connection", "cloud", "1001", false);
        assertThat(cached.children()).isEqualTo(detail.children());
        assertThat(cached.links()).isEqualTo(detail.links());
    }

    @Test
    void newChildUsesTheParentProjectAndATypeOneLevelBelowIt()
            throws Exception
    {
        doReturn(json("{\"id\":\"1001\",\"fields\":{\"project\":{\"id\":\"100\"},\"issuetype\":{\"hierarchyLevel\":1}}}")).when(remote)
                .request(eq("connection"), eq("cloud"), startsWith("/rest/api/3/issue/1001?fields=project"), eq("GET"), any());
        doReturn(json("""
                {"issueTypes":[{"id":"1","name":"Epic","hierarchyLevel":1},{"id":"2","name":"Story","hierarchyLevel":0},
                  {"id":"3","name":"Task","hierarchyLevel":0},{"id":"4","name":"Subtask","hierarchyLevel":-1,"subtask":true}]}
                """)).when(remote).request(eq("connection"), eq("cloud"), startsWith("/rest/api/3/issue/createmeta/100/issuetypes?"), eq("GET"), any());
        assertThat(jira.childTypes("connection", "cloud", "1001")).containsExactly(new JiraDtos.Choice("2", "Story"), new JiraDtos.Choice("3", "Task"));

        jira.createChild("connection", "cloud", "1001", "3", "  Add the flag ");
        verify(remote).request("connection", "cloud", "/rest/api/3/issue", "POST", Optional.of(ImmutableMap.of("fields", ImmutableMap.of("project", ImmutableMap.of("id", "100"),
                "parent", ImmutableMap.of("id", "1001"), "issuetype", ImmutableMap.of("id", "3"), "summary", "Add the flag"))));
        assertThatThrownBy(() -> jira.createChild("connection", "cloud", "1001", "3", " ")).isInstanceOf(IllegalArgumentException.class);
        when(remote.request("connection", "cloud", "/rest/api/3/issue", "POST", Optional.of(ImmutableMap.of("fields", ImmutableMap.of("project", ImmutableMap.of("id", "100"),
                "parent", ImmutableMap.of("id", "1001"), "issuetype", ImmutableMap.of("id", "3"), "summary", "Needs a team")))))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> jira.createChild("connection", "cloud", "1001", "3", "Needs a team")).hasMessageContaining("create it in Jira");
    }

    @Test
    void linksPutThisTicketOnTheSideItsRelationReads()
            throws Exception
    {
        doReturn(json("{\"issueLinkTypes\":[{\"id\":\"10000\",\"name\":\"Blocks\",\"inward\":\"is blocked by\",\"outward\":\"blocks\"}]}")).when(remote)
                .request("connection", "cloud", "/rest/api/3/issueLinkType", "GET", Optional.empty());
        assertThat(jira.linkTypes("connection", "cloud")).containsExactly(new JiraDtos.LinkType("10000", "Blocks", "is blocked by", "blocks"));
        jira.refreshIssue("connection", "cloud", "1001");

        jira.link("connection", "cloud", "1001", "10000", true, "ENG-9");
        verify(remote).request("connection", "cloud", "/rest/api/3/issueLink", "POST", Optional.of(ImmutableMap.of("type", ImmutableMap.of("id", "10000"),
                "inwardIssue", ImmutableMap.of("id", "1001"), "outwardIssue", ImmutableMap.of("key", "ENG-9"))));
        jira.link("connection", "cloud", "1001", "10000", false, "ENG-10");
        verify(remote).request("connection", "cloud", "/rest/api/3/issueLink", "POST", Optional.of(ImmutableMap.of("type", ImmutableMap.of("id", "10000"),
                "inwardIssue", ImmutableMap.of("key", "ENG-10"), "outwardIssue", ImmutableMap.of("id", "1001"))));
        assertThatThrownBy(() -> jira.link("connection", "cloud", "1001", "10000", true, "ENG-7")).hasMessageContaining("itself");
        assertThatThrownBy(() -> jira.link("connection", "cloud", "1001", "10000", true, "eng-9")).isInstanceOf(IllegalArgumentException.class);
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
        assertThatThrownBy(() -> jira.comment("connection", "cloud", "1001", "Please check", "operation-123456789", Optional.empty()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> jira.comment("replacement", "cloud", "1001", "Please check", "operation-123456789", Optional.empty()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("unknown result");
        verify(remote, never()).request(eq("replacement"), anyString(), anyString(), eq("POST"), any());
    }

    @Test
    void repliesNameTheirParentAndSayWhenJiraPostsThemAtTopLevel()
            throws Exception
    {
        jira.refreshIssue("connection", "cloud", "1001");
        when(remote.request(eq("connection"), eq("cloud"), eq("/rest/api/3/issue/1001/comment"), eq("POST"), any()))
                .thenReturn(json("{\"id\":\"9\",\"parentId\":\"5\"}"), json("{\"id\":\"10\"}"));

        assertThat(jira.comment("connection", "cloud", "1001", "Agreed", "operation-reply-000001", Optional.of("5")).error()).isEmpty();
        verify(remote).request(eq("connection"), eq("cloud"), eq("/rest/api/3/issue/1001/comment"), eq("POST"),
                argThat(body -> body.orElseThrow() instanceof Map<?, ?> request && "5".equals(request.get("parentId"))));
        assertThat(jira.comment("connection", "cloud", "1001", "Agreed", "operation-reply-000002", Optional.of("5")).error())
                .contains("Jira saved your reply as a new comment instead of a reply.");
    }

    @Test
    void editsOnlyFreshPlainOwnCommentsAndKeepsTheirMentions()
            throws Exception
    {
        jira.refreshIssue("connection", "cloud", "1001");
        String own = """
                {"id":"5","author":{"accountId":"me"},"updated":"U1","body":{"type":"doc","version":1,"content":[
                  {"type":"paragraph","content":[{"type":"mention","attrs":{"id":"alex","text":"@Alex Kim"}},{"type":"text","text":" please check"}]}]}}
                """;
        doReturn(json(own)).when(remote).request("connection", "cloud", "/rest/api/3/issue/1001/comment/5", "GET", Optional.empty());

        jira.editComment("connection", "cloud", "1001", "5", "@Alex Kim fixed now", "U1");
        List<Map<String, Object>> edited = ImmutableList.of(ImmutableMap.of("type", "mention", "attrs", ImmutableMap.of("id", "alex", "text", "@Alex Kim")),
                ImmutableMap.of("type", "text", "text", " fixed now"));
        verify(remote).request("connection", "cloud", "/rest/api/3/issue/1001/comment/5", "PUT", Optional.of(ImmutableMap.of("body",
                ImmutableMap.of("version", 1, "type", "doc", "content", ImmutableList.of(ImmutableMap.of("type", "paragraph", "content", edited))))));
        assertThatThrownBy(() -> jira.editComment("connection", "cloud", "1001", "5", "Again", "U0")).hasMessageContaining("changed in Jira");
        doReturn(json(own.replace("\"me\"", "\"alex\""))).when(remote).request("connection", "cloud", "/rest/api/3/issue/1001/comment/5", "GET", Optional.empty());
        assertThatThrownBy(() -> jira.editComment("connection", "cloud", "1001", "5", "Again", "U1")).hasMessageContaining("your own");
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
    void commentRepliesKeepTheirParentInCachedContext()
            throws Exception
    {
        when(remote.request(eq("connection"), eq("cloud"), startsWith("/rest/api/3/issue/1001/comment?"), eq("GET"), any()))
                .thenReturn(json("""
                        {"comments":[
                          {"id":"2","parentId":"1","author":{"accountId":"bob"},"body":{"type":"doc","content":[]},
                           "created":"2026-09-26T02:00:00Z","updated":"2026-09-26T02:00:00Z"},
                          {"id":"1","author":{"accountId":"alice"},"body":{"type":"doc","content":[]},
                           "created":"2026-09-26T01:00:00Z","updated":"2026-09-26T01:00:00Z"}
                        ],"total":2}
                        """));

        assertThat(jira.detail("connection", "cloud", "1001", true).comments()).extracting(JiraDtos.Comment::parentId).containsExactly(Optional.empty(), Optional.of("1"));
        assertThat(jira.detail("connection", "cloud", "1001", false).comments()).extracting(JiraDtos.Comment::parentId).containsExactly(Optional.empty(), Optional.of("1"));
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
    void detailReadsRunTogetherAndEditMetadataFailureOnlyDisablesEditing()
    {
        CountDownLatch started = new CountDownLatch(4);
        doAnswer(call -> {
            started.countDown();
            // Reads made one at a time would each wait here alone.
            assertThat(started.await(5, SECONDS)).isTrue();
            if (call.<String>getArgument(2).endsWith("/editmeta")) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
            return json("[{\"accountId\":\"alice\",\"displayName\":\"Alice\"}]");
        }).when(remote).request(eq("connection"), eq("cloud"), argThat(path -> path.startsWith("/rest/api/3/issue/1001/") || path.startsWith("/rest/api/3/user/")),
                eq("GET"), any());
        JiraDtos.Detail detail = jira.detail("connection", "cloud", "1001", true);
        assertThat(detail.error()).get().asString().contains("Some editing options are unavailable");
        assertThat(detail.editableFields()).isEmpty();
        assertThat(detail.assignees()).isEmpty();
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
