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
package com.bytequay.app.assistant;

import com.bytequay.agent.core.AgentApproval;
import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.ResolveApprovalCommand;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.app.agenda.AgendaFixtures;
import com.bytequay.app.agent.InteractiveAgentTaskStarter;
import com.bytequay.app.agent.InteractiveTaskStart;
import com.bytequay.app.jira.JiraDtos;
import com.bytequay.app.jira.JiraIssue;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.notices.AppNoticeService;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.AssistantAction;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.domain.AssistantReceipt;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.ServedRef;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.DENY;
import static com.bytequay.core.domain.AssistantReceipt.Fix.BUTTONS;
import static com.bytequay.core.domain.AssistantReceipt.Fix.SETTINGS;
import static com.bytequay.core.domain.AssistantReceipt.Outcome.DONE;
import static com.bytequay.core.domain.AssistantReceipt.Outcome.FAILED;
import static com.bytequay.core.domain.AssistantReceipt.Outcome.REFUSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAssistantActions
{
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-10-17");
    private static final ApprovalId APPROVAL = ApprovalId.random();
    private static final String CARD = "approval:" + APPROVAL;
    private static final String QUESTION_INPUT = "{\"questions\":[{\"question\":\"Where should the snapshot id appear?\",\"header\":\"Place\",\"multiSelect\":false,"
            + "\"options\":[{\"label\":\"Metadata table\"},{\"label\":\"Hidden column\"},{\"label\":\"Both\"}]}]}";
    private static final ServedRef SERVED = new ServedRef("start_options", Optional.empty());

    private final MemoryAssistantStore store = new MemoryAssistantStore();
    private final AssistantSettings permissions = mock(AssistantSettings.class);
    private final AgentApprovalStore approvals = mock(AgentApprovalStore.class);
    private final AgentApprovalService approvalService = mock(AgentApprovalService.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnService turns = mock(AgentTurnService.class);
    private final InteractiveAgentTaskStarter starter = mock(InteractiveAgentTaskStarter.class);
    private final AppNoticeService notices = mock(AppNoticeService.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final WorkItemStore workItems = mock(WorkItemStore.class);
    private final RepositoryIssueStore issues = mock(RepositoryIssueStore.class);
    private final JiraService jira = mock(JiraService.class);
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final AppSettingsService settings = mock(AppSettingsService.class);
    private final ObjectMapper mapper = AgendaFixtures.mapper();
    private final AtomicReference<AssistantPermissions> allowed = new AtomicReference<>(new AssistantPermissions(true, true));
    private final List<AssistantItem> receipts = new ArrayList<>();
    private final AgentTask task = task("Retry Iceberg commit on conflict");
    private final Workspace trino = new Workspace("ws1", "Trino", 1, RepoRef.of("trinodb", "trino"), Optional.empty(), Optional.empty(), 1, RepoRef.of("trinodb", "trino"),
            Workspace.Topology.DIRECT, "master", "https://github.com/trinodb/trino.git", Optional.empty(), Optional.empty(), "/managed/trino", Optional.empty(),
            Optional.empty(), 0, NOW, NOW);
    private AssistantActions actions;

    @BeforeEach
    void setUp()
    {
        when(permissions.permissions()).thenAnswer(call -> allowed.get());
        when(tasks.find(task.id())).thenReturn(Optional.of(task));
        when(approvalService.resolve(any())).thenAnswer(call -> {
            ResolveApprovalCommand command = call.getArgument(0);
            return new ApprovalDecision(command.approvalId(), command.decision(), Optional.empty(), false, command.answers(), NOW, false);
        });
        when(workspaces.findAll()).thenReturn(ImmutableList.of(trino));
        when(workspaces.findById("ws1")).thenReturn(Optional.of(trino));
        when(models.agentModel(AgentModelType.DEVELOPMENT, Optional.of("ws1")))
                .thenReturn(new AgentModelSetting(AgentModelType.DEVELOPMENT, true, "claude", "Claude Code", "opus", "Opus 5.5", Optional.of("high")));
        when(models.get()).thenReturn(new ModelSettingsService.ModelSettings(ImmutableList.of(),
                ImmutableList.of(provider("claude", "Claude Code", AgentProviderStatus.Readiness.READY, "opus", "high"),
                        provider("codex", "Codex", AgentProviderStatus.Readiness.READY, "gpt-6.1-sol", ""),
                        provider("gemini", "Gemini", AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED, "flash", "")),
                ImmutableList.of(), ImmutableList.of(), Optional.empty()));
        when(settings.developmentAutomation()).thenReturn(new DevelopmentAutomation(true, true, false, Optional.empty(), false));
        when(starter.start(any())).thenAnswer(call -> {
            InteractiveTaskStart start = call.getArgument(0);
            return AgentTask.created(start.taskId(), Optional.of(start.workspaceId()), true, "Expose snapshot id in metadata", start.laneId(),
                    new RepositoryIdentity("trinodb", "trino"), Path.of("/tmp/assistant-task"), start.defaults(), NOW);
        });
        when(turns.findTurnByIdempotencyKey(any(), any())).thenAnswer(call -> Optional.of(turn(call.getArgument(0))));
        when(turns.start(any())).thenAnswer(call -> turn(call.<StartAgentTurnCommand>getArgument(0).taskId()));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        actions = new AssistantActions(store, permissions, approvals, approvalService, tasks, turns, starter, notices, reviews, workspaces, workItems, issues, jira, models,
                settings, mapper, clock);
    }

    @Test
    void allowsOnceASandboxedCommandFromTheUsersWords()
            throws Exception
    {
        card(ProviderApprovalRequest.Kind.COMMAND, ImmutableList.of("Bash"), "{\"command\":\"./mvnw test\"}", true);
        AssistantItem message = message("Allow it, please", ImmutableList.of(CARD));

        assertThat(act(message, "{\"op\":\"answer_approval\",\"approval\":\"" + CARD + "\",\"decision\":\"allow_once\",\"words\":\"allow  IT\"}"))
                .isEqualTo("{\"outcome\":\"done\",\"message\":\"Allowed once · Retry Iceberg commit on conflict\"}");

        ArgumentCaptor<ResolveApprovalCommand> resolved = ArgumentCaptor.forClass(ResolveApprovalCommand.class);
        verify(approvalService).resolve(resolved.capture());
        assertThat(resolved.getValue()).isEqualTo(new ResolveApprovalCommand(APPROVAL, ApprovalDecision.Decision.ALLOW, Optional.empty(), false, ImmutableMap.of()));
        AssistantItem receipt = receipts.getLast();
        assertThat(receipt.kind()).isEqualTo(AssistantItem.Kind.RECEIPT);
        assertThat(receipt.receipt().orElseThrow()).isEqualTo(AssistantReceipt.done(AssistantReceipt.Operation.ANSWER_APPROVAL,
                Optional.of(AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", task.id().toString()))));
        assertThat(store.actions(message.id())).singleElement().satisfies(action -> {
            assertThat(action.operation()).isEqualTo(AssistantReceipt.Operation.ANSWER_APPROVAL);
            assertThat(action.target()).isEqualTo(CARD);
            assertThat(action.words()).isEqualTo("allow  IT");
            assertThat(action.outcome()).isEqualTo(DONE);
            assertThat(action.result()).contains("ALLOW");
        });
    }

    @Test
    void refusesWhileItsSwitchIsOff()
    {
        allowed.set(new AssistantPermissions(false, true));
        AssistantItem message = message("start one in trino: add a metadata table", ImmutableList.of());
        act(message, ImmutableMap.of("ws:ws1", SERVED), "{\"op\":\"start_task\",\"workspace\":\"ws:ws1\",\"words\":\"add a metadata table\"}");
        assertRefused("Starting tasks is off", Optional.of(SETTINGS));

        allowed.set(new AssistantPermissions(true, false));
        card(ProviderApprovalRequest.Kind.COMMAND, ImmutableList.of("Bash"), "{}", true);
        act(message("deny it", ImmutableList.of(CARD)), "{\"op\":\"answer_approval\",\"approval\":\"" + CARD + "\",\"decision\":\"deny\",\"words\":\"deny it\"}");
        assertRefused("Answering tasks is off", Optional.of(SETTINGS));
        verify(starter, never()).start(any());
        verify(approvalService, never()).resolve(any());
    }

    @Test
    void refusesWordsThatAreNotTheUsers()
    {
        card(ProviderApprovalRequest.Kind.COMMAND, ImmutableList.of("Bash"), "{}", true);
        act(message("what is this one?", ImmutableList.of(CARD)), "{\"op\":\"answer_approval\",\"approval\":\"" + CARD + "\",\"decision\":\"allow_once\",\"words\":\"allow it\"}");
        assertRefused("Those words aren't in your message", Optional.empty());
        act(message("a", ImmutableList.of(CARD)), "{\"op\":\"answer_approval\",\"approval\":\"" + CARD + "\",\"decision\":\"allow_once\",\"words\":\"a\"}");
        assertRefused("Those words aren't in your message", Optional.empty());
        verify(approvalService, never()).resolve(any());
    }

    @Test
    void refusesWhatWasNotOnScreen()
    {
        card(ProviderApprovalRequest.Kind.COMMAND, ImmutableList.of("Bash"), "{}", true);
        act(message("allow it", ImmutableList.of()), "{\"op\":\"answer_approval\",\"approval\":\"" + CARD + "\",\"decision\":\"allow_once\",\"words\":\"allow it\"}");
        assertRefused("That isn't on screen", Optional.empty());
        act(message("tell it to use the hidden column", ImmutableList.of()),
                "{\"op\":\"reply_to_task\",\"task\":\"task:" + task.id() + "\",\"words\":\"use the hidden column\"}");
        assertRefused("That isn't on screen", Optional.empty());
        act(message("start one in trino: add a metadata table", ImmutableList.of()), "{\"op\":\"start_task\",\"workspace\":\"ws:ws1\",\"words\":\"add a metadata table\"}");
        assertRefused("That isn't on screen", Optional.empty());
        verify(approvalService, never()).resolve(any());
        verify(starter, never()).start(any());
    }

    @Test
    void refusesACardThatExpiredOrWasAnswered()
    {
        AgentApproval waiting = card(ProviderApprovalRequest.Kind.COMMAND, ImmutableList.of("Bash"), "{}", true);
        when(approvals.find(APPROVAL)).thenReturn(Optional.of(waiting.resolve(
                new ApprovalDecision(APPROVAL, ApprovalDecision.Decision.DENY, Optional.empty(), false, ImmutableMap.of(), NOW, true), AgentEventPayload.ApprovalOutcome.EXPIRED)));
        String allow = "{\"op\":\"answer_approval\",\"approval\":\"" + CARD + "\",\"decision\":\"allow_once\",\"words\":\"allow it\"}";
        act(message("allow it", ImmutableList.of(CARD)), allow);
        assertRefused("Expired", Optional.empty());

        when(approvals.find(APPROVAL)).thenReturn(Optional.of(waiting.resolve(
                new ApprovalDecision(APPROVAL, ApprovalDecision.Decision.ALLOW, Optional.empty(), false, ImmutableMap.of(), NOW, false),
                AgentEventPayload.ApprovalOutcome.ALLOWED)));
        act(message("allow it", ImmutableList.of(CARD)), allow);
        assertRefused("Already answered", Optional.empty());
        verify(approvalService, never()).resolve(any());
    }

    @Test
    void sendsToTheButtonsWhatWordsMayNotAnswer()
    {
        String allow = "{\"op\":\"answer_approval\",\"approval\":\"" + CARD + "\",\"decision\":\"allow_once\",\"words\":\"allow it\"}";
        // Claude's shell asking to leave its sandbox, and any Codex command, which asks only to leave Codex's.
        card(ProviderApprovalRequest.Kind.COMMAND, ImmutableList.of("Bash"), "{\"dangerouslyDisableSandbox\":true}", false);
        act(message("allow it", ImmutableList.of(CARD)), allow);
        assertRefused("Use the buttons: this one runs outside the sandbox", Optional.of(BUTTONS));
        card(ProviderApprovalRequest.Kind.COMMAND, ImmutableList.of("commandExecution"), "./gradlew test", false);
        act(message("allow it", ImmutableList.of(CARD)), allow);
        assertRefused("Use the buttons: this one runs outside the sandbox", Optional.of(BUTTONS));
        card(ProviderApprovalRequest.Kind.FILE_CHANGE, ImmutableList.of("Edit"), "{\"file_path\":\"Foo.java\"}", false);
        act(message("allow it", ImmutableList.of(CARD)), allow);
        assertRefused("Use the buttons: words answer only commands", Optional.of(BUTTONS));
        card(ProviderApprovalRequest.Kind.TOOL, ImmutableList.of("AskUserQuestion"), QUESTION_INPUT, false);
        act(message("allow it", ImmutableList.of(CARD)), allow);
        assertRefused("Pick one of the card's options", Optional.of(BUTTONS));
        verify(approvalService, never()).resolve(any());
    }

    @Test
    void answersAQuestionWithAnOfferedOptionOrTheUsersWords()
    {
        card(ProviderApprovalRequest.Kind.TOOL, ImmutableList.of("AskUserQuestion"), QUESTION_INPUT, false);
        act(message("put it in the hidden column", ImmutableList.of(CARD)),
                "{\"op\":\"answer_question\",\"approval\":\"" + CARD + "\",\"answers\":{\"Where should the snapshot id appear?\":\"hidden COLUMN\"},\"words\":\"hidden column\"}");
        assertThat(receipts.getLast().text()).isEqualTo("Answered · Retry Iceberg commit on conflict");
        verify(approvalService).resolve(new ResolveApprovalCommand(APPROVAL, ApprovalDecision.Decision.ALLOW, Optional.empty(), false,
                ImmutableMap.of("Where should the snapshot id appear?", "Hidden column")));

        card(ProviderApprovalRequest.Kind.TOOL, ImmutableList.of("AskUserQuestion"), QUESTION_INPUT, false);
        act(message("put it in the manifest list", ImmutableList.of(CARD)),
                "{\"op\":\"answer_question\",\"approval\":\"" + CARD + "\",\"answers\":{\"Where should the snapshot id appear?\":\"in the manifest list\"},"
                        + "\"words\":\"manifest list\"}");
        verify(approvalService).resolve(new ResolveApprovalCommand(APPROVAL, ApprovalDecision.Decision.ALLOW, Optional.empty(), false,
                ImmutableMap.of("Where should the snapshot id appear?", "in the manifest list")));

        card(ProviderApprovalRequest.Kind.TOOL, ImmutableList.of("AskUserQuestion"), QUESTION_INPUT, false);
        act(message("both of them, ok?", ImmutableList.of(CARD)),
                "{\"op\":\"answer_question\",\"approval\":\"" + CARD + "\",\"answers\":{\"Where should the snapshot id appear?\":\"Elsewhere\"},\"words\":\"both of them\"}");
        assertRefused("Pick one of the card's options", Optional.of(BUTTONS));
        act(message("both of them", ImmutableList.of(CARD)),
                "{\"op\":\"answer_approval\",\"approval\":\"" + CARD + "\",\"decision\":\"allow_once\",\"words\":\"both of them\"}");
        assertRefused("Pick one of the card's options", Optional.of(BUTTONS));
        verify(approvalService, times(2)).resolve(any());
    }

    @Test
    void sendsTheUsersWordsToAnIdleTaskAndRefusesABusyOne()
    {
        AssistantItem message = message("tell it to use the hidden column", ImmutableList.of());
        AgendaTarget opens = AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", task.id().toString());
        Map<String, ServedRef> served = ImmutableMap.of("task:" + task.id(), new ServedRef("tasks", Optional.of(opens)));
        act(message, served, "{\"op\":\"reply_to_task\",\"task\":\"task:" + task.id() + "\",\"words\":\"use the hidden column\"}");
        assertThat(receipts.getLast().text()).isEqualTo("Sent your words to Retry Iceberg commit on conflict");
        ArgumentCaptor<StartAgentTurnCommand> sent = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(turns).start(sent.capture());
        assertThat(sent.getValue().message()).isEqualTo("use the hidden column");

        when(tasks.find(task.id())).thenReturn(Optional.of(task.claimWorktreeMutation(WorktreeMutationClaim.turn(TurnId.random(), NOW), NOW)));
        act(message("and also keep the old key", ImmutableList.of()), served, "{\"op\":\"reply_to_task\",\"task\":\"task:" + task.id() + "\",\"words\":\"keep the old key\"}");
        assertRefused("Task is busy; steer it from the task", Optional.empty());
        verify(turns, times(1)).start(any());
    }

    @Test
    void startsATaskFromTheUsersWordsWithTheSavedAutomation()
    {
        AssistantItem message = message("start one in trino: add a metadata table for snapshot ids", ImmutableList.of());
        act(message, ImmutableMap.of("ws:ws1", SERVED), "{\"op\":\"start_task\",\"workspace\":\"ws:ws1\",\"words\":\"add a metadata table for snapshot ids\"}");

        InteractiveTaskStart start = started();
        assertThat(start.message()).isEqualTo("add a metadata table for snapshot ids");
        assertThat(start.workspaceId()).isEqualTo("ws1");
        assertThat(start.laneId()).isEqualTo(new AgentLaneId("claude"));
        assertThat(start.defaults()).isEqualTo(new AgentTaskDefaults(Optional.of("opus"), Optional.of("high"), AgentPermissionPolicy.AUTO, Optional.empty(),
                Optional.of(new DevelopmentAutomation(true, true, false, Optional.empty(), false))));
        assertThat(start.initialAutomation()).contains(new DevelopmentAutomation(true, true, false, Optional.empty(), false));
        assertThat(start.workItemId()).isEmpty();
        AssistantReceipt receipt = receipts.getLast().receipt().orElseThrow();
        assertThat(receipts.getLast().text()).isEqualTo("Started “Expose snapshot id in metadata”");
        assertThat(receipt.detail()).contains("trinodb/trino · Claude Code Opus 5.5 · Auto draft PR");
        assertThat(receipt.turnId()).isPresent();
        assertThat(receipt.target()).contains(AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", start.taskId().toString()));
        assertThat(store.actions(message.id()).getLast().result()).contains(start.taskId().toString());
    }

    @Test
    void startsAnItemWithItsOwnFirstMessageThenTheUsersNote()
    {
        when(workItems.find("w22")).thenReturn(Optional.of(new WorkItem("w22", "ws1", Optional.empty(), "Expose snapshot id in metadata", WorkItem.Kind.DEVELOP, "",
                WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.of(), NOW, NOW, WorkItem.Priority.HIGH, 0,
                ImmutableList.of(), Optional.empty(), 0)));
        act(message("start the snapshot id one with codex", ImmutableList.of()), ImmutableMap.of("work:w22", SERVED),
                "{\"op\":\"start_task\",\"from\":\"work:w22\",\"agent\":\"codex\",\"words\":\"start the snapshot id one\"}");
        InteractiveTaskStart work = started();
        assertThat(work.message()).isEqualTo("Develop this work item: Expose snapshot id in metadata\n\nCall read_issue_context before starting to read this item's full context "
                + "and its parent Epic, if any. Deliver only this work item.\n\nNote from the user, in their words to the assistant: “start the snapshot id one”");
        assertThat(work.workItemId()).contains("w22");
        assertThat(work.laneId()).isEqualTo(new AgentLaneId("codex"));
        assertThat(work.defaults().model()).contains("gpt-6.1-sol");
        assertThat(work.defaults().effort()).isEmpty();

        when(issues.find(1, 77)).thenReturn(Optional.of(new RepositoryIssue(901, 77, "Snapshot ids in $snapshots", "body", "marlo-t", "", "https://github.com/trinodb/trino/issues/77",
                ImmutableList.of(), ImmutableList.of(), 0, NOW, NOW.minusSeconds(60))));
        act(message("start #77", ImmutableList.of(), Optional.of("issue:trinodb/trino#77")), "{\"op\":\"start_task\",\"from\":\"issue:trinodb/trino#77\",\"words\":\"start #77\"}");
        InteractiveTaskStart issue = started();
        assertThat(issue.message()).isEqualTo("Implement Snapshot ids in $snapshots\n\nNote from the user, in their words to the assistant: “start #77”");
        assertThat(issue.issueSource().orElseThrow().issueId()).isEqualTo(901);
        assertThat(issue.issueSource().orElseThrow().updatedAt()).isEqualTo(NOW.minusSeconds(60));

        JiraIssue ticket = new JiraIssue("connection", "cloud", "10042", "ICE-7", "https://company.atlassian.net/browse/ICE-7", "Expire snapshots by branch", "As described",
                "Story", new JiraDtos.Status("3", "In progress", "indeterminate"), Optional.empty(), ImmutableList.of(), Optional.empty(), Optional.empty(), false, false,
                NOW, Optional.empty(), Optional.empty(), ImmutableList.of(), false, Optional.empty(), Optional.of(new JiraDtos.WorkspaceLink("ws1", "Trino")));
        when(jira.cachedIssue("cloud", "10042")).thenReturn(Optional.of(ticket));
        act(message("go ahead with the ticket", ImmutableList.of()), ImmutableMap.of("jira:cloud:10042", SERVED),
                "{\"op\":\"start_task\",\"from\":\"jira:cloud:10042\",\"words\":\"go ahead with the ticket\"}");
        InteractiveTaskStart jiraStart = started();
        assertThat(jiraStart.message()).isEqualTo("Implement ICE-7: Expire snapshots by branch\n\nNote from the user, in their words to the assistant: “go ahead with the ticket”");
        assertThat(jiraStart.jiraSource().orElseThrow().key()).isEqualTo("ICE-7");
    }

    @Test
    void retriesAStartThatFailedAsTheSameTaskAndRefusesARepeatOrASixthAction()
    {
        AssistantItem message = message("start one in trino: add a metadata table", ImmutableList.of());
        Map<String, ServedRef> served = ImmutableMap.of("ws:ws1", SERVED);
        String start = "{\"op\":\"start_task\",\"workspace\":\"ws:ws1\",\"words\":\"add a metadata table\"}";
        doThrow(new IllegalStateException("The worktree is not ready")).doAnswer(call -> {
            InteractiveTaskStart value = call.getArgument(0);
            return AgentTask.created(value.taskId(), Optional.of("ws1"), true, "Add a metadata table", value.laneId(), new RepositoryIdentity("trinodb", "trino"),
                    Path.of("/tmp/assistant-task"), value.defaults(), NOW);
        }).when(starter).start(any());
        act(message, served, start);
        assertThat(receipts.getLast().receipt().orElseThrow().outcome()).isEqualTo(FAILED);
        assertThat(receipts.getLast().text()).isEqualTo("The worktree is not ready");
        // A retried reply runs the message again with a new tool; the same call is the same task.
        act(message, served, start);
        ArgumentCaptor<InteractiveTaskStart> starts = ArgumentCaptor.forClass(InteractiveTaskStart.class);
        verify(starter, times(2)).start(starts.capture());
        assertThat(starts.getAllValues()).extracting(InteractiveTaskStart::taskId).containsOnly(starts.getValue().taskId());
        assertThat(starts.getAllValues()).extracting(InteractiveTaskStart::idempotencyKey).containsOnly(starts.getValue().idempotencyKey());

        act(message, served, start);
        assertRefused("Already done", Optional.empty());
        act(message, served, "{\"op\":\"start_task\",\"workspace\":\"ws:ws1\",\"words\":\"a metadata table\"}");
        act(message, served, "{\"op\":\"start_task\",\"workspace\":\"ws:ws1\",\"words\":\"metadata table\"}");
        act(message, served, "{\"op\":\"start_task\",\"workspace\":\"ws:ws1\",\"words\":\"table\"}");
        assertRefused("That's as many actions as one message can ask for", Optional.empty());
        assertThat(store.actions(message.id())).extracting(AssistantAction::outcome).containsExactly(FAILED, DONE, REFUSED, DONE, DONE, REFUSED);
        verify(starter, times(4)).start(any());
    }

    @Test
    void refusesAnAgentThatIsNotReadyAndRejectsAMalformedCall()
    {
        act(message("start one in trino with gemini: add a metadata table", ImmutableList.of()), ImmutableMap.of("ws:ws1", SERVED),
                "{\"op\":\"start_task\",\"workspace\":\"ws:ws1\",\"agent\":\"gemini\",\"words\":\"add a metadata table\"}");
        assertRefused("That agent isn't ready", Optional.empty());
        int written = receipts.size();
        assertThat(act(message("allow it", ImmutableList.of()), "{\"op\":\"merge\",\"words\":\"allow it\"}")).contains("op must be one of");
        String forTask = "{\"op\":\"answer_approval\",\"approval\":\"" + CARD + "\",\"decision\":\"allow_for_task\",\"words\":\"allow it\"}";
        assertThat(act(message("allow it", ImmutableList.of()), forTask))
                .contains("decision must be allow_once or deny");
        assertThat(act(message("allow it", ImmutableList.of()), "{\"op\":\"reply_to_task\",\"task\":\"task:x\",\"message\":\"hi\",\"words\":\"allow it\"}"))
                .contains("reply_to_task takes no message");
        assertThat(receipts).hasSize(written);
        verify(starter, never()).start(any());
    }

    @Test
    void keepsAReceiptForACardAnsweredWithItsButton()
    {
        AgentApproval waiting = card(ProviderApprovalRequest.Kind.COMMAND, ImmutableList.of("Bash"), "{}", false);
        when(approvals.find(APPROVAL)).thenReturn(Optional.of(waiting.resolve(
                new ApprovalDecision(APPROVAL, ApprovalDecision.Decision.ALLOW, Optional.of("Bash(./mvnw:*)"), true, ImmutableMap.of(), NOW, false),
                AgentEventPayload.ApprovalOutcome.ALLOWED)));
        AssistantItem receipt = actions.buttonReceipt(APPROVAL, DAY);
        assertThat(receipt.text()).isEqualTo("Allowed for this task · Retry Iceberg commit on conflict");
        assertThat(receipt.receipt().orElseThrow().target()).contains(AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", task.id().toString()));
        assertThat(store.actions(receipt.id())).isEmpty();
    }

    private AgentApproval card(ProviderApprovalRequest.Kind kind, List<String> requested, String detail, boolean sandboxed)
    {
        ProviderApprovalRequest request = new ProviderApprovalRequest(kind, "Allow " + requested.getFirst(), detail, requested, ImmutableList.of(ALLOW_ONCE, DENY), sandboxed,
                ProviderAgentEventSource.root(), NOW.minusSeconds(30));
        AgentApproval approval = AgentApproval.pending(new ApprovalRequest(APPROVAL, task.id(), TurnId.random(), request, NOW.minusSeconds(30)));
        when(approvals.find(APPROVAL)).thenReturn(Optional.of(approval));
        return approval;
    }

    private static AssistantItem message(String text, List<String> visible)
    {
        return message(text, visible, Optional.empty());
    }

    private static AssistantItem message(String text, List<String> visible, Optional<String> onScreen)
    {
        return AssistantItem.user(DAY, NOW, text, visible, onScreen, "key-" + text);
    }

    private String act(AssistantItem message, String arguments)
    {
        return act(message, ImmutableMap.of(), arguments);
    }

    /** One call through a tool made for the message, as a run makes one; a retried run makes another for the same message. */
    private String act(AssistantItem message, Map<String, ServedRef> served, String arguments)
    {
        AssistantContext context = new AssistantContext(mock(AssistantReader.class), DAY, message.visible(), message.onScreen(), served, mapper);
        return actions.tool(message, context, receipts::add).call().apply(arguments);
    }

    private InteractiveTaskStart started()
    {
        ArgumentCaptor<InteractiveTaskStart> start = ArgumentCaptor.forClass(InteractiveTaskStart.class);
        verify(starter, atLeastOnce()).start(start.capture());
        return start.getValue();
    }

    private void assertRefused(String reason, Optional<AssistantReceipt.Fix> fix)
    {
        AssistantItem receipt = receipts.getLast();
        assertThat(receipt.text()).isEqualTo(reason);
        assertThat(receipt.receipt().orElseThrow().outcome()).isEqualTo(REFUSED);
        assertThat(receipt.receipt().orElseThrow().fix()).isEqualTo(fix);
    }

    private static AgentTurn turn(TaskId taskId)
    {
        AgentTurnSettings settled = new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ZERO, ImmutableList.of());
        return AgentTurn.accepted(TurnId.random(), taskId, "request", settled, NOW);
    }

    private static AgentTask task(String title)
    {
        return AgentTask.created(TaskId.random(), Optional.of("ws1"), true, title, new AgentLaneId("claude"), new RepositoryIdentity("trinodb", "trino"),
                Path.of("/tmp/assistant-task"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW.minusSeconds(3600));
    }

    private static ModelSettingsService.Provider provider(String id, String label, AgentProviderStatus.Readiness readiness, String model, String effort)
    {
        return new ModelSettingsService.Provider(id, label, Optional.empty(), readiness, Optional.empty(), "", Optional.empty(),
                new AgentCapabilities(true, true, true, ImmutableSet.of(AgentPermissionPolicy.AUTO), ImmutableSet.of()), ImmutableList.of(), model, effort);
    }
}
