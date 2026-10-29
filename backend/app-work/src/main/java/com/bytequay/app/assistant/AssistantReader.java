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
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.AutomationLevel;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.app.agenda.AgendaPages;
import com.bytequay.app.agenda.AgendaReader;
import com.bytequay.app.agenda.AgendaRefs;
import com.bytequay.app.agenda.AgendaService;
import com.bytequay.app.agenda.AgendaSnapshot;
import com.bytequay.app.agenda.AgendaView;
import com.bytequay.app.agent.RunningTask;
import com.bytequay.app.agent.RunningTaskReader;
import com.bytequay.app.agent.TaskActivity;
import com.bytequay.app.agent.TaskActivityReader;
import com.bytequay.app.agent.TaskMessage;
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
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.AgendaStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.agent.spi.ProviderApprovalRequest.QUESTION_TOOL;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

/**
 * Reads what the assistant's {@code assistant_context} answers, fresh on every call: the agenda's pages over the stores as they are
 * now, the tasks at work, the cards a message lists as visible, the page it was sent from, any item named by its ref or found by its
 * title, number or key, what a task said last, and what a new task can start with. Like the agenda's reader, it reads only what
 * ByteQuay stores: no GitHub, Jira, calendar or provider request is made here.
 */
@Component
class AssistantReader
{
    private static final int TITLE = 80;
    private static final int LINE = 160;
    private static final int DETAIL = 400;
    private static final Set<String> LOOKUP_REFS = ImmutableSet.of("pr", "task", "review", "work");
    private static final Set<String> RESULT_REFS = ImmutableSet.of("task", "review");

    private final AgendaReader agenda;
    private final AgendaStore agendas;
    private final AgendaService agendaService;
    private final RunningTaskReader running;
    private final AppNoticeService notices;
    private final AgentApprovalStore approvals;
    private final AgentTaskStore tasks;
    private final TaskActivityReader activities;
    private final AgentReviewStore reviews;
    private final PullRequestStore pullRequests;
    private final WorkItemStore workItems;
    private final WorkspaceStore workspaces;
    private final AssistantRows rows;
    private final CredentialService credentials;
    private final ModelSettingsService models;
    private final AppSettingsService settings;
    private final ObjectMapper mapper;
    private final Clock clock;

