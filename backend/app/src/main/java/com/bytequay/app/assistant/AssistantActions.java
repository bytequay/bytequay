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
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.ResolveApprovalCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.app.agenda.AgendaRefs;
import com.bytequay.app.agent.InteractiveAgentTaskStarter;
import com.bytequay.app.agent.InteractiveTaskStart;
import com.bytequay.app.agent.IssueSourceInput;
import com.bytequay.app.agent.JiraSourceInput;
import com.bytequay.app.agent.TaskInput;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.notices.AppNotice;
import com.bytequay.app.notices.AppNoticeService;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.AssistantAction;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.domain.AssistantReceipt;
import com.bytequay.core.domain.AssistantReceipt.Fix;
import com.bytequay.core.domain.AssistantReceipt.Operation;
import com.bytequay.core.domain.AssistantReceipt.Outcome;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.ServedRef;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.AssistantStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraIssue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bytequay.app.agenda.AgendaReader.clip;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/**
 * The {@code assistant_act} tool: what the assistant may do for the user, and only in the run their own message started. A call passes
 * the checks in order or is refused, and either way leaves a receipt in the conversation and a row in the action log. It sends a task
 * only the user's words, never the assistant's: a task's own messages count as the user's authority for what it does next.
 */
@Component
class AssistantActions
{
    static final String NAME = "assistant_act";
    static final int MAXIMUM_ACTIONS = 5;
    private static final int MAXIMUM_ARGUMENTS = 16 * 1024;
    private static final int REASON = 200;
    private static final int QUESTION = 400;
    private static final String QUESTION_TOOL = "AskUserQuestion";
    // Twins of the composer's own first messages, in WorkItemsScreen.tsx and AgentTasksView.tsx; change them together.
    private static final String WORK_ITEM_START = "Develop this work item: %s\n\nCall read_issue_context before starting to read this item's full context and its "
            + "parent Epic, if any. Deliver only this work item.";
    private static final String ISSUE_START = "Implement %s";
    private static final String JIRA_START = "Implement %s: %s";
    private static final String NOTE = "\n\nNote from the user, in their words to the assistant: “%s”";
    private static final Map<String, Operation> OPERATIONS = ImmutableMap.of(
            "start_task", Operation.START_TASK,
            "answer_approval", Operation.ANSWER_APPROVAL,
            "answer_question", Operation.ANSWER_QUESTION,
            "reply_to_task", Operation.REPLY_TO_TASK);
    private static final Map<Operation, Set<String>> FIELDS = ImmutableMap.of(
            Operation.START_TASK, ImmutableSet.of("op", "words", "workspace", "from", "agent"),
            Operation.ANSWER_APPROVAL, ImmutableSet.of("op", "words", "approval", "decision"),
            Operation.ANSWER_QUESTION, ImmutableSet.of("op", "words", "approval", "answers"),
            Operation.REPLY_TO_TASK, ImmutableSet.of("op", "words", "task"));
    private static final Set<String> START_FROM = ImmutableSet.of("work", "issue", "jira");
    // Words after one of these, in the same clause, are what the user said not to do: "push to main" in "don't push to main".
    private static final Pattern NEGATION = Pattern.compile("\\b(?:not|never|don't|dont|doesn't|didn't|can't|cannot|won't|wouldn't|shouldn't|mustn't|isn't|aren't|wasn't"
            + "|stop|avoid|without|neither|nor|except|instead of|rather than)\\b");
    // ponytail: a comma or a contrast ends the clause, so "don't push, open a PR" allows the PR, but a list after a comma ("don't push, merge
    // or tag") escapes; asking the user to confirm the words would close that.
    private static final Pattern CLAUSE_END = Pattern.compile("[,.!?;:]|\\b(?:but|then|otherwise)\\b");

    private final AssistantStore store;
    private final AssistantSettings permissions;
    private final AgentApprovalStore approvals;
    private final AgentApprovalService approvalService;
    private final AgentTaskStore tasks;
    private final AgentTurnService turns;
    private final InteractiveAgentTaskStarter starter;
    private final AppNoticeService notices;
    private final AgentReviewStore reviews;
    private final WorkspaceStore workspaces;
    private final WorkItemStore workItems;
    private final RepositoryIssueStore issues;
    private final JiraService jira;
    private final ModelSettingsService models;
    private final AppSettingsService settings;
    private final ObjectMapper mapper;
    private final Clock clock;

