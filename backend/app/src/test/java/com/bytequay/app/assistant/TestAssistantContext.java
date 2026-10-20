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
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.app.agenda.AgendaFixtures;
import com.bytequay.app.agenda.AgendaReader;
import com.bytequay.app.agent.RunningTask;
import com.bytequay.app.agent.RunningTaskReader;
import com.bytequay.app.agent.TaskActivity;
import com.bytequay.app.agent.TaskActivityReader;
import com.bytequay.app.agent.TaskMessage;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.notices.AppNotice;
import com.bytequay.app.notices.AppNoticeService;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.AgendaCalendar;
import com.bytequay.core.domain.AgendaDay;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.ServedRef;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.AgendaStore;
import com.bytequay.core.store.EpicStore;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraActivityReader;
import com.bytequay.integration.jira.JiraDtos;
import com.bytequay.integration.jira.JiraIssue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestAssistantContext
{
    private static final Instant NOW = AgendaFixtures.NOW;
    private static final LocalDate TODAY = LocalDate.parse("2026-10-16");
    private static final AgentTask TASK = task("Retry Iceberg commit on conflict", "claude");
    private static final AgentTask ASKING = task("Expose snapshot id in metadata", "codex");
    private static final AgentTask FINISHED = task("Add metadata cache", "codex");
    private static final ApprovalId COMMAND = ApprovalId.valueOf("0b1d5f8e-5d7a-4f0e-9a52-6a4f1c2b3d01");
    private static final ApprovalId QUESTION = ApprovalId.valueOf("0b1d5f8e-5d7a-4f0e-9a52-6a4f1c2b3d02");
    private static final String QUESTION_INPUT = """
            {"questions": [{"question": "Where should the snapshot id appear?", "header": "Place", "multiSelect": false,
              "options": [{"label": "Metadata table", "description": "x"}, {"label": "Both", "description": "y"}]}]}
            """;

    private final ObjectMapper mapper = AgendaFixtures.mapper();
    private final AgendaReader agenda = mock(AgendaReader.class);
    private final AgendaStore agendas = mock(AgendaStore.class);
    private final RunningTaskReader running = mock(RunningTaskReader.class);
    private final AppNoticeService notices = mock(AppNoticeService.class);
    private final AgentApprovalStore approvals = mock(AgentApprovalStore.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final TaskActivityReader activities = mock(TaskActivityReader.class);
    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final WorkItemStore workItems = mock(WorkItemStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final AppSettingsService settings = mock(AppSettingsService.class);
    private final RepositoryIssueStore issues = mock(RepositoryIssueStore.class);
    private final JiraService jira = mock(JiraService.class);
    private final EpicStore epics = mock(EpicStore.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final AssistantRows rows = new AssistantRows(pullRequests, mock(PrDetailStore.class), issues, jira, mock(JiraActivityReader.class), tasks, reviews, running, activities,
            workItems, epics, workspaces, agenda, models, credentials);
    private final AssistantReader reader = new AssistantReader(agenda, agendas, running, notices, approvals, tasks, activities, reviews, pullRequests, workItems, workspaces,
            rows, credentials, models, settings, mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void storesHoldOneDay()
    {
        when(agenda.read(any(), any(), eq(false), anyList(), any())).thenAnswer(ignored -> AgendaFixtures.friday(1));
        when(running.current()).thenReturn(ImmutableList.of(
                new RunningTask(TASK.id().toString(), "ws1", TASK.title(), "trinodb/trino", RunningTask.Kind.TASK, TASK.id().toString(), RunningTask.Status.WAITING,
                        NOW.minusSeconds(600), Optional.empty()),
                new RunningTask(FINISHED.id().toString(), "ws1", "Get CI green on #31402", "trinodb/trino", RunningTask.Kind.CI_FIX, "31402", RunningTask.Status.RUNNING,
                        NOW.minusSeconds(60), Optional.empty())));
        for (AgentTask task : ImmutableList.of(TASK, ASKING, FINISHED)) {
            when(tasks.find(task.id())).thenReturn(Optional.of(task));
        }
        when(notices.current()).thenReturn(ImmutableList.of(
                notice("approval:" + COMMAND, AppNotice.Kind.APPROVAL, TASK, "Allow Claude Bash"),
                notice("approval:" + QUESTION, AppNotice.Kind.QUESTION, ASKING, "Where should the snapshot id appear?"),
                notice("question:turn-9", AppNotice.Kind.QUESTION, FINISHED, "Keep the old cache key " + "and migrate it? ".repeat(20))));
        List<ProviderApprovalRequest.Decision> allowOrDeny = ImmutableList.of(ProviderApprovalRequest.Decision.ALLOW_ONCE, ProviderApprovalRequest.Decision.DENY);
        when(approvals.listPending()).thenReturn(ImmutableList.of(
                pending(COMMAND, TASK, new ProviderApprovalRequest(ProviderApprovalRequest.Kind.COMMAND, "Allow Claude Bash",
                        "{\"command\":\"./mvnw test " + "-x ".repeat(300) + "\"}", ImmutableList.of("Bash"), allowOrDeny, true, ProviderAgentEventSource.root(), NOW)),
                pending(QUESTION, ASKING, new ProviderApprovalRequest(ProviderApprovalRequest.Kind.TOOL, "Allow Claude AskUserQuestion", QUESTION_INPUT,
                        ImmutableList.of("AskUserQuestion"), allowOrDeny, false, ProviderAgentEventSource.root(), NOW))));
        when(pullRequests.findIdByRepoAndNumber("trinodb/trino", 31402)).thenReturn(Optional.of(7L));
        when(pullRequests.findById(7L)).thenReturn(Optional.of(new PullRequest(7, "trinodb/trino", 31402, "Retry Iceberg commit on conflict", "elric-t",
                "https://github.com/trinodb/trino/pull/31402", NOW, NOW, PullRequest.Origin.AUTHORED, ImmutableList.of(), ImmutableMap.of(), false, null, null, null,
                ImmutableList.of("marlo-t"), PullRequestDetail.CiStatus.FAILING, 10, 2, 3, "open", null, null, true, "clean", NOW, "retry", null, NOW, null, "CHANGES_REQUESTED")));
        when(workItems.find("w22")).thenReturn(Optional.of(new WorkItem("w22", "ws1", "Expose snapshot id in metadata", WorkItem.Kind.DEVELOP, "",
                WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.of(), NOW, NOW, WorkItem.Priority.HIGH,
                ImmutableList.of("w19"), Optional.empty(), 0, ImmutableList.of())));
        AgentTurnSettings settled = new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ZERO, ImmutableList.of());
        AgentTurn failed = AgentTurn.accepted(TurnId.random(), FINISHED.id(), "request", settled, NOW.minusSeconds(300)).markDeliveryStarted(NOW.minusSeconds(300))
                .running(new ProviderTurnRef("provider-turn"), Optional.empty()).finish(TurnState.FAILED, AgentMetrics.empty(), Optional.of("The provider stopped answering"), NOW);
        when(activities.read(ImmutableList.of(FINISHED))).thenReturn(ImmutableMap.of(FINISHED.id(), new TaskActivity(Optional.of(new TaskMessage(TaskMessage.Author.AGENT,
                "Kept the old cache key and " + "checked every reader of it. ".repeat(30), NOW.minusSeconds(60), Optional.empty())), Optional.empty(), Optional.of(failed))));
        Workspace trino = new Workspace("ws1", "Trino", 1, RepoRef.of("trinodb", "trino"), Optional.empty(), Optional.empty(), 1, RepoRef.of("trinodb", "trino"),
                Workspace.Topology.DIRECT, "master", "https://github.com/trinodb/trino.git", Optional.empty(), Optional.empty(), "/managed/trino", Optional.empty(),
                Optional.empty(), 0, NOW, NOW);
        when(workspaces.findAll()).thenReturn(ImmutableList.of(trino));
        when(models.agentModel(AgentModelType.DEVELOPMENT, Optional.of("ws1")))
                .thenReturn(new AgentModelSetting(AgentModelType.DEVELOPMENT, true, "claude", "Claude Code", "opus", "Opus 5.5", Optional.empty()));
        when(models.get()).thenReturn(new ModelSettingsService.ModelSettings(ImmutableList.of(),
                ImmutableList.of(provider("claude", "Claude Code", AgentProviderStatus.Readiness.READY),
                        provider("codex", "Codex", AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED)),
                ImmutableList.of(), ImmutableList.of(), Optional.empty()));
        when(settings.developmentAutomation()).thenReturn(new DevelopmentAutomation(true, true, false, Optional.empty(), false, false));
    }

    @Test
    void eachKindHoldsOnlyItsFields()
            throws Exception
    {
        ReviewTool tool = context(Optional.of("pr:trinodb/trino#31402"), ImmutableMap.of()).tool();
        assertThat(tool.name()).isEqualTo("assistant_context");

        JsonNode overview = read(tool, "overview");
        assertThat(keys(overview)).isEqualTo(ImmutableSet.of("today", "weekday", "recapDay", "since", "counts", "calendar", "nextPage"));
        assertThat(keys(read(tool, "tasks").path("entries").path(0))).contains("ref", "title", "state", "waitingFor");

        JsonNode running = read(tool, "running");
        assertThat(keys(running)).isEqualTo(ImmutableSet.of("kind", "entries"));
        assertThat(keys(running.path("entries").path(0))).isEqualTo(ImmutableSet.of("ref", "title", "repo", "agent", "phase", "since"));
        assertThat(running.path("entries").path(0).path("ref").asText()).isEqualTo("task:" + TASK.id());
        assertThat(running.path("entries").path(0).path("agent").asText()).isEqualTo("Claude Code");
        assertThat(running.path("entries").path(0).path("phase").asText()).isEqualTo("waiting");
        // A CI repair opens from its pull request, so it names no ref of its own.
        assertThat(keys(running.path("entries").path(1))).isEqualTo(ImmutableSet.of("title", "repo", "agent", "phase", "since"));

        JsonNode waiting = read(tool, "waiting").path("entries");
        assertThat(waiting).hasSize(3);
        JsonNode command = waiting.path(0);
        assertThat(keys(command)).isEqualTo(ImmutableSet.of("ref", "task", "kind", "title", "detail", "sandboxed", "decisions", "byWords"));
        assertThat(command.path("kind").asText()).isEqualTo("command");
        assertThat(command.path("detail").asText()).hasSizeLessThanOrEqualTo(400).endsWith("…");
        assertThat(command.path("sandboxed").asBoolean()).isTrue();
        assertThat(command.path("byWords").asBoolean()).isTrue();
        assertThat(command.path("decisions").toString()).isEqualTo("[\"allow_once\",\"deny\"]");
        JsonNode question = waiting.path(1);
        assertThat(keys(question)).isEqualTo(ImmutableSet.of("ref", "task", "kind", "title", "questions", "sandboxed", "decisions", "byWords"));
        assertThat(question.path("kind").asText()).isEqualTo("question");
        assertThat(question.path("byWords").asBoolean()).isTrue();
        assertThat(keys(question.path("questions").path(0))).isEqualTo(ImmutableSet.of("question", "options", "multiSelect"));
        assertThat(question.path("questions").path(0).path("options").toString()).isEqualTo("[\"Metadata table\",\"Both\"]");
        JsonNode asked = waiting.path(2);
        assertThat(keys(asked)).isEqualTo(ImmutableSet.of("ref", "task", "question"));
        assertThat(asked.path("question").asText()).hasSizeLessThanOrEqualTo(160).endsWith("…");

        JsonNode screen = read(tool, "screen").path("entries");
        assertThat(keys(screen.path(0))).isEqualTo(ImmutableSet.of("ref", "title", "author", "mine", "state", "ci", "review", "mergeBlocked", "pendingReviewers",
                "lastActivity"));
        assertThat(screen.path(0).path("ci").asText()).isEqualTo("failing");
        assertThat(screen.path(0).path("review").asText()).isEqualTo("changes_requested");
        JsonNode task = read(context(Optional.of("task:" + TASK.id()), ImmutableMap.of()).tool(), "screen").path("entries").path(0);
        assertThat(keys(task)).isEqualTo(ImmutableSet.of("ref", "title", "state", "waitingFor"));
        assertThat(task.path("waitingFor").asText()).isEqualTo("approval");
        assertThat(read(context(Optional.empty(), ImmutableMap.of()).tool(), "screen").path("entries")).isEmpty();

        JsonNode options = read(tool, "start_options");
        assertThat(keys(options)).isEqualTo(ImmutableSet.of("kind", "repositories", "agents", "automation"));
        assertThat(keys(options.path("repositories").path(0))).isEqualTo(ImmutableSet.of("ref", "name", "agent", "model"));
        assertThat(options.path("repositories").path(0).path("ref").asText()).isEqualTo("ws:ws1");
        // Only an agent that is ready now can start a task.
        assertThat(options.path("agents").toString()).isEqualTo("[{\"id\":\"claude\",\"label\":\"Claude Code\"}]");
        assertThat(keys(options.path("automation"))).isEqualTo(ImmutableSet.of("autoDraftPr", "autoCiFix", "reviewSummary", "reviewReply"));
    }

    @Test
    void readsTheAgendaFreshOnEveryCallWithoutRecapOrACalendarRead()
            throws Exception
    {
        AgendaCalendar busy = new AgendaCalendar("used", 2.5, false, ImmutableList.of(new AgendaCalendar.Block("10:00", "12:30")), ImmutableList.of());
        Instant made = NOW.minusSeconds(7200);
        List<AgendaDay> days = ImmutableList.of(day(TODAY, AgendaDay.State.READY, made, Optional.of(busy)), day(TODAY.minusDays(1), AgendaDay.State.READY, NOW.minusSeconds(90000),
                Optional.empty()));
        when(agendas.recent(anyInt())).thenReturn(days);
        ReviewTool tool = context(Optional.empty(), ImmutableMap.of()).tool();

        read(tool, "overview");
        read(tool, "changes");

        // What moved is new since the agenda the user last saw, and the busy times are the ones that agenda read.
        verify(agenda, times(2)).read(TODAY, made, false, days, busy);

        when(agendas.recent(anyInt())).thenReturn(ImmutableList.of(day(TODAY, AgendaDay.State.FAILED, NOW, Optional.empty())));
        read(tool, "overview");
        verify(agenda).read(TODAY, NOW.minus(Duration.ofDays(3)), false, ImmutableList.of(day(TODAY, AgendaDay.State.FAILED, NOW, Optional.empty())),
                AgendaCalendar.of("off"));
    }

    @Test
    void servedRefsAccumulateOverTheDay()
            throws Exception
    {
        AssistantContext first = context(Optional.empty(), ImmutableMap.of());
        read(first.tool(), "tasks");
        read(first.tool(), "waiting");
        assertThat(first.served()).containsEntry("task:t1", new ServedRef("tasks", Optional.of(AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", "t1"))))
                .containsEntry("pr:trinodb/trino#31402", new ServedRef("tasks", Optional.of(AgendaTarget.pull("trinodb/trino", 31402, Optional.of("ws1")))))
                .containsEntry("task:" + TASK.id(), new ServedRef("waiting", Optional.of(AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", TASK.id().toString()))));
        // An approval is answered only from the cards on screen when the message was sent, never from a ref served earlier.
        assertThat(first.served().keySet()).noneMatch(ref -> ref.startsWith("approval:"));

        AssistantContext second = context(Optional.empty(), first.served());
        read(second.tool(), "running");
        read(second.tool(), "start_options");
        assertThat(second.served()).containsAllEntriesOf(first.served())
                .containsEntry("ws:ws1", new ServedRef("start_options", Optional.empty()));
        // The first page that named a ref keeps it.
        assertThat(second.served().get("task:" + TASK.id()).kind()).isEqualTo("waiting");
    }

    @Test
    void looksUpAnyItemByRefAndReadsWhatATaskSaidLast()
            throws Exception
    {
        AssistantContext context = context(Optional.empty(), ImmutableMap.of());
        ReviewTool tool = context.tool();

        JsonNode pull = named(tool, "lookup", "pr:trinodb/trino#31402").path("entries").path(0);
        assertThat(keys(pull)).isEqualTo(ImmutableSet.of("ref", "title", "author", "mine", "state", "ci", "review", "mergeBlocked", "pendingReviewers", "lastActivity"));
        assertThat(pull.path("pendingReviewers").toString()).isEqualTo("[\"marlo-t\"]");
        // Until the login is known, how the pull request was found says whose it is; once known, the login decides, as the pull request list does.
        assertThat(pull.path("mine").asBoolean()).isTrue();
        assertThat(pull.path("mergeBlocked").asBoolean()).isFalse();
        when(credentials.cachedLogin()).thenReturn(Optional.of("marlo-t"));
        assertThat(named(tool, "lookup", "pr:trinodb/trino#31402").path("entries").path(0).path("mine").asBoolean()).isFalse();
        JsonNode work = named(tool, "lookup", "work:w22").path("entries").path(0);
        assertThat(keys(work)).isEqualTo(ImmutableSet.of("ref", "title", "state", "priority", "blocked"));
        assertThat(work.path("state").asText()).isEqualTo("planned");
        assertThat(work.path("blocked").asBoolean()).isTrue();
        assertThat(keys(named(tool, "lookup", "task:" + TASK.id()).path("entries").path(0))).isEqualTo(ImmutableSet.of("ref", "title", "state", "waitingFor"));
        // Something ByteQuay holds nothing about reads as an empty page, not as an error to repair.
        assertThat(named(tool, "lookup", "pr:trinodb/trino#1").path("entries")).isEmpty();

        JsonNode result = named(tool, "result", "task:" + FINISHED.id()).path("entries").path(0);
        assertThat(keys(result)).isEqualTo(ImmutableSet.of("ref", "title", "state", "turn", "reason", "said"));
        assertThat(result.path("turn").asText()).isEqualTo("failed");
        assertThat(result.path("reason").asText()).isEqualTo("The provider stopped answering");
        assertThat(keys(result.path("said"))).isEqualTo(ImmutableSet.of("by", "text", "at"));
        assertThat(result.path("said").path("by").asText()).isEqualTo("agent");
        assertThat(result.path("said").path("text").asText()).hasSizeLessThanOrEqualTo(400).endsWith("…");

        // Each item the agent named joins the day's refs, so a later message may act on it.
        assertThat(context.served()).containsEntry("pr:trinodb/trino#31402", new ServedRef("lookup", Optional.of(AgendaTarget.pull("trinodb/trino", 31402, Optional.of("ws1")))))
                .containsEntry("work:w22", new ServedRef("lookup", Optional.of(AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", "w22"))))
                .containsEntry("task:" + FINISHED.id(), new ServedRef("result", Optional.of(AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", FINISHED.id().toString()))));
    }

    @Test
    void searchesTheStoredItemsByTitleNumberOrKey()
            throws Exception
    {
        when(pullRequests.findAttentionCandidates()).thenReturn(ImmutableList.of(pull(7, 31402, "Retry Iceberg commit on conflict", NOW.minusSeconds(60)),
                pull(8, 31377, "Add Iceberg snapshot cache", NOW.minusSeconds(7200))));
        when(issues.findPage(1, RepoRef.of("trinodb", "trino"), 0, 200)).thenReturn(new RepositoryIssuePage(1, RepoRef.of("trinodb", "trino"),
                ImmutableList.of(new RepositoryIssue(901, 77, "Snapshot ids in $snapshots", "body", "marlo-t", "", "https://github.com/trinodb/trino/issues/77", ImmutableList.of(),
                        ImmutableList.of(), 0, NOW.minusSeconds(86400), NOW.minusSeconds(600))), 0, 200, 1, false, Optional.empty()));
        when(jira.cachedIssues()).thenReturn(ImmutableList.of(new JiraIssue("connection", "cloud", "10042", "ICE-7", "https://company.atlassian.net/browse/ICE-7",
                "Expire snapshots by branch", "As described", "Story", new JiraDtos.Status("3", "In progress", "indeterminate"), Optional.empty(), ImmutableList.of(),
                Optional.empty(), Optional.empty(), false, false, NOW.minusSeconds(300), Optional.empty(), Optional.empty(), ImmutableList.of(), false, Optional.empty(),
                Optional.of(new JiraDtos.WorkspaceLink("ws1", "Trino")))));
        when(tasks.listRecent(500)).thenReturn(ImmutableList.of(TASK, ASKING, FINISHED));
        when(workItems.list("ws1")).thenReturn(ImmutableList.of(work("w22", "Expose snapshot id in metadata", Optional.of("LOCAL:w1"), NOW),
                work("w1", "Iceberg write path", Optional.empty(), NOW.minusSeconds(9000))));
        when(agenda.epicSource(any(), eq("LOCAL:w1"))).thenReturn(Optional.of(new AgendaReader.EpicSource("Iceberg snapshots",
                AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", "w1"))));
        AssistantContext context = context(Optional.empty(), ImmutableMap.of());
        ReviewTool tool = context.tool();

        // Every word of the query in a title, the newest first, whichever store holds it.
        JsonNode snapshots = search(tool, "{\"kind\":\"search\",\"query\":\"Snapshot\"}");
        assertThat(keys(snapshots)).isEqualTo(ImmutableSet.of("kind", "query", "page", "entries", "nextPage"));
        assertThat(refs(snapshots)).containsExactly("work:w22", "epic:LOCAL:w1", "jira:cloud:10042", "issue:trinodb/trino#77", "task:" + ASKING.id(), "pr:trinodb/trino#31377");
        assertThat(keys(snapshots.path("entries").path(2))).isEqualTo(ImmutableSet.of("ref", "title", "state", "at"));
        assertThat(snapshots.path("entries").path(2).path("title").asText()).isEqualTo("ICE-7 Expire snapshots by branch");
        assertThat(snapshots.path("entries").path(2).path("state").asText()).isEqualTo("In progress");
        assertThat(snapshots.path("nextPage").isNull()).isTrue();
        assertThat(refs(search(tool, "{\"kind\":\"search\",\"query\":\"iceberg  cache\"}"))).containsExactly("pr:trinodb/trino#31377");
        // A number or key the user names comes first.
        assertThat(refs(search(tool, "{\"kind\":\"search\",\"query\":\"#31402\"}"))).containsExactly("pr:trinodb/trino#31402");
        assertThat(refs(search(tool, "{\"kind\":\"search\",\"query\":\"ice-7\"}")).getFirst()).isEqualTo("jira:cloud:10042");

        // What a search found joins the day's refs, so a later message may act on it.
        assertThat(context.served()).containsEntry("work:w22", new ServedRef("search", Optional.of(AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", "w22"))))
                .containsEntry("epic:LOCAL:w1", new ServedRef("search", Optional.of(AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", "w1"))))
                .containsEntry("pr:trinodb/trino#31402", new ServedRef("search", Optional.of(AgendaTarget.pull("trinodb/trino", 31402, Optional.of("ws1")))));
        // Tickets come from Jira's cache; a search never asks Jira.
        verify(jira, times(4)).cachedIssues();
        verifyNoMoreInteractions(jira);
    }

    @Test
    void searchPagesTwentyAtATime()
            throws Exception
    {
        when(issues.findPage(eq(1L), any(), anyInt(), eq(200))).thenReturn(new RepositoryIssuePage(1, RepoRef.of("trinodb", "trino"), ImmutableList.of(), 0, 200, 0, false,
                Optional.empty()));
        when(workItems.list("ws1")).thenReturn(IntStream.range(0, 25).mapToObj(index -> work("w" + index, "Snapshot step " + index, Optional.empty(), NOW.minusSeconds(index)))
                .collect(toImmutableList()));
        AssistantContext context = context(Optional.empty(), ImmutableMap.of());
        ReviewTool tool = context.tool();

        JsonNode first = search(tool, "{\"kind\":\"search\",\"query\":\"snapshot step\"}");
        assertThat(first.path("entries")).hasSize(20);
        assertThat(first.path("nextPage").asInt()).isEqualTo(1);
        JsonNode second = search(tool, "{\"kind\":\"search\",\"query\":\"snapshot step\",\"page\":1}");
        assertThat(refs(second)).containsExactly("work:w20", "work:w21", "work:w22", "work:w23", "work:w24");
        assertThat(second.path("nextPage").isNull()).isTrue();
        assertThat(context.served()).hasSize(25);

        assertThat(error(tool, "{\"kind\":\"search\"}")).isEqualTo("search needs a query of at least 2 characters");
        assertThat(error(tool, "{\"kind\":\"search\",\"query\":\"snapshot\",\"ref\":\"work:w1\"}")).isEqualTo("Unknown field: ref");
        assertThat(error(tool, "{\"kind\":\"lookup\",\"ref\":\"work:w1\",\"query\":\"snapshot\"}")).isEqualTo("Unknown field: query");
        assertThat(error(tool, "{\"kind\":\"search\",\"query\":\"snapshot\",\"page\":-1}")).isEqualTo("page must be a non-negative integer");
    }

    @Test
    void answersABadCallWithAnErrorItCanRepair()
            throws Exception
    {
        AssistantContext context = context(Optional.empty(), ImmutableMap.of());
        ReviewTool tool = context.tool();

        assertThat(error(tool, "{\"kind\":\"pull_request\"}")).startsWith("kind must be one of overview, changes");
        assertThat(error(tool, "{\"kind\":\"screen\",\"ref\":\"pr:trinodb/trino#1\"}")).isEqualTo("Unknown field: ref");
        assertThat(error(tool, "{\"kind\":\"lookup\"}")).isEqualTo("lookup needs a ref");
        assertThat(error(tool, "{\"kind\":\"lookup\",\"ref\":\"jira:cloud:10042\"}")).isEqualTo("lookup reads pr, task, review and work refs");
        assertThat(error(tool, "{\"kind\":\"result\",\"ref\":\"pr:trinodb/trino#31402\"}")).isEqualTo("result reads task and review refs");
        assertThat(error(tool, "{\"kind\":\"tasks\",\"page\":-1}")).contains("non-negative");
        assertThat(error(tool, "not json")).isEqualTo("arguments are not JSON");
        when(running.current()).thenThrow(new IllegalStateException("database is locked"));
        assertThat(error(tool, "{\"kind\":\"running\"}")).isEqualTo("ByteQuay could not read that now: database is locked");
        assertThat(context.served()).isEmpty();
    }

    private AssistantContext context(Optional<String> onScreen, Map<String, ServedRef> served)
    {
        List<String> visible = ImmutableList.of("approval:" + COMMAND, "approval:" + QUESTION, "question:turn-9", "approval:0b1d5f8e-5d7a-4f0e-9a52-6a4f1c2b3d09");
        return new AssistantContext(reader, TODAY, visible, onScreen, served, mapper);
    }

    private JsonNode read(ReviewTool tool, String kind)
            throws Exception
    {
        return mapper.readTree(tool.call().apply("{\"kind\":\"" + kind + "\"}"));
    }

    private JsonNode named(ReviewTool tool, String kind, String ref)
            throws Exception
    {
        return mapper.readTree(tool.call().apply("{\"kind\":\"" + kind + "\",\"ref\":\"" + ref + "\"}"));
    }

    private JsonNode search(ReviewTool tool, String arguments)
            throws Exception
    {
        return mapper.readTree(tool.call().apply(arguments));
    }

    private static List<String> refs(JsonNode page)
    {
        return ImmutableList.copyOf(page.path("entries").findValuesAsText("ref"));
    }

    private String error(ReviewTool tool, String arguments)
            throws Exception
    {
        return mapper.readTree(tool.call().apply(arguments)).path("error").path("message").asText();
    }

    private static Set<String> keys(JsonNode node)
    {
        return ImmutableSet.copyOf(node.fieldNames());
    }

    private static AgentTask task(String title, String lane)
    {
        return AgentTask.created(TaskId.random(), Optional.of("ws1"), true, title, new AgentLaneId(lane), new RepositoryIdentity("trinodb", "trino"),
                Path.of("/tmp/assistant-task"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW.minusSeconds(3600));
    }

    private static PullRequest pull(long id, int number, String title, Instant at)
    {
        return new PullRequest(id, "trinodb/trino", number, title, "elric-t", "https://github.com/trinodb/trino/pull/" + number, NOW, NOW, PullRequest.Origin.AUTHORED,
                ImmutableList.of(), ImmutableMap.of(), false, null, null, null, ImmutableList.of(), PullRequestDetail.CiStatus.PASSING, 10, 2, 3, "open", null, null, true,
                "clean", NOW, "retry", null, at, null, null);
    }

    private static WorkItem work(String id, String title, Optional<String> epicId, Instant at)
    {
        return new WorkItem(id, "ws1", title, WorkItem.Kind.DEVELOP, "", WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.PLANNED, 0,
                ImmutableList.of(), ImmutableList.of(), at, at, WorkItem.Priority.NORMAL, ImmutableList.of(), epicId, 0, ImmutableList.of());
    }

    private static AppNotice notice(String id, AppNotice.Kind kind, AgentTask task, String message)
    {
        return new AppNotice(id, kind, task.id().toString(), "ws1", Optional.of("turn-9"), Optional.empty(), task.title(), Optional.empty(), Optional.empty(), Optional.empty(),
                message, "trinodb/trino", task.laneId().value(), NOW);
    }

    private static AgentApproval pending(ApprovalId id, AgentTask task, ProviderApprovalRequest request)
    {
        return AgentApproval.pending(new ApprovalRequest(id, task.id(), TurnId.random(), request, NOW));
    }

    private static ModelSettingsService.Provider provider(String id, String label, AgentProviderStatus.Readiness readiness)
    {
        return new ModelSettingsService.Provider(id, label, Optional.empty(), readiness, Optional.empty(), "", Optional.empty(),
                new AgentCapabilities(true, true, true, ImmutableSet.of(AgentPermissionPolicy.AUTO), ImmutableSet.of()), ImmutableList.of(), "", "");
    }

    private static AgendaDay day(LocalDate day, AgendaDay.State state, Instant at, Optional<AgendaCalendar> calendar)
    {
        return new AgendaDay(day, state, at, at.minusSeconds(86400), "claude", "Opus 5.5", "", ImmutableList.of(), Optional.empty(), ImmutableMap.of(), calendar,
                state == AgendaDay.State.FAILED ? Optional.of("The agent stopped") : Optional.empty());
    }
}
