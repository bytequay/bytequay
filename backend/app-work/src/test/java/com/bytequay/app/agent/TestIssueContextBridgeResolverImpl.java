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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.IssueContextBridge;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.workitem.UnifiedWorkItem;
import com.bytequay.app.workitem.WorkItemCatalog;
import com.bytequay.app.workitem.WorkItemContextView;
import com.bytequay.core.domain.Epic;
import com.bytequay.core.domain.EpicChild;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.IssueEpic;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.domain.JiraIssueTask;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemContextLink;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.EpicStore;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.RepositoryIssueDetailStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraDtos;
import com.bytequay.integration.jira.JiraIssue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestIssueContextBridgeResolverImpl
{
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final GitHubIssueSource SOURCE = new GitHubIssueSource("github.com", 70, "upstream/repo", 7,
            "https://github.com/upstream/repo/issues/7", "Fix selection", "Accepted description", NOW);
    private final TaskId taskId = TaskId.random();
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final IssueDevelopmentStore sources = mock(IssueDevelopmentStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final RepositoryIssueStore issues = mock(RepositoryIssueStore.class);
    private final RepositoryIssueDetailStore details = mock(RepositoryIssueDetailStore.class);
    private final JiraIssueTaskStore jiraSources = mock(JiraIssueTaskStore.class);
    private final JiraService jira = mock(JiraService.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());
    private final WorkItemStore workItems = mock(WorkItemStore.class);
    private final EpicStore epics = mock(EpicStore.class);
    private final WorkItemCatalog catalog = mock(WorkItemCatalog.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final IssueContextBridgeResolverImpl resolver = new IssueContextBridgeResolverImpl(tasks, sources, workspaces, issues, details, mapper, jiraSources, jira,
            workItems, epics, beans.getBeanProvider(WorkItemCatalog.class));
    private final AgentTask task = mock(AgentTask.class, withSettings().mockMaker(MockMakers.INLINE));

    @BeforeEach
    void setUp()
    {
        beans.registerSingleton("catalog", catalog);
        when(tasks.find(taskId)).thenReturn(Optional.of(task));
        when(task.interactive()).thenReturn(true);
        when(task.state()).thenReturn(TaskState.OPEN);
        when(task.workspaceId()).thenReturn(Optional.of("workspace"));
        when(sources.findByTaskId(taskId.toString())).thenReturn(Optional.of(new IssueTask(taskId.toString(), "workspace", SOURCE, Optional.of("Implement selection"), NOW)));
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.issueHost()).thenReturn("github.com");
        when(workspace.monitoredRepository()).thenReturn(RepoRef.of("upstream", "repo"));
        when(workspace.monitoredRepositoryId()).thenReturn(2L);
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace));
    }

    @Test
    void jiraContextRemainsTaskBoundOfflineAndAvailableAfterCacheEviction()
            throws Exception
    {
        when(sources.findByTaskId(taskId.toString())).thenReturn(Optional.empty());
        JiraIssueSource source = new JiraIssueSource("connection", "cloud", "10001", "APP-7", "https://company.atlassian.net/browse/APP-7",
                "Accepted Jira title", "Accepted Jira body", NOW);
        when(jiraSources.findByTaskId(taskId.toString())).thenReturn(Optional.of(new JiraIssueTask(taskId.toString(), "workspace", source, "Implement Jira scope", NOW)));
        JiraDtos.Person author = new JiraDtos.Person("account", "Alice", Optional.empty());
        when(jira.issue("connection", "cloud", "10001")).thenReturn(new JiraIssue("connection", "cloud", "10001", "APP-7", source.url(),
                "Changed Jira title", "Changed Jira body", "Story", new JiraDtos.Status("3", "Done", "done"), Optional.of(author),
                ImmutableList.of("bug"), Optional.empty(), Optional.empty(), false, false, NOW.plusSeconds(1), Optional.empty(), Optional.empty(),
                ImmutableList.of(new JiraDtos.Comment("comment-1", Optional.empty(), author, "x".repeat(2_500), NOW.toString(), NOW.toString(), false, Optional.empty())), true,
                Optional.of(NOW), Optional.empty()));
        IssueContextBridge bridge = resolver.resolve(taskId.toString()).orElseThrow();
        JsonNode context = mapper.readTree(bridge.read().get());
        assertThat(context.at("/acceptedIssue/body").asText()).isEqualTo(source.body());
        assertThat(context.at("/cachedMetadata/completedAt").asText()).isEqualTo(NOW.toString());
        assertThat(context.at("/cachedMetadata/labels/0").asText()).isEqualTo("bug");
        assertThat(context.at("/cachedMetadata/differsFromAcceptedDescription").asBoolean()).isTrue();
        assertThat(context.at("/cachedTimeline/truncated").asBoolean()).isTrue();
        assertThat(context.at("/cachedTimeline/events/0/body").asText()).hasSize(2_000);
        assertThat(context.at("/cachedTimeline/events/0/event").asText()).isEqualTo("commented");
        assertThat(context.at("/cachedTimeline/events/0/createdAt").asText()).isEqualTo(NOW.toString());
        assertThat(context.at("/cachedTimeline/events/0/updatedAt").asText()).isEqualTo(NOW.toString());
        assertThat(bridge.instruction()).contains("Jira", "Do not fetch", "untrusted");
        when(jira.issue("connection", "cloud", "10001")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        JsonNode retained = mapper.readTree(bridge.read().get());
        assertThat(retained.at("/acceptedIssue/body").asText()).isEqualTo(source.body());
        assertThat(retained.at("/cachedMetadata/status").asText()).isEqualTo("missing");
        verify(jira, never()).refreshIssue(anyString(), anyString(), anyString());
        when(task.state()).thenReturn(TaskState.COMPLETED);
        assertThat(resolver.resolve(taskId.toString())).isEmpty();
        assertThatThrownBy(() -> bridge.read().get()).isInstanceOf(AgentConflictException.class);
    }

    @Test
    void returnsAcceptedDescriptionAndExplicitlyCachedMetadataWithBoundedTimeline()
            throws Exception
    {
        when(issues.find(any(), any(), eq(7))).thenReturn(Optional.of(new RepositoryIssue(70, 7, "Edited title", "New scope", "alice", "", SOURCE.url(),
                ImmutableList.of("bug"), ImmutableList.of("alice"), 25, NOW, NOW.plusSeconds(20))));
        when(details.find(any(), any(), eq(7))).thenReturn(Optional.of(new RepositoryIssueDetail(2, 7,
                IntStream.range(0, 25).mapToObj(index -> new PrTimelineEvent((long) index, "commented", "alice", NOW.plusSeconds(index),
                        "c".repeat(2_500), Optional.empty())).collect(toImmutableList()), "Bug", ImmutableList.of("Bug"), Optional.of(NOW.plusSeconds(10)))));
        JsonNode context = mapper.readTree(resolver.resolve(taskId.toString()).orElseThrow().read().get());
        assertThat(context.at("/acceptedIssue/body").asText()).isEqualTo("Accepted description");
        assertThat(context.path("instructions").asText()).isEqualTo("Implement selection");
        assertThat(context.at("/cachedMetadata/labels/0").asText()).isEqualTo("bug");
        assertThat(context.at("/cachedMetadata/differsFromAcceptedDescription").asBoolean()).isTrue();
        assertThat(context.at("/cachedTimeline/status").asText()).isEqualTo("stale");
        assertThat(context.at("/cachedTimeline/truncated").asBoolean()).isTrue();
        assertThat(context.at("/cachedTimeline/events")).hasSize(20);
        assertThat(context.at("/cachedTimeline/events/0/body").asText()).hasSize(2_000);
        assertThat(context.at("/cachedTimeline/events/0/bodyTruncated").asBoolean()).isTrue();
        assertThat(context.path("source").asText()).contains("no remote request");
    }

    @Test
    void timelineOmitsMissingTimestampAndKeepsEmptyBodyAndNullPayloadFields()
            throws Exception
    {
        when(details.find(any(), any(), eq(7))).thenReturn(Optional.of(new RepositoryIssueDetail(2, 7,
                ImmutableList.of(new PrTimelineEvent(null, "commented", null, null, null, Optional.empty())), "", ImmutableList.of(), Optional.empty())));

        JsonNode context = mapper.readTree(resolver.resolve(taskId.toString()).orElseThrow().read().get());
        assertThat(context.at("/cachedTimeline/events/0")).isEqualTo(mapper.readTree("""
                {
                  "event": "commented",
                  "actor": null,
                  "body": "",
                  "bodyTruncated": false,
                  "label": null,
                  "state": null,
                  "sourceTitle": null,
                  "sourceUrl": null,
                  "milestone": null
                }
                """));
    }

    @Test
    void cacheEvictionAndMissingInstructionsDoNotLoseSourceOrInventClosedState()
            throws Exception
    {
        when(sources.findByTaskId(taskId.toString())).thenReturn(Optional.of(new IssueTask(taskId.toString(), "workspace", SOURCE, Optional.empty(), NOW)));
        JsonNode context = mapper.readTree(resolver.resolve(taskId.toString()).orElseThrow().read().get());
        assertThat(context.at("/acceptedIssue/body").asText()).isEqualTo(SOURCE.body());
        assertThat(context.at("/acceptedIssue/url").asText()).isEqualTo(SOURCE.url());
        assertThat(context.path("instructions").asText()).isEmpty();
        assertThat(context.at("/cachedMetadata/status").asText()).isEqualTo("missing");
        assertThat(context.at("/cachedTimeline/status").asText()).isEqualTo("missing");
        assertThat(context.at("/cachedMetadata/state").isMissingNode()).isTrue();
    }

    @Test
    void ordinaryTasksHaveNoToolAndRetainedBridgeRechecksBinding()
    {
        assertThat(resolver.resolve(TaskId.random().toString())).isEmpty();
        IssueContextBridge bridge = resolver.resolve(taskId.toString()).orElseThrow();
        when(sources.findByTaskId(taskId.toString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> bridge.read().get()).isInstanceOf(AgentConflictException.class);
        verifyNoInteractions(issues, details);
    }

    @Test
    void epicWorkWithoutASourceReadsItsEpicAndItsPlaceInTheWork()
            throws Exception
    {
        when(sources.findByTaskId(taskId.toString())).thenReturn(Optional.empty());
        String epicId = WorkItemLink.issueIdentity("github.com", 900);
        WorkItem done = work("done", "Cache table metadata", WorkItem.State.DONE, 0, epicId, "", ImmutableList.of(), ImmutableList.of(
                new WorkItemLink("PULL_REQUEST:github.com:upstream/repo#31", new GoalItem(GoalItemKind.PULL_REQUEST, "upstream/repo#31", "#31", "Cache table metadata", NOW))));
        WorkItem current = work("current", "Invalidate on DDL", WorkItem.State.WORKING, 1, epicId, "Tables changed by DDL are read again", ImmutableList.of("done"),
                ImmutableList.of(new WorkItemLink(WorkItemLink.taskIdentity(taskId.toString()), new GoalItem(GoalItemKind.TASK, taskId.toString(), "Task", "Invalidate", NOW))));
        WorkItem elsewhere = work("elsewhere", "Work of another Epic", WorkItem.State.PLANNED, 0, WorkItemLink.issueIdentity("github.com", 901), "", ImmutableList.of(),
                ImmutableList.of());
        when(workItems.findByIdentity(WorkItemLink.taskIdentity(taskId.toString()))).thenReturn(Optional.of(current));
        when(workItems.list("workspace")).thenReturn(ImmutableList.of(elsewhere, current, done));
        when(sources.findEpic("github.com", 900)).thenReturn(Optional.of(new IssueEpic("github.com", 900, "upstream/repo", 9, true, NOW)));
        when(issues.find(any(), any(), eq(9))).thenReturn(Optional.of(new RepositoryIssue(900, 9, "Metadata cache", "Epic description", "alice", "",
                "https://github.com/upstream/repo/issues/9", ImmutableList.of(), ImmutableList.of(), 0, NOW, NOW)));
        EpicChild child = new EpicChild(WorkItemLink.issueIdentity("github.com", 910), GoalItemKind.ISSUE, "upstream/repo#10", "#10", "Benchmark warm cache", "Open", false,
                Optional.empty());
        when(epics.find(epicId)).thenReturn(Optional.of(Epic.of(epicId, "workspace").withChildren(ImmutableList.of(child), NOW, false)));

        IssueContextBridge bridge = resolver.resolve(taskId.toString()).orElseThrow();
        assertThat(bridge.instruction()).contains(IssueContextBridge.WORK_INSTRUCTION, IssueContextBridge.EPIC_INSTRUCTION);
        JsonNode context = mapper.readTree(bridge.read().get());
        assertThat(context.path("source").asText()).contains("no remote request");
        assertThat(context.at("/workItem/id").asText()).isEqualTo(current.id());
        assertThat(context.at("/workItem/acceptanceCriteria").asText()).isEqualTo(current.acceptanceCriteria());
        assertThat(context.at("/epic/reference").asText()).isEqualTo("#9");
        assertThat(context.at("/epic/url").asText()).isEqualTo("https://github.com/upstream/repo/issues/9");
        assertThat(context.at("/epic/title").asText()).isEqualTo("Metadata cache");
        assertThat(context.at("/epic/description").asText()).isEqualTo("Epic description");
        assertThat(context.at("/epic/children/0/reference").asText()).isEqualTo("#10");
        assertThat(context.at("/epic/children/0/status").asText()).isEqualTo("Open");
        assertThat(context.at("/epic/work")).extracting(item -> item.path("title").asText()).containsExactly("Cache table metadata", "Invalidate on DDL");
        assertThat(context.at("/epic/work")).extracting(item -> item.path("current").asBoolean()).containsExactly(false, true);
        assertThat(context.at("/epic/work/0/state").asText()).isEqualTo("DONE");
        assertThat(context.at("/epic/work/0/links/0/reference").asText()).isEqualTo("#31");
        assertThat(context.at("/epic/currentWork/position").asInt()).isEqualTo(2);
        assertThat(context.at("/epic/currentWork/doneWhen").asText()).isEqualTo("Tables changed by DDL are read again");
        assertThat(context.at("/epic/currentWork/waitsOn/0").asText()).isEqualTo("Cache table metadata");
        assertThat(context.at("/epic/currentWork/links")).isEmpty();

        // Its issue left the local store: the Epic's work stays readable.
        when(issues.find(any(), any(), eq(9))).thenReturn(Optional.empty());
        JsonNode evicted = mapper.readTree(bridge.read().get());
        assertThat(evicted.at("/epic/status").asText()).isEqualTo("missing");
        assertThat(evicted.at("/epic/title").isMissingNode()).isTrue();
        assertThat(evicted.at("/epic/work")).hasSize(2);
        // A task of another workspace reads none of this workspace's work.
        when(task.workspaceId()).thenReturn(Optional.of("other"));
        assertThat(resolver.resolve(taskId.toString())).isEmpty();
        assertThatThrownBy(() -> bridge.read().get()).isInstanceOf(AgentConflictException.class);
    }

    @Test
    void issueWorkOfAnEpicReadsItsEpicBesideItsIssue()
            throws Exception
    {
        String epicId = WorkItemLink.issueIdentity("github.com", 900);
        WorkItem current = work("current", "Fix selection", WorkItem.State.WORKING, 0, epicId, "", ImmutableList.of(), ImmutableList.of());
        when(workItems.findByIdentity(WorkItemLink.taskIdentity(taskId.toString()))).thenReturn(Optional.of(current));
        when(workItems.list("workspace")).thenReturn(ImmutableList.of(current));
        IssueContextBridge bridge = resolver.resolve(taskId.toString()).orElseThrow();
        assertThat(bridge.instruction()).contains(IssueContextBridge.INSTRUCTION, IssueContextBridge.EPIC_INSTRUCTION);
        JsonNode context = mapper.readTree(bridge.read().get());
        assertThat(context.at("/acceptedIssue/body").asText()).isEqualTo(SOURCE.body());
        assertThat(context.at("/epic/status").asText()).isEqualTo("missing");
        assertThat(context.at("/epic/currentWork/title").asText()).isEqualTo("Fix selection");
        assertThat(context.at("/epic/work")).hasSize(1);
    }

    @Test
    void standaloneLocalWorkRetainsItsDescriptionWithoutAnEpicOrRemoteSource()
            throws Exception
    {
        when(sources.findByTaskId(taskId.toString())).thenReturn(Optional.empty());
        WorkItem item = new WorkItem("local", "workspace", "Private repair", WorkItem.Kind.DEVELOP, "Reproduction and full acceptance criteria",
                WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.WORKING, 0, ImmutableList.of(), ImmutableList.of(), NOW, NOW,
                WorkItem.Priority.NORMAL, ImmutableList.of(), Optional.empty(), 0, ImmutableList.of());
        when(workItems.findByIdentity(WorkItemLink.taskIdentity(taskId.toString()))).thenReturn(Optional.of(item));
        IssueContextBridge bridge = resolver.resolve(taskId.toString()).orElseThrow();
        assertThat(bridge.instruction()).isEqualTo(IssueContextBridge.WORK_INSTRUCTION + "\n" + IssueContextBridge.PRIVACY_INSTRUCTION);
        JsonNode result = mapper.readTree(bridge.read().get());
        assertThat(result.at("/workItem/id").asText()).isEqualTo("local");
        assertThat(result.at("/workItem/acceptanceCriteria").asText()).isEqualTo(item.acceptanceCriteria());
        assertThat(result.has("epic")).isFalse();
        verifyNoInteractions(issues, details);
    }

    @Test
    void localEpicWorkReadsItsParentAndSiblingsOnlyWithinItsWorkspace()
            throws Exception
    {
        when(sources.findByTaskId(taskId.toString())).thenReturn(Optional.empty());
        String epicId = "LOCAL:parent";
        WorkItem parent = new WorkItem("parent", "workspace", "Private Epic", WorkItem.Kind.DEVELOP, "Full parent context and acceptance criteria",
                WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.of(), NOW, NOW,
                WorkItem.Priority.NORMAL, ImmutableList.of(), Optional.empty(), 0, ImmutableList.of());
        WorkItem sibling = work("sibling", "First connector", WorkItem.State.DONE, 0, epicId, "First connector verified", ImmutableList.of(), ImmutableList.of());
        WorkItem current = work("current", "Second connector", WorkItem.State.WORKING, 1, epicId, "Second connector verified", ImmutableList.of("sibling"), ImmutableList.of());
        when(workItems.find("parent")).thenReturn(Optional.of(parent));
        when(workItems.findByIdentity(WorkItemLink.taskIdentity(taskId.toString()))).thenReturn(Optional.of(current));
        when(workItems.list("workspace")).thenReturn(ImmutableList.of(parent, current, sibling));
        when(epics.find(epicId)).thenReturn(Optional.of(Epic.of(epicId, "workspace")));

        IssueContextBridge bridge = resolver.resolve(taskId.toString()).orElseThrow();
        assertThat(bridge.instruction()).contains(IssueContextBridge.WORK_INSTRUCTION, IssueContextBridge.EPIC_INSTRUCTION);
        JsonNode context = mapper.readTree(bridge.read().get());
        assertThat(context.at("/workItem/id").asText()).isEqualTo(current.id());
        assertThat(context.at("/workItem/acceptanceCriteria").asText()).isEqualTo(current.acceptanceCriteria());
        assertThat(context.at("/epic/status").asText()).isEqualTo("local");
        assertThat(context.at("/epic/reference").asText()).isEqualTo("Local");
        assertThat(context.at("/epic/url").asText()).isEmpty();
        assertThat(context.at("/epic/title").asText()).isEqualTo(parent.title());
        assertThat(context.at("/epic/description").asText()).isEqualTo(parent.acceptanceCriteria());
        assertThat(context.at("/epic/children")).isEmpty();
        assertThat(context.at("/epic/work")).extracting(item -> item.path("title").asText()).containsExactly(sibling.title(), current.title());
        assertThat(context.at("/epic/currentWork/doneWhen").asText()).isEqualTo(current.acceptanceCriteria());
        assertThat(context.at("/epic/currentWork/waitsOn/0").asText()).isEqualTo(sibling.title());

        WorkItem otherWorkspace = new WorkItem("parent", "other", "Private elsewhere", parent.kind(), "Other workspace context", parent.completionRule(),
                parent.state(), 0, ImmutableList.of(), ImmutableList.of(), NOW, NOW, parent.priority(), ImmutableList.of(), Optional.empty(), 0, parent.contextLinks());
        when(workItems.find("parent")).thenReturn(Optional.of(otherWorkspace));
        JsonNode excluded = mapper.readTree(bridge.read().get());
        assertThat(excluded.at("/epic/status").asText()).isEqualTo("missing");
        assertThat(excluded.at("/epic/title").isMissingNode()).isTrue();
        assertThat(excluded.at("/epic/description").isMissingNode()).isTrue();
        assertThat(excluded.at("/epic/work")).hasSize(2);
        verifyNoInteractions(issues, details, jira);
    }

    private static WorkItem work(String id, String title, WorkItem.State state, long epicRank, String epicId, String criteria, List<String> blockedBy, List<WorkItemLink> links)
    {
        return new WorkItem(id, "workspace", title, WorkItem.Kind.DEVELOP, criteria, WorkItem.CompletionRule.MERGED_DELIVERY, state, 0, links,
                ImmutableList.of(), NOW, NOW, WorkItem.Priority.NORMAL, blockedBy, Optional.of(epicId), epicRank, ImmutableList.of());
    }

    @Test
    void allAssignmentSourcesReadDirectContextLinkSummariesWithoutExpandingTargets()
            throws Exception
    {
        String targetId = "ISSUE:github.com:900";
        WorkItem item = new WorkItem("local", "workspace", "Private repair", WorkItem.Kind.DEVELOP, "Retained task criteria",
                WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.WORKING, 0, ImmutableList.of(), ImmutableList.of(), NOW, NOW,
                WorkItem.Priority.NORMAL, ImmutableList.of(), Optional.empty(), 0, ImmutableList.of(new WorkItemContextLink(targetId, "Linked background")));
        when(workItems.findByIdentity(WorkItemLink.taskIdentity(taskId.toString()))).thenReturn(Optional.of(item));
        when(catalog.contextLinks("workspace", "local")).thenReturn(ImmutableList.of(
                new WorkItemContextView(targetId, "resolved-local", UnifiedWorkItem.Source.GITHUB, "Linked background", "Open", true, Optional.empty())));
        for (String source : ImmutableList.of("LOCAL", "GITHUB", "JIRA")) {
            when(sources.findByTaskId(taskId.toString())).thenReturn(source.equals("GITHUB")
                    ? Optional.of(new IssueTask(taskId.toString(), "workspace", SOURCE, Optional.empty(), NOW)) : Optional.empty());
            JiraIssueSource jiraSource = new JiraIssueSource("connection", "cloud", "10001", "APP-7", "https://company.atlassian.net/browse/APP-7",
                    "Jira assignment", "Jira body", NOW);
            when(jiraSources.findByTaskId(taskId.toString())).thenReturn(source.equals("JIRA")
                    ? Optional.of(new JiraIssueTask(taskId.toString(), "workspace", jiraSource, "Implement Jira scope", NOW)) : Optional.empty());
            if (source.equals("JIRA")) {
                when(jira.issue("connection", "cloud", "10001")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
            }
            IssueContextBridge bridge = resolver.resolve(taskId.toString()).orElseThrow();
            assertThat(bridge.instruction()).contains("work_items READ", "do not assign linked work");
            JsonNode context = mapper.readTree(bridge.read().get());
            assertThat(context.at("/workItem/id").asText()).isEqualTo("local");
            assertThat(context.at("/workItem/contextLinks/0/targetId").asText()).isEqualTo(targetId);
            assertThat(context.at("/contextLinks/0/id").asText()).isEqualTo("resolved-local");
            assertThat(context.at("/contextLinks/0/title").asText()).isEqualTo("Linked background");
            assertThat(context.at("/contextLinks/0/status").asText()).isEqualTo("Open");
            assertThat(context.at("/contextLinks/0/description").isMissingNode()).isTrue();
            assertThat(context.at("/contextLinks/0/contextLinks").isMissingNode()).isTrue();
        }
        verify(catalog, never()).read(anyString(), anyString());
    }

    @Test
    void changedWorkspaceUpstreamCannotSelectAnUnrelatedCachedIssue()
            throws Exception
    {
        Workspace changed = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(changed.monitoredRepository()).thenReturn(RepoRef.of("different", "repo"));
        when(workspaces.findById("workspace")).thenReturn(Optional.of(changed));
        JsonNode context = mapper.readTree(resolver.resolve(taskId.toString()).orElseThrow().read().get());
        assertThat(context.at("/acceptedIssue/repo").asText()).isEqualTo("upstream/repo");
        assertThat(context.at("/cachedMetadata/status").asText()).isEqualTo("missing");
        verifyNoInteractions(issues, details);
    }
}