    AssistantReader(
            AgendaReader agenda,
            AgendaStore agendas,
            AgendaService agendaService,
            RunningTaskReader running,
            AppNoticeService notices,
            AgentApprovalStore approvals,
            AgentTaskStore tasks,
            TaskActivityReader activities,
            AgentReviewStore reviews,
            PullRequestStore pullRequests,
            WorkItemStore workItems,
            WorkspaceStore workspaces,
            AssistantRows rows,
            CredentialService credentials,
            ModelSettingsService models,
            AppSettingsService settings,
            ObjectMapper mapper,
            Clock clock)
    {
        this.agenda = requireNonNull(agenda, "agenda is null");
        this.agendas = requireNonNull(agendas, "agendas is null");
        this.agendaService = requireNonNull(agendaService, "agendaService is null");
        this.running = requireNonNull(running, "running is null");
        this.notices = requireNonNull(notices, "notices is null");
        this.approvals = requireNonNull(approvals, "approvals is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.activities = requireNonNull(activities, "activities is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.workItems = requireNonNull(workItems, "workItems is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.rows = requireNonNull(rows, "rows is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.models = requireNonNull(models, "models is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * One kind's page and the refs it names.
     *
     * @param refs each ref with where it opens; empty for a repository
     */
    record Page(Object body, Map<String, Optional<AgendaTarget>> refs)
    {
        Page
        {
            requireNonNull(body, "body is null");
            refs = ImmutableMap.copyOf(refs);
        }
    }

    /** The agenda's pages as the stores stand now: new since the last ready agenda, no recap, and the calendar today's agenda read. */
    AgendaSnapshot agenda(LocalDate today)
    {
        List<AgendaDay> earlier = agendas.recent(AgendaService.EARLIER_DAYS + 1);
        Instant since = earlier.stream().filter(day -> day.state() == AgendaDay.State.READY).map(AgendaDay::generatedAt).findFirst()
                .orElse(clock.instant().minus(AgendaService.FIRST_LOOK));
        // Reading the calendar again would ask Google; the busy times today's agenda read are enough to talk about the day.
        AgendaCalendar calendar = earlier.stream().filter(day -> day.day().equals(today)).findFirst().flatMap(AgendaDay::calendar).orElse(AgendaCalendar.of("off"));
        return agenda.read(today, since, false, earlier, calendar);
    }

    /** Today's agenda as Home shows it: whether it is made yet, its summary, and each item with whether it is done. */
    Page today()
    {
        AgendaView view = agendaService.today(Optional.empty());
        Map<String, Optional<AgendaTarget>> refs = new LinkedHashMap<>();
        view.items().forEach(item -> refs.put(item.ref(), Optional.of(item.target())));
        List<Planned> items = view.items().stream().map(item -> new Planned(item.ref(), item.title(), item.why(), item.status())).collect(toImmutableList());
        return new Page(new Today("today", view.state().toLowerCase(Locale.ROOT), view.summary(), items, view.failure()), refs);
    }

    /** Tasks at work, waiting on the user, or recently settled, as the sidebar lists them. */
    Page running()
    {
        Map<String, String> agents = models.get().providers().stream().collect(toImmutableMap(ModelSettingsService.Provider::id, ModelSettingsService.Provider::label));
        ImmutableList.Builder<Running> entries = ImmutableList.builder();
        Map<String, Optional<AgendaTarget>> refs = new LinkedHashMap<>();
        for (RunningTask task : running.current()) {
            // A cherry-pick or CI repair opens from its pull request, not from a page of its own.
            Optional<AgendaTarget> target = switch (task.kind()) {
                case TASK -> Optional.of(AgendaTarget.of(AgendaTarget.Kind.TASK, task.workspaceId(), task.id()));
                case REVIEW -> Optional.of(AgendaTarget.of(AgendaTarget.Kind.REVIEW, task.workspaceId(), task.targetId()));
                case CHERRY_PICK, CI_FIX -> Optional.empty();
            };
            Optional<String> ref = target.map(AssistantReader::ref);
            ref.ifPresent(value -> refs.put(value, target));
            String agent = tasks.find(TaskId.valueOf(task.id())).map(found -> found.laneId().value()).map(lane -> agents.getOrDefault(lane, lane)).orElse("");
            entries.add(new Running(ref, AgendaReader.clip(task.title(), TITLE), task.repository(), agent, lower(task.status()), task.startedAt()));
        }
        return new Page(new Entries("running", entries.build()), refs);
    }

    /** The cards a message lists as visible that still wait: approvals with what may answer them, and the questions turns left. */
    Page waiting(List<String> visible)
    {
        Map<String, AppNotice> shown = notices.current().stream().filter(notice -> visible.contains(notice.id())).collect(toImmutableMap(AppNotice::id, notice -> notice));
        Map<String, ProviderApprovalRequest> pending = approvals.listPending().stream().map(AgentApproval::request)
                .collect(toImmutableMap(request -> "approval:" + request.id(), ApprovalRequest::providerRequest));
        ImmutableList.Builder<Object> entries = ImmutableList.builder();
        Map<String, Optional<AgendaTarget>> refs = new LinkedHashMap<>();
        for (String ref : visible.stream().distinct().toList()) {
            AppNotice notice = shown.get(ref);
            if (notice == null || !(pending.containsKey(ref) || ref.startsWith("question:"))) {
                // Answered or gone since the message was sent, or a card with nothing to answer.
                continue;
            }
            AgendaTarget target = notice.reviewId().map(review -> AgendaTarget.of(AgendaTarget.Kind.REVIEW, notice.workspaceId(), review))
                    .orElseGet(() -> AgendaTarget.of(AgendaTarget.Kind.TASK, notice.workspaceId(), notice.taskId()));
            String task = ref(target);
            refs.put(task, Optional.of(target));
            entries.add(pending.containsKey(ref) ? approval(ref, task, pending.get(ref)) : new Asked(ref, task, AgendaReader.clip(notice.message(), LINE)));
        }
        return new Page(new Entries("waiting", entries.build()), refs);
    }

    /** A line for each card a message lists as visible that still shows: its ref, its task's title and what it says, clipped. */
    List<String> cards(List<String> visible)
    {
        return notices.current().stream().filter(notice -> visible.contains(notice.id()))
                .map(notice -> notice.id() + ": " + AgendaReader.clip(notice.title(), TITLE) + " · " + AgendaReader.clip(notice.message(), TITLE))
                .collect(toImmutableList());
    }

    /** Facts about the page a message was sent from. */
    Page screen(Optional<String> onScreen)
    {
        return onScreen.flatMap(ref -> facts("screen", ref)).orElseGet(() -> nothing("screen"));
    }

    /** Facts about one item the user names, wherever it is: a pull request, a task or review, or a work item. */
    Page lookup(String ref)
    {
        checkArgument(LOOKUP_REFS.contains(AgendaRefs.kind(ref)), "lookup reads pr, task, review and work refs");
        return facts("lookup", ref).orElseGet(() -> nothing("lookup"));
    }

    /** What a task or review said last, by its agent or by the user, and how its latest turn ended. */
    Page result(String ref)
    {
        checkArgument(RESULT_REFS.contains(AgendaRefs.kind(ref)), "result reads task and review refs");
        return named(ref).map(named -> {
            TaskActivity activity = activities.read(ImmutableList.of(named.task())).get(named.task().id());
            Optional<AgentTurn> latest = activity.latestTurn();
            Optional<Said> said = activity.lastMessage()
                    .map(message -> new Said(message.author() == TaskMessage.Author.AGENT ? "agent" : "user", AgendaReader.clip(message.text(), DETAIL), message.at()));
            Result entry = new Result(named.ref(), AgendaReader.clip(named.task().title(), TITLE), lower(named.task().state()), latest.map(turn -> lower(turn.state())),
                    latest.flatMap(AgentTurn::terminalReason).map(reason -> AgendaReader.clip(reason, LINE)), said);
            return new Page(new Entries("result", ImmutableList.of(entry)), ImmutableMap.of(named.ref(), named.target()));
        }).orElseGet(() -> nothing("result"));
    }

    /** One page of the stored items whose title, number or key matches the query, exact numbers and keys first, then the newest. */
    Page search(String query, int page)
    {
        checkArgument(query.strip().length() >= 2, "search needs a query of at least 2 characters");
        List<AssistantRows.Found> found = rows.search(query);
        int from = Math.min(found.size(), page * AgendaPages.PAGE);
        int to = Math.min(found.size(), from + AgendaPages.PAGE);
        Map<String, Optional<AgendaTarget>> refs = new LinkedHashMap<>();
        ImmutableList.Builder<Match> entries = ImmutableList.builder();
        for (AssistantRows.Found item : found.subList(from, to)) {
            refs.put(item.ref(), Optional.of(item.target()));
            entries.add(new Match(item.ref(), AgendaReader.clip(item.title(), TITLE), item.state(), item.at()));
        }
        return new Page(new Searched("search", query.strip(), page, entries.build(), to < found.size() ? Optional.of(page + 1) : Optional.empty()), refs);
    }

    /** Where a new task can start: each watched repository with its Development default, the agents ready now, and the saved automation. */
    Page startOptions()
    {
        ImmutableList.Builder<Repository> repositories = ImmutableList.builder();
        Map<String, Optional<AgendaTarget>> refs = new LinkedHashMap<>();
        for (Workspace workspace : workspaces.findAll()) {
            String ref = "ws:" + workspace.id();
            AgentModelSetting development = models.agentModel(AgentModelType.DEVELOPMENT, Optional.of(workspace.id()));
            repositories.add(new Repository(ref, workspace.monitoredRepository().fullName(), development.configId(), development.modelLabel()));
            refs.put(ref, Optional.empty());
        }
        List<Agent> agents = models.get().providers().stream().filter(provider -> provider.readiness() == AgentProviderStatus.Readiness.READY)
                .map(provider -> new Agent(provider.id(), provider.label())).collect(toImmutableList());
        return new Page(new StartOptions("start_options", repositories.build(), agents, settings.developmentAutomation().level()), refs);
    }

    private Optional<Page> facts(String kind, String ref)
    {
        return switch (AgendaRefs.kind(ref)) {
            case "pr" -> pullRequest(kind, ref);
            case "task", "review" -> named(ref).map(named -> task(kind, named));
            case "work" -> workItems.find(AgendaRefs.id(ref)).map(item -> work(kind, item));
            default -> Optional.empty();
        };
    }

    /** The task a task or review ref names, with the ref in its usual form and where it opens. */
    private Optional<Named> named(String ref)
    {
        return switch (AgendaRefs.kind(ref)) {
            case "task" -> AssistantRows.taskId(AgendaRefs.id(ref)).flatMap(tasks::find).map(task -> new Named(AgendaRefs.task(task.id().toString()), task,
                    task.workspaceId().map(workspace -> AgendaTarget.of(AgendaTarget.Kind.TASK, workspace, task.id().toString()))));
            case "review" -> reviews.findReview(AgendaRefs.id(ref)).flatMap(review -> AssistantRows.taskId(review.taskId()).flatMap(tasks::find)
                    .map(task -> new Named(AgendaRefs.review(review.id()), task, Optional.of(AgendaTarget.of(AgendaTarget.Kind.REVIEW, review.workspaceId(), review.id())))));
            default -> Optional.empty();
        };
    }

    private Optional<Page> pullRequest(String kind, String ref)
    {
        return AgendaRefs.numbered(ref).flatMap(number -> pullRequests.findIdByRepoAndNumber(number.repo(), number.number())).flatMap(pullRequests::findById).map(pr -> {
            String canonical = AgendaRefs.pull(pr.repo(), pr.number());
            Optional<String> workspace = workspaces.findAll().stream().filter(Workspace::isGitHub)
                    .filter(watched -> watched.monitoredRepository().fullName().equalsIgnoreCase(pr.repo()))
                    .map(Workspace::id).findFirst();
            PullRequestFacts facts = new PullRequestFacts(canonical, AgendaReader.clip(pr.title(), TITLE), pr.author(), pr.mine(credentials.cachedLogin()),
                    AssistantRows.state(pr), Optional.ofNullable(pr.ciStatus()).map(AssistantReader::lower),
                    Optional.ofNullable(pr.reviewDecision()).map(decision -> decision.toLowerCase(Locale.ROOT)), pr.mergeBlocked(), pr.requestedReviewers(),
                    Optional.ofNullable(pr.activityAt()).or(() -> Optional.ofNullable(pr.updatedAt())));
            return new Page(new Entries(kind, ImmutableList.of(facts)), ImmutableMap.of(canonical, Optional.of(AgendaTarget.pull(pr.repo(), pr.number(), workspace))));
        });
    }

    private Page task(String kind, Named named)
    {
        String id = named.task().id().toString();
        Optional<String> waitingFor = notices.current().stream().filter(notice -> notice.taskId().equals(id)).findFirst().flatMap(AgendaReader::waitingFor);
        TaskFacts facts = new TaskFacts(named.ref(), AgendaReader.clip(named.task().title(), TITLE), lower(named.task().state()), waitingFor);
        return new Page(new Entries(kind, ImmutableList.of(facts)), ImmutableMap.of(named.ref(), named.target()));
    }

    private static Page work(String kind, WorkItem item)
    {
        String ref = AgendaRefs.work(item.id());
        WorkFacts facts = new WorkFacts(ref, AgendaReader.clip(item.title(), TITLE), lower(item.state()), lower(item.priority()), !item.blockedBy().isEmpty());
        return new Page(new Entries(kind, ImmutableList.of(facts)), ImmutableMap.of(ref, Optional.of(AgendaTarget.of(AgendaTarget.Kind.WORK, item.workspaceId(), item.id()))));
    }

    private static Page nothing(String kind)
    {
        return new Page(new Entries(kind, ImmutableList.of()), ImmutableMap.of());
    }

    private Approval approval(String ref, String task, ProviderApprovalRequest request)
    {
        boolean question = request.requestedPermissions().contains(QUESTION_TOOL);
        // What the user may answer in words: Allow once or Deny on a shell command that stays in the sandbox, or a question's choice.
        boolean byWords = question || request.kind() == ProviderApprovalRequest.Kind.COMMAND && request.sandboxed();
        return new Approval(ref, task, question ? "question" : lower(request.kind()), AgendaReader.clip(request.title(), TITLE),
                question ? Optional.empty() : Optional.of(AgendaReader.clip(request.detail(), DETAIL)), question ? questions(request.detail()) : ImmutableList.of(),
                request.sandboxed(), request.availableDecisions().stream().map(AssistantReader::lower).collect(toImmutableList()), byWords);
    }

    /** The questions and option labels the card's input holds; none when that input was cut short or has another shape. */
    private List<Question> questions(String detail)
    {
        try {
            ImmutableList.Builder<Question> questions = ImmutableList.builder();
            for (JsonNode question : Optional.ofNullable(mapper.readTree(detail)).map(input -> input.path("questions")).orElse(mapper.missingNode())) {
                questions.add(new Question(AgendaReader.clip(question.path("question").asText(""), DETAIL),
                        Streams.stream(question.path("options")).map(option -> AgendaReader.clip(option.path("label").asText(""), TITLE)).collect(toImmutableList()),
                        question.path("multiSelect").asBoolean(false)));
            }
            return questions.build();
        }
        catch (JsonProcessingException unreadable) {
            return ImmutableList.of();
        }
    }

    private static String ref(AgendaTarget target)
    {
        String id = target.id().orElseThrow();
        return target.kind() == AgendaTarget.Kind.REVIEW ? AgendaRefs.review(id) : AgendaRefs.task(id);
    }

    private static String lower(Enum<?> value)
    {
        return value.name().toLowerCase(Locale.ROOT);
    }

    record Entries(String kind, List<?> entries) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Running(Optional<String> ref, String title, String repo, String agent, String phase, Instant since) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Approval(String ref, String task, String kind, String title, Optional<String> detail, List<Question> questions, boolean sandboxed, List<String> decisions,
            boolean byWords) {}

    record Question(String question, List<String> options, boolean multiSelect) {}

    record Asked(String ref, String task, String question) {}

    private record Named(String ref, AgentTask task, Optional<AgendaTarget> target) {}

    /**
     * @param mine whether the signed-in user opened it, as the pull request list decides
     * @param mergeBlocked whether GitHub blocks merging it for now, such as for a missing approval or a failing required check
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record PullRequestFacts(String ref, String title, String author, boolean mine, String state, Optional<String> ci, Optional<String> review, boolean mergeBlocked,
            List<String> pendingReviewers, Optional<Instant> lastActivity) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record TaskFacts(String ref, String title, String state, Optional<String> waitingFor) {}

    record WorkFacts(String ref, String title, String state, String priority, boolean blocked) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Result(String ref, String title, String state, Optional<String> turn, Optional<String> reason, Optional<Said> said) {}

    /** The newest message in the conversation, by the task's own agent or by the user, clipped. */
    record Said(String by, String text, Instant at) {}

    record Searched(String kind, String query, int page, List<Match> entries, Optional<Integer> nextPage) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Match(String ref, String title, Optional<String> state, Optional<Instant> at) {}

    /**
     * Today's agenda as the assistant reads it.
     *
     * @param state none before today's agenda is made, running while it is being made, ready or failed
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Today(String kind, String state, Optional<String> summary, List<Planned> items, Optional<String> failure) {}

    /**
     * One of today's items.
     *
     * @param status open, done or dismissed
     */
    record Planned(String ref, String title, String why, String status) {}

    record StartOptions(String kind, List<Repository> repositories, List<Agent> agents, AutomationLevel automation) {}

    record Repository(String ref, String name, String agent, String model) {}

    record Agent(String id, String label) {}
}
