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
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.AutomationLevel;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.QueueAgentInputCommand;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
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
import com.bytequay.app.agenda.ItemTaskStarter;
import com.bytequay.app.agent.InteractiveAgentTaskStarter;
import com.bytequay.app.agent.InteractiveTaskStart;
import com.bytequay.app.agent.IssueDevelopmentService;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.notices.AppNoticeService;
import com.bytequay.app.service.attention.AttentionMarkService;
import com.bytequay.app.service.epic.EpicService;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workitem.WorkItemService;
import com.bytequay.app.workitem.WorkItemView;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.AssistantAction;
import com.bytequay.core.domain.AssistantDraft;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.domain.AssistantReceipt;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.ServedRef;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraDtos;
import com.bytequay.integration.jira.JiraIssue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private final IssueDevelopmentService issueDevelopment = mock(IssueDevelopmentService.class);
    private final EpicService epics = mock(EpicService.class);
    private final WorkItemService workItemService = mock(WorkItemService.class);
    private final AssistantRows rows = mock(AssistantRows.class);
    private final AttentionMarkService attentionMarks = mock(AttentionMarkService.class);
    private final ObjectMapper mapper = AgendaFixtures.mapper();
    private final AtomicReference<AssistantPermissions> allowed = new AtomicReference<>(new AssistantPermissions(true, true, true, true));
    private final List<AssistantItem> receipts = new ArrayList<>();
    private final AgentTask task = task("Retry Iceberg commit on conflict");
    private final Workspace trino = new Workspace("ws1", "Trino", 1, RepoRef.of("trinodb", "trino"), Optional.empty(), Optional.empty(), 1, RepoRef.of("trinodb", "trino"),
            Workspace.Topology.DIRECT, "master", "https://github.com/trinodb/trino.git", Optional.empty(), Optional.empty(), "/managed/trino", Optional.empty(),
            Optional.empty(), 0, NOW, NOW);
    private AssistantActions actions;
    private AssistantWork work;

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
        when(settings.developmentAutomation()).thenReturn(new DevelopmentAutomation(true, AutomationLevel.DRAFT_PR, Optional.empty()));
        when(starter.start(any())).thenAnswer(call -> {
            InteractiveTaskStart start = call.getArgument(0);
            return AgentTask.created(start.taskId(), Optional.of(start.workspaceId()), true, "Expose snapshot id in metadata", start.laneId(),
                    new RepositoryIdentity("trinodb", "trino"), Path.of("/tmp/assistant-task"), start.defaults(), NOW);
        });
        when(turns.findTurnByIdempotencyKey(any(), any())).thenAnswer(call -> Optional.of(turn(call.getArgument(0))));
        when(turns.start(any())).thenAnswer(call -> turn(call.<StartAgentTurnCommand>getArgument(0).taskId()));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        work = new AssistantWork(issueDevelopment, jira, epics, workItemService, workItems, workspaces, rows, attentionMarks);
        actions = new AssistantActions(store, permissions, approvals, approvalService, tasks, turns, new ItemTaskStarter(starter, models, settings, workspaces, workItems,
                issues, jira), notices, reviews, work, mapper, clock);
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
                Optional.of(AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", task.id().toString()))).withWords("allow  IT"));
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
        allowed.set(new AssistantPermissions(false, true, true, true));
        AssistantItem message = message("start one in trino: add a metadata table", ImmutableList.of());
        act(message, ImmutableMap.of("ws:ws1", SERVED), "{\"op\":\"start_task\",\"workspace\":\"ws:ws1\",\"words\":\"add a metadata table\"}");
        assertRefused("Starting tasks is off", Optional.of(SETTINGS));

        allowed.set(new AssistantPermissions(true, false, true, true));
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
        assertThat(receipts.getLast().receipt().orElseThrow().detail()).contains("Where should the snapshot id appear? → Hidden column");
        assertThat(receipts.getLast().receipt().orElseThrow().words()).contains("hidden column");
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
    void refusesWordsTheMessageSaysNotToDo()
    {
        AgendaTarget opens = AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", task.id().toString());
        Map<String, ServedRef> served = ImmutableMap.of("task:" + task.id(), new ServedRef("tasks", Optional.of(opens)));
        act(message("tell it: don't push to main", ImmutableList.of()), served, "{\"op\":\"reply_to_task\",\"task\":\"task:" + task.id() + "\",\"words\":\"push to main\"}");
        assertRefused("Your message says not to do that", Optional.empty());
        assertThat(receipts.getLast().receipt().orElseThrow().words()).contains("push to main");
        verify(turns, never()).start(any());

        card(ProviderApprovalRequest.Kind.COMMAND, ImmutableList.of("Bash"), "{}", true);
        act(message("don’t allow it", ImmutableList.of(CARD)), "{\"op\":\"answer_approval\",\"approval\":\"" + CARD + "\",\"decision\":\"allow_once\",\"words\":\"allow it\"}");
        assertRefused("Your message says not to do that", Optional.empty());
        act(message("never allow it, deny it", ImmutableList.of(CARD)), "{\"op\":\"answer_approval\",\"approval\":\"" + CARD + "\",\"decision\":\"deny\",\"words\":\"deny it\"}");
        assertThat(receipts.getLast().text()).isEqualTo("Denied · Retry Iceberg commit on conflict");

        card(ProviderApprovalRequest.Kind.TOOL, ImmutableList.of("AskUserQuestion"), QUESTION_INPUT, false);
        act(message("not the metadata table, use the hidden column", ImmutableList.of(CARD)),
                "{\"op\":\"answer_question\",\"approval\":\"" + CARD + "\",\"answers\":{\"Where should the snapshot id appear?\":\"Metadata table\"},"
                        + "\"words\":\"use the hidden column\"}");
        assertRefused("Your message says not to do that", Optional.empty());
        verify(approvalService, times(1)).resolve(any());
    }

    @Test
    void readsANegationOnlyWithinItsClause()
    {
        assertThat(AssistantActions.negated("don't push to main", "push to main")).isTrue();
        assertThat(AssistantActions.negated("please do not merge yet", "merge yet")).isTrue();
        assertThat(AssistantActions.negated("don't merge it or tag it", "tag it")).isTrue();
        assertThat(AssistantActions.negated("open a pr instead of pushing to main", "pushing to main")).isTrue();
        assertThat(AssistantActions.negated("don't push to main, open a pr instead", "open a pr instead")).isFalse();
        assertThat(AssistantActions.negated("don't push to main but push the branch", "push the branch")).isFalse();
        assertThat(AssistantActions.negated("merge it. don't tag it", "merge it")).isFalse();
        assertThat(AssistantActions.negated("nothing else, just merge it", "merge it")).isFalse();
        assertThat(AssistantActions.negated("don't push to main", "don't push to main")).isFalse();
        assertThat(AssistantActions.negated("ship it", "push to main")).isFalse();
    }

    @Test
    void sendsTheUsersWordsToAnIdleTaskAndQueuesThemBehindAWorkingOne()
    {
        AssistantItem message = message("tell it to use the hidden column", ImmutableList.of());
        AgendaTarget opens = AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", task.id().toString());
        Map<String, ServedRef> served = ImmutableMap.of("task:" + task.id(), new ServedRef("tasks", Optional.of(opens)));
        act(message, served, "{\"op\":\"reply_to_task\",\"task\":\"task:" + task.id() + "\",\"words\":\"use the hidden column\"}");
        assertThat(receipts.getLast().text()).isEqualTo("Sent your words to Retry Iceberg commit on conflict");
        ArgumentCaptor<StartAgentTurnCommand> sent = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(turns).start(sent.capture());
        assertThat(sent.getValue().message()).isEqualTo("use the hidden column");

        // A task at work takes the words after its current turn, as its own page's send does.
        when(tasks.find(task.id())).thenReturn(Optional.of(task.claimWorktreeMutation(WorktreeMutationClaim.turn(TurnId.random(), NOW), NOW)));
        AgentTurnSettings settled = new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ZERO,
                ImmutableList.of(), Optional.empty(), Optional.empty());
        when(turns.enqueue(any())).thenAnswer(call -> QueuedAgentInput.queued(QueuedInputId.random(), task.id(), 1, call.<QueueAgentInputCommand>getArgument(0).idempotencyKey(),
                new BlobRef("reply.txt", "text/plain", 16), call.<QueueAgentInputCommand>getArgument(0).message(), settled, NOW));
        act(message("and also keep the old key", ImmutableList.of()), served, "{\"op\":\"reply_to_task\",\"task\":\"task:" + task.id() + "\",\"words\":\"keep the old key\"}");
        assertThat(receipts.getLast().text()).isEqualTo("Queued your words for Retry Iceberg commit on conflict");
        assertThat(receipts.getLast().receipt().orElseThrow().detail()).contains("After its current turn");
        assertThat(receipts.getLast().receipt().orElseThrow().target()).contains(opens);
        ArgumentCaptor<QueueAgentInputCommand> queued = ArgumentCaptor.forClass(QueueAgentInputCommand.class);
        verify(turns).enqueue(queued.capture());
        assertThat(queued.getValue().message()).isEqualTo("keep the old key");
        verify(turns, times(1)).start(any());

        // Anything else holding the task, such as a run in its working copy, is the turn service's refusal.
        doThrow(new AgentConflictException("A run is using this working copy")).when(turns).enqueue(any());
        act(message("and also drop the cache", ImmutableList.of()), served, "{\"op\":\"reply_to_task\",\"task\":\"task:" + task.id() + "\",\"words\":\"drop the cache\"}");
        assertThat(receipts.getLast().receipt().orElseThrow().outcome()).isEqualTo(FAILED);
        assertThat(receipts.getLast().text()).isEqualTo("A run is using this working copy");
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
                Optional.of(new DevelopmentAutomation(true, AutomationLevel.DRAFT_PR, Optional.empty()))));
        assertThat(start.initialAutomation()).contains(new DevelopmentAutomation(true, AutomationLevel.DRAFT_PR, Optional.empty()));
        assertThat(start.workItemId()).isEmpty();
        AssistantReceipt receipt = receipts.getLast().receipt().orElseThrow();
        assertThat(receipts.getLast().text()).isEqualTo("Started “Expose snapshot id in metadata”");
        assertThat(receipt.detail()).contains("trinodb/trino · Claude Code Opus 5.5 · Auto-Draft PR");
        assertThat(receipt.turnId()).isPresent();
        assertThat(receipt.target()).contains(AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", start.taskId().toString()));
        assertThat(store.actions(message.id()).getLast().result()).contains(start.taskId().toString());
    }

    @Test
    void startsAnItemWithItsOwnFirstMessageThenTheUsersNote()
    {
        when(workItems.find("w22")).thenReturn(Optional.of(new WorkItem("w22", "ws1", "Expose snapshot id in metadata", WorkItem.Kind.DEVELOP, "",
                WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.of(), NOW, NOW, WorkItem.Priority.HIGH,
                ImmutableList.of(), Optional.empty(), 0, ImmutableList.of())));
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
    void refusesWorkChangesWhileTheirSwitchIsOff()
    {
        allowed.set(new AssistantPermissions(true, true, false, true));
        act(message("make #77 an epic", ImmutableList.of()), ImmutableMap.of("issue:trinodb/trino#77", SERVED),
                "{\"op\":\"mark_epic\",\"item\":\"issue:trinodb/trino#77\",\"words\":\"make #77 an epic\"}");
        assertRefused("Changing Epics and work items is off", Optional.of(SETTINGS));
        verify(issueDevelopment, never()).setEpic(anyString(), anyInt(), anyBoolean());
    }

    @Test
    void marksAnIssueATicketOrAWorkItemAsAnEpic()
    {
        Map<String, ServedRef> served = ImmutableMap.of("issue:trinodb/trino#77", SERVED, "jira:cloud:10042", SERVED, "work:w22", SERVED);
        AgendaTarget issue = AgendaTarget.issue("trinodb/trino", 77, "ws1");
        named("issue:trinodb/trino#77", "Snapshot ids in $snapshots", issue);
        act(message("make #77 an epic", ImmutableList.of()), served, "{\"op\":\"mark_epic\",\"item\":\"issue:trinodb/trino#77\",\"words\":\"make #77 an epic\"}");
        verify(issueDevelopment).setEpic("ws1", 77, true);
        assertThat(receipts.getLast().text()).isEqualTo("Marked “Snapshot ids in $snapshots” as an Epic");
        assertThat(receipts.getLast().receipt().orElseThrow()).isEqualTo(AssistantReceipt.done(AssistantReceipt.Operation.MARK_EPIC, Optional.of(issue))
                .withWords("make #77 an epic"));

        JiraIssue ticket = ticket();
        when(jira.cachedIssue("cloud", "10042")).thenReturn(Optional.of(ticket));
        act(message("mark the ticket as an epic", ImmutableList.of()), served, "{\"op\":\"mark_epic\",\"item\":\"jira:cloud:10042\",\"words\":\"mark the ticket\"}");
        verify(jira).epic("connection", "cloud", "10042", true);
        when(workItems.find("w22")).thenReturn(Optional.of(workItem("w22")));
        act(message("this work item is an epic", ImmutableList.of()), served, "{\"op\":\"mark_epic\",\"item\":\"work:w22\",\"words\":\"is an epic\"}");
        verify(epics).setLocalEpic("ws1", "w22", true);
        assertThat(receipts).extracting(receipt -> receipt.receipt().orElseThrow().outcome()).containsOnly(DONE);

        // Only what the user could see, of a kind that can be an Epic; the item's own rules are its service's.
        act(message("make #78 an epic", ImmutableList.of()), served, "{\"op\":\"mark_epic\",\"item\":\"issue:trinodb/trino#78\",\"words\":\"make #78 an epic\"}");
        assertRefused("That isn't on screen", Optional.empty());
        assertThat(act(message("make it an epic", ImmutableList.of()), served, "{\"op\":\"mark_epic\",\"item\":\"pr:trinodb/trino#31402\",\"words\":\"make it an epic\"}"))
                .contains("mark_epic takes an issue:, jira: or work: ref");
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "This ticket already has a development task.")).when(jira).epic("connection", "cloud", "10042", true);
        act(message("mark the ticket as an epic, please", ImmutableList.of()), served,
                "{\"op\":\"mark_epic\",\"item\":\"jira:cloud:10042\",\"words\":\"mark the ticket as an epic\"}");
        assertThat(receipts.getLast().receipt().orElseThrow().outcome()).isEqualTo(FAILED);
        assertThat(receipts.getLast().text()).isEqualTo("This ticket already has a development task.");
    }

    @Test
    void addsWorkToAnEpicThatTheUserCouldSee()
    {
        Map<String, ServedRef> served = ImmutableMap.of("epic:LOCAL:w1", SERVED, "work:w22", SERVED, "issue:trinodb/trino#77", SERVED);
        when(rows.epicWorkspace("LOCAL:w1")).thenReturn(Optional.of(trino));
        named("epic:LOCAL:w1", "Iceberg write path", AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", "w1"));
        when(workItemService.joinLocalEpic("ws1", "LOCAL:w1", "w22")).thenReturn(new WorkItemService.EpicJoin(view("w22", "Add a snapshot id column"), Optional.empty()));
        when(workItemService.joinEpic("ws1", "LOCAL:w1", GoalItemKind.ISSUE, "trinodb/trino#77"))
                .thenReturn(new WorkItemService.EpicJoin(view("w30", "Snapshot ids in $snapshots"), Optional.of("JIRA:cloud:10040")));

        act(message("put the column one in the write path epic", ImmutableList.of()), served,
                "{\"op\":\"add_to_epic\",\"epic\":\"epic:LOCAL:w1\",\"item\":\"work:w22\",\"words\":\"put the column one in the write path epic\"}");
        assertThat(receipts.getLast().text()).isEqualTo("Added “Add a snapshot id column” to Iceberg write path");
        assertThat(receipts.getLast().receipt().orElseThrow().target()).contains(AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", "w22"));
        act(message("and #77 too", ImmutableList.of()), served, "{\"op\":\"add_to_epic\",\"epic\":\"epic:LOCAL:w1\",\"item\":\"issue:trinodb/trino#77\",\"words\":\"#77 too\"}");
        assertThat(receipts.getLast().text()).isEqualTo("Added “Snapshot ids in $snapshots” to Iceberg write path");
        assertThat(receipts.getLast().receipt().orElseThrow().detail()).contains("Moved from another Epic");

        act(message("add #9 to it", ImmutableList.of()), ImmutableMap.of("issue:trinodb/trino#9", SERVED),
                "{\"op\":\"add_to_epic\",\"epic\":\"epic:LOCAL:w1\",\"item\":\"issue:trinodb/trino#9\",\"words\":\"add #9 to it\"}");
        assertRefused("That isn't on screen", Optional.empty());
        assertThat(act(message("add it", ImmutableList.of()), served, "{\"op\":\"add_to_epic\",\"epic\":\"work:w1\",\"item\":\"work:w22\",\"words\":\"add it\"}"))
                .contains("add_to_epic needs an epic: ref");
        verify(workItemService).joinLocalEpic(anyString(), anyString(), anyString());
        verify(workItemService).joinEpic(anyString(), anyString(), any(GoalItemKind.class), anyString());
    }

    @Test
    void createsAWorkItemTitledWithTheUsersWordsOnce()
    {
        Map<String, ServedRef> served = ImmutableMap.of("epic:LOCAL:w1", SERVED, "ws:ws1", SERVED);
        when(rows.epicWorkspace("LOCAL:w1")).thenReturn(Optional.of(trino));
        named("epic:LOCAL:w1", "Iceberg write path", AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", "w1"));
        AssistantItem message = message("add work to the write path epic: retry the commit on conflict", ImmutableList.of());
        String create = "{\"op\":\"create_work_item\",\"epic\":\"epic:LOCAL:w1\",\"words\":\"retry the commit on conflict\"}";
        when(workItemService.createInEpic(eq("ws1"), anyString(), eq("LOCAL:w1"), eq("retry the commit on conflict"), eq(WorkItem.Kind.DEVELOP), eq(""),
                eq(WorkItem.CompletionRule.MERGED_DELIVERY))).thenThrow(new IllegalStateException("database is locked"))
                .thenAnswer(call -> view(call.getArgument(1), "retry the commit on conflict"));

        act(message, served, create);
        assertThat(receipts.getLast().receipt().orElseThrow().outcome()).isEqualTo(FAILED);
        // A retried reply makes a new tool for the same message; the same call names the same new work.
        act(message, served, create);
        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(workItemService, times(2)).createInEpic(eq("ws1"), ids.capture(), eq("LOCAL:w1"), eq("retry the commit on conflict"), eq(WorkItem.Kind.DEVELOP), eq(""),
                eq(WorkItem.CompletionRule.MERGED_DELIVERY));
        assertThat(ids.getAllValues()).containsOnly(ids.getValue());
        assertThat(receipts.getLast().text()).isEqualTo("Created “retry the commit on conflict”");
        assertThat(receipts.getLast().receipt().orElseThrow().detail()).contains("trinodb/trino · Iceberg write path");
        assertThat(receipts.getLast().receipt().orElseThrow().target()).contains(AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", ids.getValue()));

        when(workItemService.create(eq("ws1"), anyString(), eq("flaky snapshot test"), eq(WorkItem.Kind.DEVELOP), eq(""),
                eq(WorkItem.CompletionRule.MERGED_DELIVERY))).thenAnswer(call -> view(call.getArgument(1), "flaky snapshot test"));
        act(message("new work item in trino: flaky snapshot test", ImmutableList.of()), served,
                "{\"op\":\"create_work_item\",\"workspace\":\"ws:ws1\",\"words\":\"flaky snapshot test\"}");
        assertThat(receipts.getLast().text()).isEqualTo("Created “flaky snapshot test”");
        assertThat(receipts.getLast().receipt().orElseThrow().detail()).contains("trinodb/trino");
        assertThat(act(message("new work: x y", ImmutableList.of()), served, "{\"op\":\"create_work_item\",\"workspace\":\"trino\",\"words\":\"x y\"}"))
                .contains("create_work_item needs a ws: or epic: ref");
    }

    @Test
    void acknowledgesAndSnoozesWhatNeedsAttention()
    {
        String taskRef = "task:" + task.id();
        Map<String, ServedRef> served = ImmutableMap.of("pr:trinodb/trino#31402", SERVED, taskRef, SERVED);
        AgendaTarget pull = AgendaTarget.pull("trinodb/trino", 31402, Optional.of("ws1"));
        named("pr:trinodb/trino#31402", "Add Iceberg metadata cache", pull);
        when(rows.attentionKey("pr:trinodb/trino#31402")).thenReturn(Optional.of("TrinoDB/trino#31402"));
        act(message("clear the metadata cache PR", ImmutableList.of()), served,
                "{\"op\":\"acknowledge\",\"item\":\"pr:trinodb/trino#31402\",\"words\":\"clear the metadata cache PR\"}");
        // The list's own key, in the repository's own spelling, so the row that goes is the one the user meant.
        verify(attentionMarks).acknowledge("TrinoDB/trino#31402");
        AssistantReceipt acknowledged = receipts.getLast().receipt().orElseThrow();
        assertThat(receipts.getLast().text()).isEqualTo("Acknowledged “Add Iceberg metadata cache”");
        assertThat(acknowledged.operation()).isEqualTo(AssistantReceipt.Operation.ACKNOWLEDGE);
        assertThat(acknowledged.detail()).contains("Back when something new happens");
        assertThat(acknowledged.target()).contains(pull);

        named(taskRef, "Retry Iceberg commit on conflict", AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", task.id().toString()));
        when(rows.attentionKey(taskRef)).thenReturn(Optional.of(taskRef));
        act(message("snooze the retry task until tomorrow", ImmutableList.of()), served,
                "{\"op\":\"snooze\",\"item\":\"" + taskRef + "\",\"until\":\"tomorrow\",\"words\":\"snooze the retry task until tomorrow\"}");
        verify(attentionMarks).snooze(taskRef, AttentionMarkService.Snooze.TOMORROW);
        assertThat(receipts.getLast().text()).isEqualTo("Snoozed “Retry Iceberg commit on conflict” until tomorrow at 9:00");
        assertThat(receipts).extracting(receipt -> receipt.receipt().orElseThrow().outcome()).containsOnly(DONE);

        // Only what the user could see, of a kind the list shows, for one of its snooze choices.
        act(message("clear #31403", ImmutableList.of()), served, "{\"op\":\"acknowledge\",\"item\":\"pr:trinodb/trino#31403\",\"words\":\"clear #31403\"}");
        assertRefused("That isn't on screen", Optional.empty());
        assertThat(act(message("clear it", ImmutableList.of()), served, "{\"op\":\"acknowledge\",\"item\":\"work:w22\",\"words\":\"clear it\"}"))
                .contains("acknowledge takes a pr:, issue:, jira: or task: ref");
        assertThat(act(message("snooze it till friday", ImmutableList.of()), served,
                "{\"op\":\"snooze\",\"item\":\"pr:trinodb/trino#31402\",\"until\":\"friday\",\"words\":\"snooze it\"}"))
                .contains("until must be hour, tomorrow or monday");
        when(rows.attentionKey("pr:trinodb/trino#31402")).thenReturn(Optional.empty());
        act(message("clear the metadata cache PR again", ImmutableList.of()), served,
                "{\"op\":\"acknowledge\",\"item\":\"pr:trinodb/trino#31402\",\"words\":\"clear the metadata cache PR again\"}");
        assertThat(receipts.getLast().receipt().orElseThrow().outcome()).isEqualTo(FAILED);
        assertThat(receipts.getLast().text()).isEqualTo("That isn't in Needs attention");
    }

    @Test
    void refusesToAcknowledgeWhileItsSwitchIsOff()
    {
        allowed.set(new AssistantPermissions(true, true, true, false));
        act(message("clear the metadata cache PR", ImmutableList.of()), ImmutableMap.of("pr:trinodb/trino#31402", SERVED),
                "{\"op\":\"acknowledge\",\"item\":\"pr:trinodb/trino#31402\",\"words\":\"clear the metadata cache PR\"}");
        assertRefused("Acknowledging and snoozing is off", Optional.of(SETTINGS));
        verifyNoInteractions(attentionMarks);
    }

    @Test
    void namesTheEpicADraftBecameAndTheIssueItWasPublishedAs()
    {
        AssistantDraft draft = new AssistantDraft("ws1", "trinodb/trino", "Track the snapshot work.", "w9");
        assertThatThrownBy(() -> work.drafted(draft, DAY, NOW)).isInstanceOf(IllegalArgumentException.class).hasMessage("That draft was not created yet");

        WorkItem published = new WorkItem("w9", "ws1", "Iceberg snapshot ids", WorkItem.Kind.DEVELOP, "Track the snapshot work.",
                WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.PLANNED, 2, ImmutableList.of(new WorkItemLink("ISSUE:github.com:901",
                        new GoalItem(GoalItemKind.ISSUE, "trinodb/trino#123", "#123", "Iceberg snapshot ids", NOW), Optional.empty(), Optional.empty())),
                ImmutableList.of(), NOW, NOW, WorkItem.Priority.NORMAL, ImmutableList.of(), Optional.empty(), 0, ImmutableList.of());
        when(workItems.find("w9")).thenReturn(Optional.of(published));
        AssistantItem receipt = work.drafted(draft, DAY, NOW);

        // The user may have edited the title before the click: the receipt names the work as created.
        assertThat(receipt.text()).isEqualTo("Created “Iceberg snapshot ids” as an Epic");
        assertThat(receipt.receipt().orElseThrow().operation()).isEqualTo(AssistantReceipt.Operation.CREATE_EPIC);
        assertThat(receipt.receipt().orElseThrow().detail()).contains("trinodb/trino#123");
        assertThat(receipt.receipt().orElseThrow().target()).contains(AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", "w9"));
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
        AgentTurnSettings settled = new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ZERO,
                ImmutableList.of(), Optional.empty(), Optional.empty());
        return AgentTurn.accepted(TurnId.random(), taskId, "request", settled, NOW);
    }

    private static AgentTask task(String title)
    {
        return AgentTask.created(TaskId.random(), Optional.of("ws1"), true, title, new AgentLaneId("claude"), new RepositoryIdentity("trinodb", "trino"),
                Path.of("/tmp/assistant-task"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW.minusSeconds(3600));
    }

    /** What the stores call the item now and where it opens. */
    private void named(String ref, String title, AgendaTarget target)
    {
        when(rows.rows(ImmutableList.of(ref))).thenReturn(ImmutableMap.of(ref, new AssistantRow(ref, AssistantRow.Kind.WORK, title, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), false, false, false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), target)));
    }

    private static JiraIssue ticket()
    {
        return new JiraIssue("connection", "cloud", "10042", "ICE-7", "https://company.atlassian.net/browse/ICE-7", "Expire snapshots by branch", "As described",
                "Story", new JiraDtos.Status("3", "In progress", "indeterminate"), Optional.empty(), ImmutableList.of(), Optional.empty(), Optional.empty(), false, false,
                NOW, Optional.empty(), Optional.empty(), ImmutableList.of(), false, Optional.empty(), Optional.of(new JiraDtos.WorkspaceLink("ws1", "Trino")));
    }

    private static WorkItem workItem(String id)
    {
        return new WorkItem(id, "ws1", "Expose snapshot id in metadata", WorkItem.Kind.DEVELOP, "", WorkItem.CompletionRule.MERGED_DELIVERY,
                WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.of(), NOW, NOW, WorkItem.Priority.HIGH, ImmutableList.of(), Optional.empty(), 0,
                ImmutableList.of());
    }

    private static WorkItemView view(String id, String title)
    {
        return new WorkItemView(id, "ws1", title, WorkItem.Kind.DEVELOP, "", WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.PLANNED, 1,
                ImmutableList.of(), ImmutableList.of(), NOW, NOW, WorkItem.Priority.NORMAL, ImmutableList.of(), Optional.of("LOCAL:w1"), 0, false, ImmutableList.of());
    }

    private static ModelSettingsService.Provider provider(String id, String label, AgentProviderStatus.Readiness readiness, String model, String effort)
    {
        return new ModelSettingsService.Provider(id, label, Optional.empty(), readiness, Optional.empty(), "", Optional.empty(),
                new AgentCapabilities(true, true, true, ImmutableSet.of(AgentPermissionPolicy.AUTO), ImmutableSet.of()), ImmutableList.of(), model, effort);
    }
}
