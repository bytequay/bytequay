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

import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
    private final JiraCache cache = new JiraCache(settings, mapper);
    private final JiraService jira = new JiraService(remote, cache, tasks);
    private final Map<String, String> saved = new HashMap<>();

    @BeforeEach
    void setup()
            throws Exception
    {
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
        verifyNoInteractions(remote);
    }

    @Test
    void childSearchPreventsTreatingParentStoryAsLeaf()
            throws Exception
    {
        when(remote.request(eq("connection"), eq("cloud"), org.mockito.ArgumentMatchers.startsWith("/rest/api/3/search/jql"), eq("GET"), any()))
                .thenReturn(json("{\"issues\":[{\"id\":\"1002\"}]}"));
        assertThat(jira.refreshIssue("connection", "cloud", "1001").hasChildren()).isTrue();
        when(remote.request(eq("connection"), eq("cloud"), org.mockito.ArgumentMatchers.startsWith("/rest/api/3/search/jql"), eq("GET"), any()))
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
        when(remote.request(eq("connection"), eq("cloud"), org.mockito.ArgumentMatchers.startsWith("/rest/software/"), eq("GET"), any()))
                .thenReturn(json(response), json("{\"issues\":[],\"isLast\":true}"));
        assertThat(jira.issues("connection", "cloud", 82, Optional.of(9L), true, true).issues()).hasSize(1);
        verify(remote).request(eq("connection"), eq("cloud"), org.mockito.ArgumentMatchers.contains("nextPageToken=next/token"), eq("GET"), any());
        when(remote.request(eq("connection"), eq("cloud"), org.mockito.ArgumentMatchers.startsWith("/rest/software/"), eq("GET"), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> jira.issues("connection", "cloud", 82, Optional.of(9L), true, true)).isInstanceOf(ResponseStatusException.class);
        assertThat(jira.issues("connection", "cloud", 82, Optional.of(9L), true, false).issues()).hasSize(1);
    }

    @Test
    void olderPollCannotReplaceNewerCachedRevision()
            throws Exception
    {
        JsonNode fresh = ticket().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) fresh.path("fields")).put("updated", "2026-09-26T02:00:00Z").put("summary", "Already edited");
        cache.write("connection.cloud.issue.1001", JiraMapping.issue("connection", "cloud", "https://example.atlassian.net", fresh));
        when(remote.request(eq("connection"), eq("cloud"), org.mockito.ArgumentMatchers.startsWith("/rest/software/"), eq("GET"), any()))
                .thenReturn(json("{\"issues\":[" + ticket() + "],\"isLast\":true}"));
        assertThat(jira.issues("connection", "cloud", 82, Optional.of(9L), true, true).issues().getFirst().title()).isEqualTo("Already edited");
        assertThat(jira.issue("connection", "cloud", "1001").title()).isEqualTo("Already edited");
    }

    @Test
    void commentPageLimitReturnsVisiblePartialResult()
            throws Exception
    {
        when(remote.request(eq("connection"), eq("cloud"), org.mockito.ArgumentMatchers.startsWith("/rest/api/3/issue/1001/comment?"), eq("GET"), any()))
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
        assertThat(JiraMapping.markdown(text)).contains("[&lt;design&gt;](https://example.com/design)").doesNotContain("javascript:", "<design>");
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

    private JsonNode json(String value)
            throws Exception
    {
        return mapper.readTree(value);
    }
}
