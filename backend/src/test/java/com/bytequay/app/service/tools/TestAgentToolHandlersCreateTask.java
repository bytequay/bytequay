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

import com.bytequay.app.domain.BacklogItem;
import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.Reactions;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadCheckpointStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.backlog.BacklogService;
import com.bytequay.app.service.local.ShellRunner;
import com.bytequay.app.service.local.TestRunnerDetector;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.threads.WorktreeService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private final ThreadService threads = mock(ThreadService.class);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final WorktreeService worktreeService = mock(WorktreeService.class);
    private final BacklogService backlog = mock(BacklogService.class);
    private final RepoService repoService = mock(RepoService.class);
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    private AgentToolHandlers handlers;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void setUp()
    {
        handlers = new AgentToolHandlers(
                taskStore,
                mock(PullRequestStore.class),
                threadStore,
                workspaces,
                mock(AgentToolRegistry.class),
                mock(SkillTools.class),
                mock(ThreadCheckpointStore.class),
                mock(TestRunnerDetector.class),
                mock(ShellRunner.class),
                watchedRepos,
                threads,
                worktreeService,
                mapper,
                backlog,
                repoService);

        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(trunkThread()));
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

    @Test
    void readsTheWorkspaceRepositoryWithoutExposingItsClonePath()
            throws Exception
    {
        Path clone = Files.createDirectories(tempDir.resolve("widgets"));
        when(workspaces.listRepos("ws-default")).thenReturn(List.of(
                new WorkspaceRepo("ws-default", "acme/widgets", "main", false, NOW)));
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("other", "repo", "/tmp/other"),
                watchedRepo("acme", "widgets", clone.toString())));

        ToolOutcome.Completed result = (ToolOutcome.Completed) handlers.readCurrentRepository(
                new AgentToolHandlers.ReadCurrentRepositoryArgs(),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        assertThat(result.isError()).isFalse();
        assertThat(mapper.readTree(result.text()).path("repo").asText())
                .isEqualTo("acme/widgets");
        assertThat(result.text()).doesNotContain(clone.toString());
    }

    @Test
    void readsFreshIssueContextFromTheCallingTrunksSoleRepository()
            throws Exception
    {
        when(workspaces.listRepos("ws-default")).thenReturn(List.of(
                new WorkspaceRepo(
                        "ws-default", "acme/widgets", "main", false, NOW)));
        IssueDetail fresh = new IssueDetail(
                482L,
                482,
                "Fix workspace routing",
                "Fresh body from GitHub",
                "jack",
                null,
                "open",
                "https://github.com/acme/widgets/issues/482",
                NOW,
                NOW,
                null,
                List.of(new IssueDetail.Label("bug", "ff0000")),
                List.of(),
                null,
                List.of(new IssueDetail.Comment(
                        1L,
                        "reviewer",
                        null,
                        "Fresh comment",
                        NOW,
                        Reactions.EMPTY)),
                List.of(),
                false);
        when(repoService.getIssueDetail("acme", "widgets", 482))
                .thenReturn(fresh);

        ToolOutcome.Completed result =
                (ToolOutcome.Completed) handlers.readIssue(
                        new AgentToolHandlers.ReadIssueArgs(482),
                        new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        assertThat(result.isError()).isFalse();
        JsonNode payload = mapper.readTree(result.text());
        assertThat(payload.path("title").asText())
                .isEqualTo("Fix workspace routing");
        assertThat(payload.path("body").asText())
                .isEqualTo("Fresh body from GitHub");
        assertThat(payload.path("comments").get(0).path("body").asText())
                .isEqualTo("Fresh comment");
        verify(repoService).getIssueDetail("acme", "widgets", 482);
    }

    @Test
    void derivesTaskTitleFromPromptAndForwardsTheTrunkPlan()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));
        when(threads.materialiseTask(eq(THREAD_ID), any())).thenReturn(mock(Task.class));
        JsonNode plan = mapper.createObjectNode().put("status", "finalized");

        handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        "chenjian2664/bytequay", /* title */ null,
                        "Clean duplicate and unused code",
                        null, null, null, plan, null, false),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        ArgumentCaptor<ThreadService.NewTaskRequest> req =
                ArgumentCaptor.forClass(ThreadService.NewTaskRequest.class);
        verify(threads).materialiseTask(eq(THREAD_ID), req.capture());
        // No explicit title → derived from the prompt's first sentence
        // (→ branch dev/clean-…), not the thread title.
        assertThat(req.getValue().title()).isEqualTo("Clean duplicate and unused code");
        assertThat(req.getValue().trunkPlan()).isSameAs(plan);
    }

    @Test
    void prefersTheAgentSuppliedTitleOverThePrompt()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));
        when(threads.materialiseTask(eq(THREAD_ID), any())).thenReturn(mock(Task.class));

        handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        "chenjian2664/bytequay", "Clean up backend exception handling",
                        "Clean up a few backend exception-handling spots. Scope is limited to X.",
                        null, null, null, null, null, false),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        ArgumentCaptor<ThreadService.NewTaskRequest> req =
                ArgumentCaptor.forClass(ThreadService.NewTaskRequest.class);
        verify(threads).materialiseTask(eq(THREAD_ID), req.capture());
        assertThat(req.getValue().title()).isEqualTo("Clean up backend exception handling");
    }

    @Test
    void capsAVerboseAgentTitleAtAWordBoundary()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));
        when(threads.materialiseTask(eq(THREAD_ID), any())).thenReturn(mock(Task.class));

        handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        "chenjian2664/bytequay",
                        "De-duplicate the two identical keyset-pagination recovery loops in "
                                + "AgentScheduler into one private recoverTurns(status, cursor)",
                        null, null, null, null, null, null, false),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        ArgumentCaptor<ThreadService.NewTaskRequest> req =
                ArgumentCaptor.forClass(ThreadService.NewTaskRequest.class);
        verify(threads).materialiseTask(eq(THREAD_ID), req.capture());
        // 12 words at a boundary + ellipsis — not a mid-token cut at "recoverTurns(status,".
        assertThat(req.getValue().title()).isEqualTo(
                "De-duplicate the two identical keyset-pagination recovery loops in "
                        + "AgentScheduler into one private…");
    }

    @Test
    void fallbackTitleCutsAtTheFirstSentenceNotMidThought()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));
        when(threads.materialiseTask(eq(THREAD_ID), any())).thenReturn(mock(Task.class));

        handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        "chenjian2664/bytequay", /* title */ null,
                        "Clean up a few backend exception-handling spots. Scope is limited to X.",
                        null, null, null, null, null, false),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        ArgumentCaptor<ThreadService.NewTaskRequest> req =
                ArgumentCaptor.forClass(ThreadService.NewTaskRequest.class);
        verify(threads).materialiseTask(eq(THREAD_ID), req.capture());
        // Cut at the sentence, not a hard char slice into "Scope is".
        assertThat(req.getValue().title()).isEqualTo("Clean up a few backend exception-handling spots.");
    }

    @Test
    void resolvesTheBacklogItemWhenIdIsSupplied()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));
        Task created = mock(Task.class);
        when(created.id()).thenReturn("task-42");
        when(threads.materialiseTask(eq(THREAD_ID), any())).thenReturn(created);

        handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        "chenjian2664/bytequay", null, "Fix the thing", null, null, null, null, "bl-1", false),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        verify(backlog).resolve("bl-1", "task-42");
    }

    @Test
    void doesNotTouchTheBacklogWhenNoIdIsSupplied()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));
        when(threads.materialiseTask(eq(THREAD_ID), any())).thenReturn(mock(Task.class));

        handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        "chenjian2664/bytequay", null, "Fix the thing", null, null, null, null, null, false),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        verify(backlog, never()).resolve(any(), any());
    }

    @Test
    void doesNotLinkASoleBacklogExplorationWithoutMatchingTaskContext()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));
        Task created = mock(Task.class);
        when(created.id()).thenReturn("task-42");
        when(threads.materialiseTask(eq(THREAD_ID), any())).thenReturn(created);
        when(backlog.list(THREAD_ID)).thenReturn(List.of(
                backlogItem("phase-2", "Phase 2 — PR ranking and evidence bundles")
                        .markInProgress(NOW),
                backlogItem("phase-3", "Phase 3 — canonical knowledge")));

        handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        "chenjian2664/bytequay", "Fix an unrelated notification race",
                        null, null, null, null, null, null, false),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        verify(threads).materialiseTask(eq(THREAD_ID), any());
        verify(backlog, never()).resolve(any(), any());
    }

    @Test
    void requiresConfirmationForOneMatchingEvidenceChannelBeforeCuttingTask()
            throws Exception
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));
        when(backlog.list(THREAD_ID)).thenReturn(List.of(
                backlogItem("phase-2", "Phase 2 — PR ranking and evidence bundles")
                        .markInProgress(NOW),
                backlogItem("phase-3", "Phase 3 — canonical knowledge")));

        ToolOutcome.Completed result = (ToolOutcome.Completed) handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        "chenjian2664/bytequay", "Add PR ranking and evidence bundles",
                        null, null, null, null, null, null, false),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        JsonNode payload = mapper.readTree(result.text());
        assertThat(result.isError()).isFalse();
        assertThat(payload.path("confirmation_required").asBoolean()).isTrue();
        assertThat(payload.path("candidates").get(0).path("id").asText())
                .isEqualTo("phase-2");
        assertThat(payload.path("candidates").get(0).path("title").asText())
                .isEqualTo("Phase 2 — PR ranking and evidence bundles");
        assertThat(payload.path("instruction").asText())
                .contains(
                        "ask_user_question",
                        "Start and link: Phase 2 — PR ranking and evidence bundles",
                        "backlog_item_id=phase-2",
                        "Start without backlog",
                        "skip_backlog_link=true");
        verify(threads, never()).materialiseTask(any(), any());
        verify(backlog, never()).resolve(any(), any());
    }

    @Test
    void linksWhenTwoIndependentEvidenceChannelsAgree()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));
        Task created = mock(Task.class);
        when(created.id()).thenReturn("task-42");
        when(threads.materialiseTask(eq(THREAD_ID), any())).thenReturn(created);
        when(backlog.list(THREAD_ID)).thenReturn(List.of(
                backlogItem("phase-2", "Project Intelligence Phase 2 — PR ranking and evidence bundles"),
                backlogItem("phase-3", "Project Intelligence Phase 3 — canonical knowledge")));

        handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        "chenjian2664/bytequay", "Add PR ranking and evidence bundles",
                        "Implement PR ranking and evidence bundles.",
                        null, null, null,
                        mapper.createObjectNode().put(
                                "deferred", "Project Intelligence Phase 3 — canonical knowledge"),
                        null, false),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        verify(backlog).resolve("phase-2", "task-42");
    }

    @Test
    void linksWhenTaskTitleAndImmediatelyPrecedingApprovalAgree()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));
        Task created = mock(Task.class);
        when(created.id()).thenReturn("task-42");
        when(threads.materialiseTask(eq(THREAD_ID), any())).thenReturn(created);
        when(backlog.list(THREAD_ID)).thenReturn(List.of(
                backlogItem("phase-2", "Project Intelligence Phase 2 — PR ranking"),
                backlogItem("phase-3", "Project Intelligence Phase 3 — canonical knowledge")));
        when(threadStore.listRecentMessages(THREAD_ID, 24)).thenReturn(List.of(
                textMessage(1, null, "user", "What's next?"),
                toolCallMessage(2, null, "ask_user_question", mapper.createObjectNode()
                        .put("question", "Cut this as the Phase 2 task?")
                        .put("context", "Phase 3 canonical knowledge remains deferred.")),
                textMessage(3, null, "assistant", "The approval question is ready."),
                textMessage(4, null, "user", "go ahead")));

        handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        "chenjian2664/bytequay", "Implement Phase 2 PR ranking",
                        "Proceed with the agreed plan.", null, null, null,
                        mapper.createObjectNode().put("status", "finalized"), null, false),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        verify(backlog).resolve("phase-2", "task-42");
    }

    @Test
    void requiresConfirmationWhenEvidenceChannelsConflict()
            throws Exception
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));
        when(backlog.list(THREAD_ID)).thenReturn(List.of(
                backlogItem("phase-2", "Project Intelligence Phase 2 — PR ranking and evidence bundles"),
                backlogItem("phase-3", "Project Intelligence Phase 3 — extraction and canonical knowledge")));

        ToolOutcome.Completed result = (ToolOutcome.Completed) handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        "chenjian2664/bytequay", "Implement Phase 2 PR ranking",
                        "Implement Phase 3 extraction and canonical knowledge.",
                        null, null, null, null, null, false),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        JsonNode payload = mapper.readTree(result.text());
        assertThat(payload.path("confirmation_required").asBoolean()).isTrue();
        assertThat(payload.path("candidates").findValuesAsText("id"))
                .containsExactly("phase-2", "phase-3");
        assertThat(payload.path("reason").asText()).contains("conflicting");
        verify(threads, never()).materialiseTask(any(), any());
    }

    @Test
    void skipsBacklogInferenceAfterUserChoosesStartWithoutBacklog()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));
        Task created = mock(Task.class);
        when(created.id()).thenReturn("task-42");
        when(threads.materialiseTask(eq(THREAD_ID), any())).thenReturn(created);

        handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        "chenjian2664/bytequay", "Implement Phase 2 PR ranking",
                        null, null, null, null, null, null, true),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        verify(threads).materialiseTask(eq(THREAD_ID), any());
        verify(backlog, never()).list(any());
        verify(backlog, never()).resolve(any(), any());
    }

    @Test
    void ignoresTaskScopedAndOlderTrunkContextWhenInferringABacklog()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));
        Task created = mock(Task.class);
        when(created.id()).thenReturn("task-42");
        when(threads.materialiseTask(eq(THREAD_ID), any())).thenReturn(created);
        when(backlog.list(THREAD_ID)).thenReturn(List.of(
                backlogItem("phase-2", "Project Intelligence Phase 2 — PR ranking")));
        when(threadStore.listRecentMessages(THREAD_ID, 24)).thenReturn(List.of(
                textMessage(1, null, "user", "Discuss Phase 2 next."),
                textMessage(2, null, "assistant", "Phase 2 is the next roadmap item."),
                textMessage(3, null, "user", "Instead, investigate the notification race."),
                textMessage(4, "task-old", "assistant", "Implement Phase 2."),
                textMessage(5, null, "assistant", "Cut the notification fix as a task?"),
                textMessage(6, null, "user", "go ahead")));

        handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        "chenjian2664/bytequay", "Fix the notification race",
                        "Implement the approved notification fix.",
                        null, null, null, null, null, false),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        verify(backlog, never()).resolve(any(), any());
    }

    @Test
    void aDirectApprovalQuestionIsDecisiveWithoutTwoOtherSignals()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));
        Task created = mock(Task.class);
        when(created.id()).thenReturn("task-42");
        when(threads.materialiseTask(eq(THREAD_ID), any())).thenReturn(created);
        when(backlog.list(THREAD_ID)).thenReturn(List.of(
                backlogItem("phase-2", "Project Intelligence Phase 2 — PR ranking"),
                backlogItem("phase-3", "Project Intelligence Phase 3 — canonical knowledge")));
        when(threadStore.listRecentMessages(THREAD_ID, 24)).thenReturn(List.of(
                textMessage(1, null, "user", "What's next?"),
                textMessage(2, null, "assistant", "Cut this as the Phase 2 task?"),
                textMessage(3, null, "user", "go ahead")));

        handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        "chenjian2664/bytequay", "Implement the approved roadmap step",
                        "Proceed with the approved roadmap step.", null, null, null,
                        mapper.createObjectNode().put("status", "finalized"), null, false),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        verify(threads).materialiseTask(eq(THREAD_ID), any());
        verify(backlog).resolve("phase-2", "task-42");
    }

    @Test
    void aRedirectingReplyDoesNotApplyThePreviousBacklogApproval()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));
        Task created = mock(Task.class);
        when(created.id()).thenReturn("task-42");
        when(threads.materialiseTask(eq(THREAD_ID), any())).thenReturn(created);
        when(backlog.list(THREAD_ID)).thenReturn(List.of(
                backlogItem("phase-2", "Project Intelligence Phase 2 — PR ranking")));
        when(threadStore.listRecentMessages(THREAD_ID, 24)).thenReturn(List.of(
                textMessage(1, null, "user", "What's next?"),
                textMessage(2, null, "assistant", "Cut this as the Phase 2 task?"),
                textMessage(3, null, "user", "No — do the notification fix instead")));

        handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        "chenjian2664/bytequay", "Fix the notification race",
                        "Proceed with the notification fix.", null, null, null,
                        null, null, false),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        verify(threads).materialiseTask(eq(THREAD_ID), any());
        verify(backlog, never()).resolve(any(), any());
    }

    @Test
    void explicitBacklogIdWinsEvenWhenSkipIsAlsoTrue()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));
        Task created = mock(Task.class);
        when(created.id()).thenReturn("task-42");
        when(threads.materialiseTask(eq(THREAD_ID), any())).thenReturn(created);

        handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        "chenjian2664/bytequay", "Add PR ranking",
                        null, null, null, null, null, "phase-2", true),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        verify(backlog).resolve("phase-2", "task-42");
        verify(backlog, never()).list(any());
    }

    @Test
    void aBadBacklogIdDoesNotFailTaskCreation()
    {
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", "/tmp/clone")));
        Task created = mock(Task.class);
        when(created.id()).thenReturn("task-42");
        when(threads.materialiseTask(eq(THREAD_ID), any())).thenReturn(created);
        when(backlog.resolve(any(), any())).thenThrow(new IllegalArgumentException("unknown backlog item"));

        ToolOutcome outcome = handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        "chenjian2664/bytequay", null, "Fix the thing", null, null, null, null, "stale-id", false),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
    }

    @Test
    void syncRepoRefreshesPlanningWorktreeAndReportsTheBaseRef()
            throws Exception
    {
        Path clone = Files.createDirectories(tempDir.resolve("clone"));
        when(watchedRepos.findAll()).thenReturn(List.of(
                watchedRepo("chenjian2664", "ByteQuay", clone.toString())));
        when(workspaces.listRepos("ws-default")).thenReturn(List.of(
                new WorkspaceRepo("ws-default", "chenjian2664/ByteQuay", "main", false, NOW)));
        when(worktreeService.refreshPlanningWorktree(
                clone.toAbsolutePath().normalize(), THREAD_ID))
                .thenReturn(Optional.of(new WorktreeService.PlanningSync(
                        clone.resolve(".worktrees/_planning/thread-1"),
                        "origin/main", "abc123")));

        ToolOutcome.Completed result = (ToolOutcome.Completed) handlers.syncRepo(
                new AgentToolHandlers.SyncRepoArgs(),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("origin/main");
        verify(worktreeService).refreshPlanningWorktree(
                clone.toAbsolutePath().normalize(), THREAD_ID);
        verify(threadStore).setPlanningSnapshot(THREAD_ID,
                new ThreadStore.PlanningSnapshot(
                        clone.toAbsolutePath().normalize().toString(), "abc123"));
    }

    @Test
    void syncRepoReportsWhenNoCloneIsLinkedAndDoesNotTouchGit()
    {
        when(watchedRepos.findAll()).thenReturn(List.of());

        ToolOutcome.Completed result = (ToolOutcome.Completed) handlers.syncRepo(
                new AgentToolHandlers.SyncRepoArgs(),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));

        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("nothing to sync");
        verify(worktreeService, never()).refreshPlanningWorktree(any(), any());
    }

    private ToolOutcome.Completed createTask(String repo)
    {
        ToolOutcome outcome = handlers.createTask(
                new AgentToolHandlers.CreateTaskArgs(
                        repo, null, null, null, null, null, null, null, false),
                new ToolCall(THREAD_ID, null, AgentRole.TRUNK));
        return (ToolOutcome.Completed) outcome;
    }

    private static WatchedRepo watchedRepo(String owner, String repo, String clonePath)
    {
        return new WatchedRepo(1L, owner, repo, 0, clonePath, null, null);
    }

    private static BacklogItem backlogItem(String id, String title)
    {
        return BacklogItem.create(
                id,
                THREAD_ID,
                "ws-default",
                title,
                title,
                List.of("project-intelligence"),
                BacklogItem.PRIORITY_MEDIUM,
                BacklogItem.SOURCE_AGENT,
                BacklogItem.CREATED_BY_TRUNK_AGENT,
                NOW,
                List.of());
    }

    private ThreadMessage textMessage(long seq, String taskId, String role, String text)
    {
        return message(seq, taskId, role, "text",
                mapper.createObjectNode().put("text", text));
    }

    private ThreadMessage toolCallMessage(
            long seq, String taskId, String toolName, JsonNode input)
    {
        return message(seq, taskId, "tool", "tool_call",
                mapper.createObjectNode()
                        .put("callId", "call-" + seq)
                        .put("toolName", toolName)
                        .set("input", input));
    }

    private static ThreadMessage message(
            long seq, String taskId, String role, String type, JsonNode content)
    {
        return new ThreadMessage(
                "message-" + seq, THREAD_ID, taskId, seq, role, type,
                content.toString(), null, null, null, null, NOW);
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
