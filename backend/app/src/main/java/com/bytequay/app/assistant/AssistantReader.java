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
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.app.agenda.AgendaReader;
import com.bytequay.app.agenda.AgendaRefs;
import com.bytequay.app.agenda.AgendaService;
import com.bytequay.app.agenda.AgendaSnapshot;
import com.bytequay.app.agent.RunningTask;
import com.bytequay.app.agent.RunningTaskReader;
import com.bytequay.app.notices.AppNotice;
import com.bytequay.app.notices.AppNoticeService;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.AgendaCalendar;
import com.bytequay.core.domain.AgendaDay;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.AgendaStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

/**
 * Reads what the assistant's {@code assistant_context} answers, fresh on every call: the agenda's pages over the stores as they are
 * now, the tasks at work, the cards a message lists as visible, the page it was sent from, and what a new task can start with. Like
 * the agenda's reader, it reads only what ByteQuay stores: no GitHub, Jira, calendar or provider request is made here.
 */
@Component
class AssistantReader
{
    private static final int TITLE = 80;
    private static final int LINE = 160;
    private static final int DETAIL = 400;
    private static final String QUESTION_TOOL = "AskUserQuestion";

    private final AgendaReader agenda;
    private final AgendaStore agendas;
    private final RunningTaskReader running;
    private final AppNoticeService notices;
    private final AgentApprovalStore approvals;
    private final AgentTaskStore tasks;
    private final AgentReviewStore reviews;
    private final PullRequestStore pullRequests;
    private final WorkspaceStore workspaces;
    private final ModelSettingsService models;
    private final AppSettingsService settings;
    private final ObjectMapper mapper;
    private final Clock clock;

    AssistantReader(
            AgendaReader agenda,
            AgendaStore agendas,
            RunningTaskReader running,
            AppNoticeService notices,
            AgentApprovalStore approvals,
            AgentTaskStore tasks,
            AgentReviewStore reviews,
            PullRequestStore pullRequests,
            WorkspaceStore workspaces,
            ModelSettingsService models,
            AppSettingsService settings,
            ObjectMapper mapper,
            Clock clock)
    {
        this.agenda = requireNonNull(agenda, "agenda is null");
        this.agendas = requireNonNull(agendas, "agendas is null");
        this.running = requireNonNull(running, "running is null");
        this.notices = requireNonNull(notices, "notices is null");
        this.approvals = requireNonNull(approvals, "approvals is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
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

    /** Tasks at work, waiting on the user, or settled in the last few minutes, as the sidebar lists them. */
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

    /** Facts about the page a message was sent from: a pull request, or a task or review. */
    Page screen(Optional<String> onScreen)
    {
        Optional<Page> page = onScreen.flatMap(ref -> switch (AgendaRefs.kind(ref)) {
            case "pr" -> pullRequest(ref);
            case "task" -> taskId(AgendaRefs.id(ref)).flatMap(tasks::find).map(task -> task(task, AgendaRefs.task(task.id().toString()),
                    task.workspaceId().map(workspace -> AgendaTarget.of(AgendaTarget.Kind.TASK, workspace, task.id().toString()))));
            case "review" -> reviews.findReview(AgendaRefs.id(ref)).flatMap(review -> taskId(review.taskId()).flatMap(tasks::find)
                    .map(task -> task(task, AgendaRefs.review(review.id()), Optional.of(AgendaTarget.of(AgendaTarget.Kind.REVIEW, review.workspaceId(), review.id())))));
            default -> Optional.empty();
        });
        return page.orElseGet(() -> new Page(new Entries("screen", ImmutableList.of()), ImmutableMap.of()));
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
        DevelopmentAutomation saved = settings.developmentAutomation();
        Automation automation = new Automation(saved.autoDraftPr(), saved.autoCiFix(), saved.reviewSummary());
        return new Page(new StartOptions("start_options", repositories.build(), agents, automation), refs);
    }

    private Optional<Page> pullRequest(String ref)
    {
        return AgendaRefs.numbered(ref).flatMap(number -> pullRequests.findIdByRepoAndNumber(number.repo(), number.number())).flatMap(pullRequests::findById).map(pr -> {
            String canonical = AgendaRefs.pull(pr.repo(), pr.number());
            Optional<String> workspace = workspaces.findAll().stream().filter(watched -> watched.monitoredRepository().fullName().equalsIgnoreCase(pr.repo()))
                    .map(Workspace::id).findFirst();
            PullRequestFacts facts = new PullRequestFacts(canonical, AgendaReader.clip(pr.title(), TITLE), pr.author(), state(pr),
                    Optional.ofNullable(pr.ciStatus()).map(AssistantReader::lower), Optional.ofNullable(pr.reviewDecision()).map(decision -> decision.toLowerCase(Locale.ROOT)));
            return new Page(new Entries("screen", ImmutableList.of(facts)), ImmutableMap.of(canonical, Optional.of(AgendaTarget.pull(pr.repo(), pr.number(), workspace))));
        });
    }

    private Page task(AgentTask task, String ref, Optional<AgendaTarget> target)
    {
        Optional<String> waitingFor = notices.current().stream().filter(notice -> notice.taskId().equals(task.id().toString())).findFirst().flatMap(AgendaReader::waitingFor);
        TaskFacts facts = new TaskFacts(ref, AgendaReader.clip(task.title(), TITLE), lower(task.state()), waitingFor);
        return new Page(new Entries("screen", ImmutableList.of(facts)), ImmutableMap.of(ref, target));
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

    private static String state(PullRequest pr)
    {
        if (pr.mergedAt() != null) {
            return "merged";
        }
        if (pr.closedAt() != null || "closed".equalsIgnoreCase(pr.state())) {
            return "closed";
        }
        return pr.draft() ? "draft" : "open";
    }

    private static String ref(AgendaTarget target)
    {
        String id = target.id().orElseThrow();
        return target.kind() == AgendaTarget.Kind.REVIEW ? AgendaRefs.review(id) : AgendaRefs.task(id);
    }

    private static Optional<TaskId> taskId(String id)
    {
        try {
            return Optional.of(TaskId.valueOf(id));
        }
        catch (IllegalArgumentException notATask) {
            return Optional.empty();
        }
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

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record PullRequestFacts(String ref, String title, String author, String state, Optional<String> ci, Optional<String> review) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record TaskFacts(String ref, String title, String state, Optional<String> waitingFor) {}

    record StartOptions(String kind, List<Repository> repositories, List<Agent> agents, Automation automation) {}

    record Repository(String ref, String name, String agent, String model) {}

    record Agent(String id, String label) {}

    record Automation(boolean autoDraftPr, boolean autoCiFix, boolean reviewSummary) {}
}
