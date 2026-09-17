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
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.RepositoryIssueDetailStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());
    private final IssueContextBridgeResolverImpl resolver = new IssueContextBridgeResolverImpl(tasks, sources, workspaces, issues, details, mapper);
    private final AgentTask task = mock(AgentTask.class, withSettings().mockMaker(MockMakers.INLINE));

    @BeforeEach
    void setUp()
    {
        when(tasks.find(taskId)).thenReturn(Optional.of(task));
        when(task.interactive()).thenReturn(true);
        when(task.state()).thenReturn(TaskState.OPEN);
        when(task.workspaceId()).thenReturn(Optional.of("workspace"));
        when(sources.findByTaskId(taskId.toString())).thenReturn(Optional.of(
                new IssueTask(taskId.toString(), "workspace", SOURCE, Optional.of("Implement selection"), NOW)));
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.monitoredRepository()).thenReturn(RepoRef.of("upstream", "repo"));
        when(workspace.monitoredRepositoryId()).thenReturn(2L);
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace));
    }

    @Test
    void returnsAcceptedDescriptionAndExplicitlyCachedMetadataWithBoundedTimeline()
            throws Exception
    {
        when(issues.find(2, 7)).thenReturn(Optional.of(new RepositoryIssue(70, 7, "Edited title", "New scope", "alice", "", SOURCE.url(),
                ImmutableList.of("bug"), ImmutableList.of("alice"), 25, NOW, NOW.plusSeconds(20))));
        when(details.find(2, 7)).thenReturn(Optional.of(new RepositoryIssueDetail(2, 7,
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
