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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadCheckpointStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.ShellRunner;
import com.bytequay.app.service.local.TestRunnerDetector;
import com.bytequay.app.service.threads.TaskQueueMaterialiser;
import com.bytequay.app.service.threads.TaskQueueScheduler;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@code create_task}'s watched-repo resolution. GitHub
 * owner/name slugs are case-insensitive (trino/Trino, spark/Spark are the same
 * repo), so the tool resolves the watched repo the same way — a slug whose case
 * doesn't match must still cut the task, not bounce off a "repo not in watched
 * repos" denial. The full task-creation machinery is exercised by the
 * integration suite; here we stop at the resolution branch.
 */
class TestAgentToolHandlersCreateTask
{
    private static final String THREAD_ID = "thread-1";
    private static final Instant NOW = Instant.parse("2026-06-19T00:00:00Z");

    private final TaskStore taskStore = mock(TaskStore.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final WatchedRepoStore watchedRepos = mock(WatchedRepoStore.class);

    private AgentToolHandlers handlers;

    @BeforeEach
    void setUp()
    {
        handlers = new AgentToolHandlers(
                taskStore,
                mock(PullRequestStore.class),
                threadStore,
                mock(WorkspaceService.class),
                mock(AgentToolRegistry.class),
                mock(SkillTools.class),
                mock(ThreadCheckpointStore.class),
                mock(TestRunnerDetector.class),
                mock(ShellRunner.class),
                watchedRepos,
                mock(ThreadService.class),
                mock(TaskQueueMaterialiser.class),
                mock(TaskQueueScheduler.class),
                new ObjectMapper());

        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(trunkThread()));
        when(taskStore.findActiveTaskForThread(THREAD_ID)).thenReturn(Optional.empty());
    }

    @Test
    void resolvesTheWatchedRepoIgnoringSlugCase()
    {
        // Watched as "chenjian2664/ByteQuay"; the agent asks with a lowercase
        // slug. A blank clone path lets us prove the lookup matched (we stop on
        // the clone-path check, the step AFTER resolution) without spinning up
        // a real worktree.
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", /* clonePath */ "")));

        ToolOutcome.Completed result = createTask("chenjian2664/bytequay");

        assertThat(result.isError()).isTrue();
        assertThat(result.text())
                .contains("no local clone path")
                .doesNotContain("not in watched repos");
    }

    @Test
    void stillRejectsAGenuinelyUnwatchedRepo()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));

        ToolOutcome.Completed result = createTask("someone/unrelated");

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("not in watched repos");
    }

    private ToolOutcome.Completed createTask(String repo)
    {
        ToolOutcome outcome = handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(repo, null, null, null, null),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));
        return (ToolOutcome.Completed) outcome;
    }

    private static WatchedRepo watchedRepo(String owner, String repo, String clonePath)
    {
        return new WatchedRepo(1L, owner, repo, 0, clonePath, null, null);
    }

    private static Thread trunkThread()
    {
        return new Thread(
                THREAD_ID, ThreadKind.CLI_AGENT, "codex", /* agentSessionId */ null,
                "Codex trunk", ThreadStatus.IDLE, "gpt-5",
                0L, 0L, 0L,
                NOW, NOW, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
    }
}