    AssistantActions(
            AssistantStore store,
            AssistantSettings permissions,
            AgentApprovalStore approvals,
            AgentApprovalService approvalService,
            AgentTaskStore tasks,
            AgentTurnService turns,
            InteractiveAgentTaskStarter starter,
            AppNoticeService notices,
            AgentReviewStore reviews,
            WorkspaceStore workspaces,
            WorkItemStore workItems,
            RepositoryIssueStore issues,
            JiraService jira,
            ModelSettingsService models,
            AppSettingsService settings,
            ObjectMapper mapper,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.permissions = requireNonNull(permissions, "permissions is null");
        this.approvals = requireNonNull(approvals, "approvals is null");
        this.approvalService = requireNonNull(approvalService, "approvalService is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.starter = requireNonNull(starter, "starter is null");
        this.notices = requireNonNull(notices, "notices is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.workItems = requireNonNull(workItems, "workItems is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.models = requireNonNull(models, "models is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * The tool for one message: its calls act only on what that message had on screen or was served that day, and leave their receipts
     * through {@code receipts}.
     */
    ReviewTool tool(AssistantItem message, AssistantContext context, Consumer<AssistantItem> receipts)
    {
        checkArgument(message.kind() == AssistantItem.Kind.USER, "only a user's message can act");
        Run run = new Run(message, context, receipts);
        Map<String, Object> schema = ImmutableMap.of(
                "type", "object",
                "properties", ImmutableMap.builder()
                        .put("op", ImmutableMap.of("type", "string", "enum", ImmutableList.copyOf(OPERATIONS.keySet())))
                        .put("words", ImmutableMap.of("type", "string", "description", "The user's exact words in their latest message that ask for this action"))
                        .put("workspace", ImmutableMap.of("type", "string", "description", "start_task without from: a ws: ref from start_options"))
                        .put("from", ImmutableMap.of("type", "string", "description", "start_task: the work:, issue: or jira: ref of the item to start"))
                        .put("agent", ImmutableMap.of("type", "string", "description", "start_task: an agent id from start_options; the repository's default when absent"))
                        .put("approval", ImmutableMap.of("type", "string", "description", "answer_approval and answer_question: the card's approval: ref"))
                        .put("decision", ImmutableMap.of("type", "string", "enum", ImmutableList.of("allow_once", "deny")))
                        .put("answers", ImmutableMap.of("type", "object", "additionalProperties", ImmutableMap.of("type", "string"),
                                "description", "answer_question: each question's text to the chosen option's label, or to the user's own words"))
                        .put("task", ImmutableMap.of("type", "string", "description", "reply_to_task: the task: ref to send the user's words to"))
                        .buildOrThrow(),
                "required", ImmutableList.of("op", "words"),
                "additionalProperties", false);
        return new ReviewTool(NAME, "Do one thing the user's latest message asks for: start a task, answer a card, or send the user's words to a task. "
                + "The app checks every call and shows the user a receipt.", schema, run::call, false);
    }

    /**
     * The receipt for a card the user answered with its own button in the panel.
     *
     * @throws IllegalArgumentException when the approval is unknown or still pending
     */
    AssistantItem buttonReceipt(ApprovalId approvalId, LocalDate day)
    {
        AgentApproval approval = approvals.find(approvalId).orElseThrow(() -> new IllegalArgumentException("Unknown approval " + approvalId));
        ApprovalDecision decision = approval.decision().orElseThrow(() -> new IllegalArgumentException("That card is not answered yet"));
        boolean question = approval.request().providerRequest().requestedPermissions().contains(QUESTION_TOOL);
        String verb = decision.decision() == ApprovalDecision.Decision.DENY ? "Denied" : question ? "Answered" : decision.forTask() ? "Allowed for this task" : "Allowed once";
        String title = tasks.find(approval.request().taskId()).map(AgentTask::title).orElse("a task");
        AssistantReceipt receipt = AssistantReceipt.done(question ? Operation.ANSWER_QUESTION : Operation.ANSWER_APPROVAL, target(approval.request().taskId()));
        return AssistantItem.receipt(day, clock.instant(), verb + " · " + clip(title, 80), receipt);
    }

    /**
     * Check 3, for every tool that acts on the user's words: why the words are not the user's to act on, or empty when they are part of
     * the message, ignoring case and runs of whitespace, at least 2 characters long, and not something the message says not to do.
     */
    static Optional<String> unsaid(AssistantItem message, String words)
    {
        if (!inMessage(message, words)) {
            return Optional.of("Those words aren't in your message");
        }
        return saysNot(message, words) ? Optional.of("Your message says not to do that") : Optional.empty();
    }

    /** Whether the words are the user's own: part of their message, ignoring case and runs of whitespace. */
    private static boolean inMessage(AssistantItem message, String words)
    {
        String said = normal(words);
        return said.length() >= 2 && normal(message.text()).contains(said);
    }

    /** Whether the message says not to do what the words say, wherever they appear in it. */
    private static boolean saysNot(AssistantItem message, String words)
    {
        return negated(normal(message.text()), normal(words));
    }

    /**
     * Check 4 for anything but a card: the refs a message may name, which are what was on screen when it was sent, the tasks of the
     * cards it showed, and what the day's reads handed out.
     */
    Set<String> shown(AssistantItem message, AssistantContext context)
    {
        List<String> cards = notices.current().stream().filter(notice -> message.visible().contains(notice.id())).map(AssistantActions::taskRef).toList();
        return ImmutableSet.<String>builder().addAll(context.served().keySet()).addAll(message.onScreen().stream().toList()).addAll(cards).build();
    }

    /** Where a task opens: its review's page for a review task, else its own. */
    private Optional<AgendaTarget> target(TaskId taskId)
    {
        Optional<AgentTask> task = tasks.find(taskId);
        if (task.isPresent() && task.orElseThrow().interactive()) {
            return task.orElseThrow().workspaceId().map(workspace -> AgendaTarget.of(AgendaTarget.Kind.TASK, workspace, taskId.toString()));
        }
        return reviews.findReviewByTask(taskId.toString()).map(review -> AgendaTarget.of(AgendaTarget.Kind.REVIEW, review.workspaceId(), review.id()));
    }

    /** What one call asked for, once its shape is valid. */
    private record Request(Operation operation, String words, JsonNode body)
    {
        String text(String field)
        {
            return body.path(field).asText("").strip();
        }

        Optional<String> optional(String field)
        {
            return Optional.of(text(field)).filter(value -> !value.isEmpty());
        }

        /** The ref the log and the repeat check name the call by. */
        String target()
        {
            return switch (operation) {
                case START_TASK -> optional("from").orElse(text("workspace"));
                case ANSWER_APPROVAL, ANSWER_QUESTION -> text("approval");
                case REPLY_TO_TASK -> text("task");
            };
        }
    }

    /** What came of one call: the line the user reads, and what the log keeps. */
    private record Result(String line, AssistantReceipt receipt, Optional<String> result)
    {
        static Result refused(Operation operation, String reason)
        {
            return new Result(reason, AssistantReceipt.refused(operation, Optional.empty()), Optional.empty());
        }

        static Result refused(Operation operation, String reason, Fix fix)
        {
            return new Result(reason, AssistantReceipt.refused(operation, Optional.of(fix)), Optional.empty());
        }
    }

    /** The tool's state for one message. Calls on it take turns, so the action count and repeats are read as they stand. */
    private final class Run
    {
        private final AssistantItem message;
        private final AssistantContext context;
        private final Consumer<AssistantItem> receipts;

        Run(AssistantItem message, AssistantContext context, Consumer<AssistantItem> receipts)
        {
            this.message = message;
            this.context = context;
            this.receipts = receipts;
        }

        synchronized String call(String arguments)
        {
            Request request;
            try {
                request = parse(arguments);
            }
            catch (JsonProcessingException invalid) {
                return reply("error", "arguments are not JSON");
            }
            catch (IllegalArgumentException invalid) {
                return reply("error", invalid.getMessage());
            }
            Result result;
            try {
                result = check(request).orElseGet(() -> act(request));
            }
            catch (RuntimeException failure) {
                // The service's own rules said no, such as an item that cannot start now; the user reads why.
                String reason = clip(Optional.ofNullable(failure.getMessage()).filter(text -> !text.isBlank()).orElse("ByteQuay could not do that"), REASON);
                result = new Result(reason, new AssistantReceipt(request.operation(), Outcome.FAILED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty()), Optional.empty());
            }
            // The words go on every receipt, done or refused, so the user sees exactly what was taken as theirs.
            AssistantReceipt receipt = result.receipt().withWords(request.words());
            Optional<String> reason = receipt.outcome() == Outcome.DONE ? Optional.empty() : Optional.of(result.line());
            store.addAction(AssistantAction.of(message, request.operation(), request.target(), request.words(), receipt.outcome(), reason, result.result(), clock.instant()));
            receipts.accept(AssistantItem.receipt(message.day(), clock.instant(), result.line(), receipt));
            return reply(receipt.outcome().name().toLowerCase(Locale.ROOT), result.line());
        }

        private Request parse(String arguments)
                throws JsonProcessingException
        {
            checkArgument(arguments.length() <= MAXIMUM_ARGUMENTS, "arguments are too long");
            JsonNode body = mapper.readTree(arguments.isBlank() ? "{}" : arguments);
            checkArgument(body.isObject(), "arguments must be an object");
            Operation operation = OPERATIONS.get(body.path("op").asText(""));
            checkArgument(operation != null, "op must be one of %s", String.join(", ", OPERATIONS.keySet()));
            body.fieldNames().forEachRemaining(field -> checkArgument(FIELDS.get(operation).contains(field), "%s takes no %s", body.path("op").asText(), field));
            checkArgument(body.path("words").isTextual(), "words are required");
            Request request = new Request(operation, body.path("words").asText(), body);
            switch (operation) {
                case START_TASK -> checkArgument(request.optional("from").isPresent() || request.optional("workspace").isPresent(), "start_task needs from or workspace");
                case ANSWER_APPROVAL -> checkArgument(ImmutableSet.of("allow_once", "deny").contains(request.text("decision")), "decision must be allow_once or deny");
                case ANSWER_QUESTION -> checkArgument(body.path("answers").isObject() && !body.path("answers").isEmpty(), "answers are required");
                case REPLY_TO_TASK -> checkArgument(request.optional("task").isPresent(), "reply_to_task needs a task: ref");
            }
            checkArgument(!request.target().isEmpty(), "%s needs a ref", body.path("op").asText());
            return request;
        }

        /** The checks before anything is done, in order; the first that fails is the refusal. */
        private Optional<Result> check(Request request)
        {
            Operation operation = request.operation();
            AssistantPermissions allowed = permissions.permissions();
            if (operation == Operation.START_TASK ? !allowed.startTasks() : !allowed.answerTasks()) {
                return Optional.of(Result.refused(operation, operation == Operation.START_TASK ? "Starting tasks is off" : "Answering tasks is off", Fix.SETTINGS));
            }
            Optional<String> unsaid = unsaid(message, request.words());
            if (unsaid.isPresent()) {
                return Optional.of(Result.refused(operation, unsaid.orElseThrow()));
            }
            if (!onScreen(request)) {
                return Optional.of(Result.refused(operation, "That isn't on screen"));
            }
            Optional<Result> state = switch (operation) {
                case START_TASK -> Optional.empty();
                case ANSWER_APPROVAL, ANSWER_QUESTION -> approvalState(request);
                case REPLY_TO_TASK -> taskState(request);
            };
            if (state.isPresent()) {
                return state;
            }
            List<AssistantAction> earlier = store.actions(message.id());
            if (earlier.size() >= MAXIMUM_ACTIONS) {
                return Optional.of(Result.refused(operation, "That's as many actions as one message can ask for"));
            }
            if (earlier.stream().anyMatch(action -> action.outcome() == Outcome.DONE && action.operation() == operation && action.target().equals(request.target())
                    && action.words().equals(request.words()))) {
                return Optional.of(Result.refused(operation, "Already done"));
            }
            return Optional.empty();
        }

        /**
         * Whether the call names only what the user could see: a card on screen when they sent the message, or a task, item or repository
         * that was on screen or that this day's reads handed out.
         */
        private boolean onScreen(Request request)
        {
            if (request.operation() == Operation.ANSWER_APPROVAL || request.operation() == Operation.ANSWER_QUESTION) {
                return message.visible().contains(request.target());
            }
            Set<String> shown = shown(message, context);
            if (request.operation() == Operation.START_TASK && request.optional("from").isPresent()) {
                return START_FROM.contains(AgendaRefs.kind(request.text("from"))) && shown.contains(request.text("from"));
            }
            String ref = request.target();
            return (request.operation() == Operation.START_TASK ? ref.startsWith("ws:") : ImmutableSet.of("task", "review").contains(AgendaRefs.kind(ref))) && shown.contains(ref);
        }

        /** A card is answered by words only while it waits, and only when it was asked before the message, which saw it. */
        private Optional<Result> approvalState(Request request)
        {
            Operation operation = request.operation();
            Optional<AgentApproval> found = approvalId(request.target()).flatMap(approvals::find);
            if (found.isEmpty()) {
                return Optional.of(Result.refused(operation, "That isn't on screen"));
            }
            AgentApproval approval = found.orElseThrow();
            if (!approval.pending()) {
                return Optional.of(Result.refused(operation, approval.decision().orElseThrow().timedOut() ? "Expired" : "Already answered"));
            }
            if (approval.request().requestedAt().isAfter(message.at())) {
                return Optional.of(Result.refused(operation, "That isn't on screen"));
            }
            ProviderApprovalRequest card = approval.request().providerRequest();
            boolean question = card.requestedPermissions().contains(QUESTION_TOOL);
            if (operation == Operation.ANSWER_QUESTION) {
                return question ? answersOffered(request, card) : Optional.of(Result.refused(operation, "Use the card's buttons: it isn't a question", Fix.BUTTONS));
            }
            if (question) {
                return Optional.of(Result.refused(operation, "Pick one of the card's options", Fix.BUTTONS));
            }
            if (card.kind() != ProviderApprovalRequest.Kind.COMMAND) {
                return Optional.of(Result.refused(operation, "Use the buttons: words answer only commands", Fix.BUTTONS));
            }
            if (!card.sandboxed()) {
                return Optional.of(Result.refused(operation, "Use the buttons: this one runs outside the sandbox", Fix.BUTTONS));
            }
            boolean deny = request.text("decision").equals("deny");
            return card.availableDecisions().contains(deny ? ProviderApprovalRequest.Decision.DENY : ProviderApprovalRequest.Decision.ALLOW_ONCE)
                    ? Optional.empty() : Optional.of(Result.refused(operation, "Use the card's buttons", Fix.BUTTONS));
        }

        /** Each answer names a question on the card and is one of its options, or the user's own words. */
        private Optional<Result> answersOffered(Request request, ProviderApprovalRequest card)
        {
            Map<String, Offered> offered = questions(card.detail());
            for (Map.Entry<String, JsonNode> answer : request.body().path("answers").properties()) {
                Optional<String> question = question(offered, answer.getKey());
                String chosen = answer.getValue().asText("").strip();
                Optional<String> label = question.flatMap(asked -> option(offered.get(asked), chosen));
                if (question.isEmpty() || chosen.isEmpty() || !(label.isPresent() || inMessage(message, chosen))) {
                    return Optional.of(Result.refused(request.operation(), "Pick one of the card's options", Fix.BUTTONS));
                }
                // An option the message turns down, such as "the metadata table" in "not the metadata table", is not the user's answer.
                if (saysNot(message, label.orElse(chosen))) {
                    return Optional.of(Result.refused(request.operation(), "Your message says not to do that"));
                }
            }
            return Optional.empty();
        }

        /** A reply waits for an open development task that is not working now; a running task is steered from its own page. */
        private Optional<Result> taskState(Request request)
        {
            if (AgendaRefs.kind(request.target()).equals("review")) {
                return Optional.of(Result.refused(request.operation(), "Reply to a review from its page"));
            }
            Optional<AgentTask> found = taskId(AgendaRefs.id(request.target())).flatMap(tasks::find);
            if (found.isEmpty()) {
                return Optional.of(Result.refused(request.operation(), "That isn't on screen"));
            }
            AgentTask task = found.orElseThrow();
            if (task.state() != TaskState.OPEN) {
                return Optional.of(Result.refused(request.operation(), "That task has ended"));
            }
            if (!task.interactive()) {
                return Optional.of(Result.refused(request.operation(), "Reply to a review from its page"));
            }
            return task.worktreeMutationClaim().isPresent() ? Optional.of(Result.refused(request.operation(), "Task is busy; steer it from the task")) : Optional.empty();
        }

        private Result act(Request request)
        {
            return switch (request.operation()) {
                case START_TASK -> start(request);
                case ANSWER_APPROVAL -> allowOrDeny(request);
                case ANSWER_QUESTION -> answerQuestion(request);
                case REPLY_TO_TASK -> send(request);
            };
        }

        private Result allowOrDeny(Request request)
        {
            ApprovalId id = approvalId(request.target()).orElseThrow();
            boolean allow = request.text("decision").equals("allow_once");
            ApprovalDecision decided = approvalService.resolve(new ResolveApprovalCommand(id, allow ? ApprovalDecision.Decision.ALLOW : ApprovalDecision.Decision.DENY,
                    Optional.empty(), false, ImmutableMap.of()));
            return decided(id, allow ? "Allowed once · " : "Denied · ", request.operation(), decided);
        }

        private Result answerQuestion(Request request)
        {
            ApprovalId id = approvalId(request.target()).orElseThrow();
            Map<String, Offered> offered = questions(approvals.find(id).orElseThrow().request().providerRequest().detail());
            Map<String, String> answers = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> answer : request.body().path("answers").properties()) {
                String question = question(offered, answer.getKey()).orElseThrow();
                String chosen = answer.getValue().asText("").strip();
                answers.put(question, option(offered.get(question), chosen).orElse(chosen));
            }
            ApprovalDecision decided = approvalService.resolve(new ResolveApprovalCommand(id, ApprovalDecision.Decision.ALLOW, Optional.empty(), false, answers));
            Result result = decided(id, "Answered · ", request.operation(), decided);
            // What the task was sent, beside the words it was taken from.
            String sent = answers.entrySet().stream().map(answer -> clip(answer.getKey(), 60) + " → " + answer.getValue()).collect(joining(" · "));
            AssistantReceipt receipt = result.receipt();
            return new Result(result.line(), new AssistantReceipt(receipt.operation(), receipt.outcome(), Optional.of(clip(sent, REASON)), receipt.target(), receipt.turnId(),
                    receipt.fix(), receipt.words()), result.result());
        }

        private Result decided(ApprovalId id, String verb, Operation operation, ApprovalDecision decided)
        {
            TaskId taskId = approvals.find(id).orElseThrow().request().taskId();
            String title = tasks.find(taskId).map(AgentTask::title).orElse("a task");
            return new Result(verb + clip(title, 80), AssistantReceipt.done(operation, target(taskId)), Optional.of(decided.decision().name()));
        }

        private Result send(Request request)
        {
            AgentTask task = taskId(AgendaRefs.id(request.target())).flatMap(tasks::find).orElseThrow();
            TaskInput.send(turns, task.id(), task.version(), request.words(), key(request), false, Optional.empty());
            return new Result("Sent your words to " + clip(task.title(), 80), AssistantReceipt.done(Operation.REPLY_TO_TASK, target(task.id())), Optional.of(task.id().toString()));
        }

        private Result start(Request request)
        {
            Start start = request.optional("from").map(from -> item(from, request.words())).orElseGet(() -> freeText(request));
            AgentModelSetting development = models.agentModel(AgentModelType.DEVELOPMENT, Optional.of(start.workspaceId()));
            String lane = request.optional("agent").orElse(development.configId());
            Optional<ModelSettingsService.Provider> provider = models.get().providers().stream().filter(candidate -> candidate.id().equals(lane)).findFirst();
            if (provider.filter(ready -> ready.readiness() == AgentProviderStatus.Readiness.READY).isEmpty()) {
                return Result.refused(Operation.START_TASK, "That agent isn't ready");
            }
            boolean usual = lane.equals(development.configId());
            Optional<String> model = Optional.ofNullable(usual ? development.model() : provider.orElseThrow().selectedModel()).filter(value -> !value.isBlank());
            Optional<String> effort = model.isEmpty() ? Optional.empty()
                    : (usual ? development.effort() : Optional.ofNullable(provider.orElseThrow().selectedEffort())).filter(value -> !value.isBlank());
            DevelopmentAutomation automation = settings.developmentAutomation();
            TaskId taskId = new TaskId(UUID.nameUUIDFromBytes(identity(request).getBytes(StandardCharsets.UTF_8)));
            String key = key(request);
            AgentTask task = starter.start(new InteractiveTaskStart(taskId, start.workspaceId(), start.message(), new AgentLaneId(lane),
                    new AgentTaskDefaults(model, effort, AgentPermissionPolicy.AUTO, Optional.empty(), Optional.of(automation)),
                    key, Optional.of(automation), start.issue(), start.jira(), start.workItemId(), ImmutableList.of()));
            Optional<AgendaTarget> target = task.workspaceId().map(workspace -> AgendaTarget.of(AgendaTarget.Kind.TASK, workspace, task.id().toString()));
            target.ifPresent(opens -> context.serve(AgendaRefs.task(task.id().toString()), new ServedRef("act", Optional.of(opens))));
            if (!task.id().equals(taskId)) {
                // An issue that has a task already opens that task instead of starting another.
                return new Result("Already started: “" + clip(task.title(), 80) + "”", AssistantReceipt.done(Operation.START_TASK, target), Optional.of(task.id().toString()));
            }
            String agent = provider.orElseThrow().label() + " " + (usual ? development.modelLabel() : model.orElse(""));
            ImmutableList.Builder<String> detail = ImmutableList.<String>builder().add(task.repository().toString(), agent.strip());
            if (automation.autoDraftPr()) {
                detail.add("Auto draft PR");
            }
            if (automation.autoCiFix()) {
                detail.add("Auto CI fix");
            }
            Optional<String> turnId = turns.findTurnByIdempotencyKey(task.id(), key).map(turn -> turn.id().toString());
            return new Result("Started “" + clip(task.title(), 80) + "”", new AssistantReceipt(Operation.START_TASK, Outcome.DONE, Optional.of(String.join(" · ", detail.build())),
                    target, turnId, Optional.empty(), Optional.empty()), Optional.of(task.id().toString()));
        }

        private Start freeText(Request request)
        {
            String workspace = request.text("workspace").substring("ws:".length());
            workspaces.findById(workspace).orElseThrow(() -> new IllegalArgumentException("That repository is not watched any more"));
            return new Start(workspace, request.words(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        /** The item's own first message, as its Start button sends it, then the user's words as their note. */
        private Start item(String ref, String words)
        {
            String note = NOTE.formatted(words);
            return switch (AgendaRefs.kind(ref)) {
                case "work" -> {
                    WorkItem item = workItems.find(AgendaRefs.id(ref)).orElseThrow(() -> new IllegalArgumentException("That work item is gone"));
                    yield new Start(item.workspaceId(), WORK_ITEM_START.formatted(item.title()) + note, Optional.empty(), Optional.empty(), Optional.of(item.id()));
                }
                case "issue" -> {
                    AgendaRefs.Numbered numbered = AgendaRefs.numbered(ref).orElseThrow(() -> new IllegalArgumentException("That issue ref is not valid"));
                    Workspace workspace = workspaces.findAll().stream().filter(watched -> watched.monitoredRepository().fullName().equalsIgnoreCase(numbered.repo())).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("That issue's repository is not watched"));
                    RepositoryIssue issue = issues.find(workspace.monitoredRepositoryId(), numbered.number())
                            .orElseThrow(() -> new IllegalArgumentException("That issue is not loaded; refresh its repository's issues"));
                    yield new Start(workspace.id(), ISSUE_START.formatted(issue.title()) + note,
                            Optional.of(new IssueSourceInput(issue.id(), issue.number(), issue.updatedAt(), Optional.empty())), Optional.empty(), Optional.empty());
                }
                case "jira" -> {
                    String[] parts = AgendaRefs.id(ref).split(":", 2);
                    checkArgument(parts.length == 2, "That Jira ref is not valid");
                    JiraIssue ticket = jira.cachedIssue(parts[0], parts[1]).orElseThrow(() -> new IllegalArgumentException("That ticket is not cached; open it in Jira first"));
                    String workspace = ticket.workspace().orElseThrow(() -> new IllegalArgumentException("Assign that ticket to a repository first")).id();
                    yield new Start(workspace, JIRA_START.formatted(ticket.key(), ticket.title()) + note, Optional.empty(),
                            Optional.of(new JiraSourceInput(ticket.connectionId(), ticket.cloudId(), ticket.id(), ticket.key(), ticket.title(), ticket.body(), ticket.updatedAt(),
                                    Optional.empty())), Optional.empty());
                }
                default -> throw new IllegalArgumentException("A task starts from a work, issue or jira ref");
            };
        }

        /** The same call on the same message is the same input: a retried reply starts or sends nothing twice. */
        private String identity(Request request)
        {
            return "assistant\n" + message.id() + "\n" + request.operation() + "\n" + request.target() + "\n" + request.words();
        }

        private String key(Request request)
        {
            return "assistant:" + UUID.nameUUIDFromBytes(identity(request).getBytes(StandardCharsets.UTF_8));
        }

        /** What the tool tells the assistant; the user reads the same line in the receipt. */
        private String reply(String outcome, String line)
        {
            try {
                return mapper.writeValueAsString(ImmutableMap.of("outcome", outcome, "message", line));
            }
            catch (JsonProcessingException impossible) {
                return "{\"outcome\":\"" + outcome + "\"}";
            }
        }
    }

    /** Where a new task starts, with what first message and from which source. */
    private record Start(String workspaceId, String message, Optional<IssueSourceInput> issue, Optional<JiraSourceInput> jira, Optional<String> workItemId) {}

    /** One question's option labels, and whether it takes several. */
    private record Offered(List<String> options, boolean multiSelect) {}

    /** The card's questions; none when its input has another shape. */
    private Map<String, Offered> questions(String detail)
    {
        try {
            Map<String, Offered> questions = new LinkedHashMap<>();
            for (JsonNode question : Optional.ofNullable(mapper.readTree(detail)).map(input -> input.path("questions")).orElse(mapper.missingNode())) {
                List<String> options = Streams.stream(question.path("options")).map(option -> option.path("label").asText("")).toList();
                questions.put(question.path("question").asText(""), new Offered(options, question.path("multiSelect").asBoolean(false)));
            }
            return questions;
        }
        catch (JsonProcessingException unreadable) {
            return ImmutableMap.of();
        }
    }

    /** The card's question a call names, by its whole text or by the clipped text the read tool showed. */
    private static Optional<String> question(Map<String, Offered> offered, String named)
    {
        String asked = named.strip();
        return offered.keySet().stream().filter(question -> question.strip().equals(asked) || clip(question, QUESTION).strip().equals(asked)).findFirst();
    }

    /** The option an answer names, ignoring case; a question that takes several joins them with ", ", as the card does. */
    private static Optional<String> option(Offered offered, String chosen)
    {
        Optional<String> whole = offered.options().stream().filter(label -> label.equalsIgnoreCase(chosen)).findFirst();
        if (whole.isPresent() || !offered.multiSelect()) {
            return whole;
        }
        List<String> picked = Splitter.on(',').trimResults().omitEmptyStrings().splitToList(chosen);
        List<String> labels = picked.stream().flatMap(name -> offered.options().stream().filter(label -> label.equalsIgnoreCase(name)).findFirst().stream()).toList();
        return !labels.isEmpty() && labels.size() == picked.size() ? Optional.of(String.join(", ", labels)) : Optional.empty();
    }

    private static String taskRef(AppNotice notice)
    {
        return notice.reviewId().map(AgendaRefs::review).orElseGet(() -> AgendaRefs.task(notice.taskId()));
    }

    private static Optional<ApprovalId> approvalId(String ref)
    {
        try {
            return ref.startsWith("approval:") ? Optional.of(ApprovalId.valueOf(ref.substring("approval:".length()))) : Optional.empty();
        }
        catch (IllegalArgumentException notAnApproval) {
            return Optional.empty();
        }
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

    private static String normal(String text)
    {
        return text.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ").strip();
    }

    /**
     * Whether the message says not to do what the words say: each place they appear follows a negation earlier in its clause. Both are
     * normalized. Words that carry their own negation, such as "don't push", are not negated by it.
     */
    static boolean negated(String message, String words)
    {
        boolean found = false;
        for (int at = message.indexOf(words); at >= 0; at = message.indexOf(words, at + 1)) {
            found = true;
            String before = message.substring(0, at);
            Matcher end = CLAUSE_END.matcher(before);
            int clause = 0;
            while (end.find()) {
                clause = end.end();
            }
            if (!NEGATION.matcher(before.substring(clause)).find()) {
                return false;
            }
        }
        return found;
    }
}
