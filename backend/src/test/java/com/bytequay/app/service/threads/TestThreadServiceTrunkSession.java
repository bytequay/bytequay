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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.WorkspaceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the trunk-altitude session routing in
 * {@link ThreadService}. A 0-task (planning) thread has no task to
 * build a task-mode CLI agent from, so {@code sessionOrThrow} must
 * route to the trunk-scope agent — otherwise every session-scoped op
 * at the trunk (subscribe, interrupt, the permission-prompt / budget
 * path behind gated MCP tools like list_skills) throws
 * "thread … has no task; cannot spawn CLI agent".
 */
@SpringBootTest
class TestThreadServiceTrunkSession
{
    @Autowired
    private ThreadService threads;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadRegistry registry;
    @Autowired
    private WatchedRepoStore watchedRepos;
    @Autowired
    private WorkspaceStore workspaces;
    @MockitoBean
    private WorktreeService worktrees;

    @TempDir
    private Path cloneRoot;

    @BeforeEach
    void linkVerifiedWorkspaceClone()
    {
        for (WatchedRepo repo : watchedRepos.findAll()) {
            watchedRepos.remove(repo.owner(), repo.repo());
        }
        watchedRepos.add("octocat", "auto-fix-fixture");
        watchedRepos.setLocalClonePath(
                "octocat", "auto-fix-fixture", cloneRoot.toString());
        workspaces.addRepo(new WorkspaceRepo(
                "ws-default", "octocat/auto-fix-fixture",
                "main", false, Instant.now()));
        when(worktrees.refreshPlanningWorktree(
                eq(cloneRoot.toAbsolutePath().normalize()), any()))
                .thenAnswer(invocation -> Optional.of(
                        new WorktreeService.PlanningSync(
                                cloneRoot,
                                "origin/main",
                                "fixture-base")));
    }

    @Test
    void subscribeAtTrunkBuildsTheTrunkAgentNotTheTaskAgent()
    {
        String threadId = newTrunkThread();

        // Before the fix this threw "has no task; cannot spawn CLI
        // agent" — sessionOrThrow always tried the task-mode builder.
        Runnable unsubscribe = threads.subscribe(threadId, event -> {});

        assertThat(unsubscribe).isNotNull();
        // The trunk-scope session is the one that got built; the
        // task-scope map stays empty for a 0-task thread.
        assertThat(registry.findTrunk(threadId)).isPresent();
        assertThat(registry.find(threadId)).isEmpty();

        unsubscribe.run();
        registry.evictTrunk(threadId);
    }

    @Test
    void resumeErroredThreadBuildsTheTrunkAgentAndDoesNotResumeLatestTask()
    {
        String threadId = UUID.randomUUID().toString();
        String taskId = threadId + ".task";
        Instant now = Instant.now();
        threadStore.saveThread(new Thread(
                threadId,
                ThreadKind.CLI_AGENT,
                /* provider */ "claude-code",
                /* agentSessionId */ "trunk-session",
                "Errored task resume fixture",
                ThreadStatus.ERRORED,
                /* model */ "test",
                0L, 0L, 0L,
                now, now, now, "limit hit",
                ThreadFlow.BUILD,
                /* workspaceId */ "ws-default",
                /* workModel */ null,
                /* activeTask */ null));
        taskStore.saveTask(new Task(
                taskId, threadId, 1L, TaskStatus.ERRORED,
                "dev/resume-latest-task", /* worktreePath */ null,
                /* baseBranch */ "main", System.getProperty("java.io.tmpdir"),
                /* processPid */ null, /* logPath */ null,
                /* prNumber */ null, /* prState */ null, /* ciState */ null,
                "DEVELOP", /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                0L, 0L, 0L, "task-session",
                now, now, "limit hit",
                /* name */ null, /* roleSkill */ null, /* workModel */ null));

        threads.resume(threadId);

        Thread resumedThread = threadStore.findThreadById(threadId).orElseThrow();
        Task untouchedTask = taskStore.findTaskById(taskId).orElseThrow();
        assertThat(resumedThread.status()).isEqualTo(ThreadStatus.IDLE);
        assertThat(resumedThread.endedAt()).isNull();
        assertThat(resumedThread.errorMessage()).isNull();
        assertThat(untouchedTask.status()).isEqualTo(TaskStatus.ERRORED);
        assertThat(untouchedTask.endedAt()).isNotNull();
        assertThat(untouchedTask.errorMessage()).isEqualTo("limit hit");
        assertThat(registry.find(threadId)).isEmpty();
        assertThat(registry.findTrunk(threadId)).isPresent();

        registry.evictTrunk(threadId);
    }

    private String newTrunkThread()
    {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Thread thread = new Thread(
                id,
                ThreadKind.CLI_AGENT,
                /* provider */ "claude-code",
                /* agentSessionId */ null,
                "Trunk session fixture",
                ThreadStatus.IDLE,
                /* model */ "test",
                0L, 0L, 0L,
                now, now, null, null,
                ThreadFlow.BUILD,
                /* workspaceId */ "ws-default",
                /* workModel */ null,
                /* activeTask */ null);
        threadStore.saveThread(thread);
        return id;
    }
}
