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
package com.bytequay.app.discussion;

import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.runtime.AgentServingGate;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.core.domain.PullRequest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestPullRequestEndWatcher
{
    private static final Instant CREATED = Instant.parse("2026-10-01T00:00:00Z");
    private static final String REPO = "owner/repo";

    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final TaskPullRequestOwnership ownership = mock(TaskPullRequestOwnership.class);
    private final AgentCompletionService completions = mock(AgentCompletionService.class);
    private final AgentServingGate gate = mock(AgentServingGate.class, withSettings().mockMaker(MockMakers.INLINE));
    private final PullRequestTaskWatcher watcher = new PullRequestTaskWatcher(
            tasks,
            ownership,
            completions,
            gate,
            mock(ReviewFixService.class));

    @BeforeEach
    void setUp()
    {
        when(gate.isReady()).thenReturn(true);
    }

    @Test
    void completesATaskOnceEveryPullRequestItOwnsIsMergedOrClosedAndSaysWhy()
    {
        AgentTask merged = task(true);
        AgentTask closed = task(true);
        when(tasks.listLive()).thenReturn(ImmutableList.of(merged, closed));
        when(ownership.pullRequests(merged)).thenReturn(ImmutableList.of(pullRequest(14, "MERGED"), pullRequest(9, "closed")));
        when(ownership.pullRequests(closed)).thenReturn(ImmutableList.of(pullRequest(21, "closed")));

        watcher.pass();

        verify(completions).complete(new CompleteAgentTaskCommand(merged.id(), Optional.of("Completed: pull request #14 was merged on GitHub")));
        verify(completions).complete(new CompleteAgentTaskCommand(closed.id(), Optional.of("Completed: pull request #21 was closed on GitHub")));
    }

    @Test
    void leavesATaskOpenWhileAnyOfItsPullRequestsIsOpenOrItHasNone()
    {
        AgentTask reopened = task(true);
        AgentTask open = task(true);
        AgentTask none = task(true);
        when(tasks.listLive()).thenReturn(ImmutableList.of(reopened, open, none));
        when(ownership.pullRequests(reopened)).thenReturn(ImmutableList.of(pullRequest(15, "open"), pullRequest(14, "closed")));
        when(ownership.pullRequests(open)).thenReturn(ImmutableList.of(pullRequest(16, "OPEN")));
        when(ownership.pullRequests(none)).thenReturn(ImmutableList.of());

        watcher.pass();

        verify(completions, never()).complete(any());
    }

    @Test
    void leavesWorkflowAndArchivedTasksAlone()
    {
        AgentTask workflow = task(false);
        AgentTask archived = task(true).withArchived(true, CREATED);
        when(tasks.listLive()).thenReturn(ImmutableList.of(workflow, archived));

        watcher.pass();

        verifyNoInteractions(ownership, completions);
    }

    @Test
    void aTaskThatCannotCompleteYetStaysOpenAndTheOthersStillComplete()
    {
        AgentTask busy = task(true);
        AgentTask done = task(true);
        when(tasks.listLive()).thenReturn(ImmutableList.of(busy, done));
        when(ownership.pullRequests(any())).thenReturn(ImmutableList.of(pullRequest(30, "merged")));
        doThrow(new AgentConflictException("Task worktree has tracked, staged, or untracked changes"))
                .when(completions).complete(new CompleteAgentTaskCommand(busy.id(), Optional.of("Completed: pull request #30 was merged on GitHub")));

        watcher.pass();

        verify(completions).complete(new CompleteAgentTaskCommand(done.id(), Optional.of("Completed: pull request #30 was merged on GitHub")));
    }

    @Test
    void doesNothingBeforeTheAppServes()
    {
        when(gate.isReady()).thenReturn(false);

        watcher.pass();

        verifyNoInteractions(tasks, ownership, completions);
    }

    private static AgentTask task(boolean interactive)
    {
        TaskId id = TaskId.random();
        TaskWorktree worktree = new TaskWorktree(id, "w1", new RepositoryIdentity("owner", "repo"), Path.of("/managed/worktrees", id.toString()), "bytequay/task/" + id,
                "a".repeat(40));
        return created(id, interactive, "Task", TitleSource.USER, AgentLaneId.CLAUDE_CLI, worktree, AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                CREATED);
    }

    private static PullRequest pullRequest(int number, String state)
    {
        return new PullRequest(number, REPO, number, "Task", "me", "https://github.com/owner/repo/pull/" + number, CREATED, CREATED, PullRequest.Origin.AUTHORED,
                ImmutableList.of(), ImmutableMap.of(), false, null, null, null, ImmutableList.of(), null, 1, 1, 0, state, null, null, null, null, CREATED,
                "branch", null, null, null, null);
    }
}
