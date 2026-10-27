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
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ResolveApprovalCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.app.agenda.AgendaRefs;
import com.bytequay.app.agenda.ItemTaskStarter;
import com.bytequay.app.agent.PullRequestTaskService;
import com.bytequay.app.agent.runtime.TaskInput;
import com.bytequay.app.notices.AppNotice;
import com.bytequay.app.notices.AppNoticeService;
import com.bytequay.app.service.attention.AttentionMarkService;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.AssistantAction;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.domain.AssistantReceipt;
import com.bytequay.core.domain.AssistantReceipt.Fix;
import com.bytequay.core.domain.AssistantReceipt.Operation;
import com.bytequay.core.domain.AssistantReceipt.Outcome;
import com.bytequay.core.domain.ServedRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.AssistantStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
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

import static com.bytequay.agent.spi.ProviderApprovalRequest.QUESTION_TOOL;
import static com.bytequay.agent.spi.ToolSchemas.object;
import static com.bytequay.app.agenda.AgendaReader.clip;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/**
 * The {@code assistant_act} tool: what the assistant may do for the user, and only in the run their own message started. A call passes
 * the checks in order or is refused, and either way leaves a receipt in the conversation and a row in the action log. It sends a task
 * only the user's words, never the assistant's: a task's own messages count as the user's authority for what it does next. It also
 * marks Epics, adds work to them, creates work items, and acknowledges or snoozes Needs attention items, all of which stay local.
 */
@Component
class AssistantActions
{
    static final String NAME = "assistant_act";
    static final int MAXIMUM_ACTIONS = 5;
    private static final int MAXIMUM_ARGUMENTS = 16 * 1024;
    private static final int REASON = 200;
    private static final int QUESTION = 400;
    private static final String NOTE = "\n\nNote from the user, in their words to the assistant: “%s”";
    private static final Map<String, Operation> OPERATIONS = ImmutableMap.<String, Operation>builder()
            .put("start_task", Operation.START_TASK)
            .put("answer_approval", Operation.ANSWER_APPROVAL)
            .put("answer_question", Operation.ANSWER_QUESTION)
            .put("reply_to_task", Operation.REPLY_TO_TASK)
            .put("mark_epic", Operation.MARK_EPIC)
            .put("add_to_epic", Operation.ADD_TO_EPIC)
            .put("create_work_item", Operation.CREATE_WORK_ITEM)
            .put("acknowledge", Operation.ACKNOWLEDGE)
            .put("snooze", Operation.SNOOZE)
            .buildOrThrow();
    private static final Map<Operation, Set<String>> FIELDS = ImmutableMap.<Operation, Set<String>>builder()
            .put(Operation.START_TASK, ImmutableSet.of("op", "words", "workspace", "from", "agent"))
            .put(Operation.ANSWER_APPROVAL, ImmutableSet.of("op", "words", "approval", "decision"))
            .put(Operation.ANSWER_QUESTION, ImmutableSet.of("op", "words", "approval", "answers"))
            .put(Operation.REPLY_TO_TASK, ImmutableSet.of("op", "words", "task"))
            .put(Operation.MARK_EPIC, ImmutableSet.of("op", "words", "item"))
            .put(Operation.ADD_TO_EPIC, ImmutableSet.of("op", "words", "epic", "item"))
            .put(Operation.CREATE_WORK_ITEM, ImmutableSet.of("op", "words", "workspace", "epic"))
            .put(Operation.ACKNOWLEDGE, ImmutableSet.of("op", "words", "item"))
            .put(Operation.SNOOZE, ImmutableSet.of("op", "words", "item", "until"))
            .buildOrThrow();
    /** The operations the "change Epics and work items" switch allows; each stays local. */
    private static final Set<Operation> WORK = Sets.immutableEnumSet(Operation.MARK_EPIC, Operation.ADD_TO_EPIC, Operation.CREATE_WORK_ITEM);
    private static final Set<String> START_FROM = ImmutableSet.of("work", "issue", "jira", "pr");
    private static final Set<String> MARKABLE = ImmutableSet.of("issue", "jira", "work");
    private static final Set<String> JOINABLE = ImmutableSet.of("work", "issue", "jira", "pr", "task");
    /** The operations the "acknowledge and snooze" switch allows, on what Needs attention lists. */
    private static final Set<Operation> MARKS = Sets.immutableEnumSet(Operation.ACKNOWLEDGE, Operation.SNOOZE);
    private static final Set<String> ATTENTION = ImmutableSet.of("pr", "issue", "jira", "task");
    private static final Map<String, AttentionMarkService.Snooze> SNOOZES = ImmutableMap.of(
            "hour", AttentionMarkService.Snooze.HOUR,
            "tomorrow", AttentionMarkService.Snooze.TOMORROW,
            "monday", AttentionMarkService.Snooze.MONDAY);
    // Words after one of these, in the same clause, are what the user said not to do: "push to main" in "don't push to main".
    private static final Pattern NEGATION = Pattern.compile("\\b(?:not|never|don't|dont|doesn't|didn't|can't|cannot|won't|wouldn't|shouldn't|mustn't|isn't|aren't|wasn't"
            + "|stop|avoid|without|neither|nor|except|instead of|rather than)\\b");
    // ponytail: a comma or a contrast ends the clause, so "don't push, open a PR" allows the PR, but a list after a comma ("don't push, merge
    // or tag") escapes; asking the user to confirm the words would close that.
    private static final Pattern CLAUSE_END = Pattern.compile("[,.!?;:]|\\b(?:but|then|otherwise)\\b");
    // Match the whole message: a model-selected excerpt cannot turn a refusal or a question into consent.
    private static final Pattern APPROVAL_COMMAND = Pattern.compile("(?:please )?(allow|approve|deny|reject)(?: (?:it|this|that|(?:the|this|that) command))?"
            + "(?: once)?(?:,? please)?[.!]?");

    private final AssistantStore store;
    private final AssistantSettings permissions;
    private final AgentApprovalStore approvals;
    private final AgentApprovalService approvalService;
    private final AgentTaskStore tasks;
    private final AgentTurnService turns;
    private final ItemTaskStarter starts;
    private final PullRequestTaskService pullRequestTasks;
    private final WorkspaceStore workspaces;
    private final AppNoticeService notices;
    private final AgentReviewStore reviews;
    private final AssistantWork work;
    private final ObjectMapper mapper;
    private final Clock clock;

    AssistantActions(
            AssistantStore store,
            AssistantSettings permissions,
            AgentApprovalStore approvals,
            AgentApprovalService approvalService,
            AgentTaskStore tasks,
            AgentTurnService turns,
            ItemTaskStarter starts,
            PullRequestTaskService pullRequestTasks,
            WorkspaceStore workspaces,
            AppNoticeService notices,
            AgentReviewStore reviews,
            AssistantWork work,
            ObjectMapper mapper,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.permissions = requireNonNull(permissions, "permissions is null");
        this.approvals = requireNonNull(approvals, "approvals is null");
        this.approvalService = requireNonNull(approvalService, "approvalService is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.starts = requireNonNull(starts, "starts is null");
        this.pullRequestTasks = requireNonNull(pullRequestTasks, "pullRequestTasks is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.notices = requireNonNull(notices, "notices is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.work = requireNonNull(work, "work is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * The tool for one message: its calls act only on what that message had on screen or was served in its conversation, and leave their receipts
     * through {@code receipts}.
     */
    ReviewTool tool(AssistantItem message, AssistantContext context, Consumer<AssistantItem> receipts)
    {
        checkArgument(message.kind() == AssistantItem.Kind.USER, "only a user's message can act");
        Run run = new Run(message, context, receipts);
        Map<String, Object> schema = object(ImmutableMap.<String, Object>builder()
                .put("op", ImmutableMap.of("type", "string", "enum", ImmutableList.copyOf(OPERATIONS.keySet())))
                .put("words", ImmutableMap.of("type", "string", "description", "The user's exact words in their latest message that ask for this action"))
                .put("workspace", ImmutableMap.of("type", "string", "description", "start_task without from, and create_work_item outside an Epic: a ws: ref "
                        + "from start_options"))
                .put("from", ImmutableMap.of("type", "string", "description", "start_task: the work:, issue: or jira: ref of the item to start, "
                        + "or the pr: ref of the user's own pull request, whose development task gets the user's words"))
                .put("agent", ImmutableMap.of("type", "string", "description", "start_task: an agent id from start_options; the repository's default when absent; "
                        + "none for a pr: ref"))
                .put("approval", ImmutableMap.of("type", "string", "description", "answer_approval and answer_question: the card's approval: ref"))
                .put("decision", ImmutableMap.of("type", "string", "enum", ImmutableList.of("allow_once", "deny")))
                .put("answers", ImmutableMap.of("type", "object", "additionalProperties", ImmutableMap.of("type", "string"),
                        "description", "answer_question: each question's text to the chosen option's label, or to the user's own words"))
                .put("task", ImmutableMap.of("type", "string", "description", "reply_to_task: the task: ref to send the user's words to"))
                .put("item", ImmutableMap.of("type", "string", "description", "mark_epic: the issue:, jira: or work: ref to mark as an Epic; add_to_epic: the work:, "
                        + "issue:, jira:, pr: or task: ref to add; acknowledge and snooze: the pr:, issue:, jira: or task: ref of a Needs attention item"))
                .put("until", ImmutableMap.of("type", "string", "enum", ImmutableList.copyOf(SNOOZES.keySet()),
                        "description", "snooze: for an hour, until tomorrow at 9:00, or until Monday at 9:00"))
                .put("epic", ImmutableMap.of("type", "string", "description", "add_to_epic, and create_work_item in an Epic: the epic: ref"))
                .buildOrThrow(), "op", "words");
        return new ReviewTool(NAME, "Do one thing the user's latest message asks for: start a task, give the user's own pull request's task their words, "
                + "answer a card, send the user's words to a task, mark an Epic, add work to an Epic, create a work item titled with the user's words, "
                + "or acknowledge or snooze a Needs attention item. The app checks every call and shows the user a receipt.", schema, run::call, false);
    }

    /**
     * The receipt for a card the user answered with its own button in the panel.
     *
     * @throws IllegalArgumentException when the approval is unknown or still pending
     */
    AssistantItem buttonReceipt(ApprovalId approvalId, String conversationId)
    {
        AgentApproval approval = approvals.find(approvalId).orElseThrow(() -> new IllegalArgumentException("Unknown approval " + approvalId));
        ApprovalDecision decision = approval.decision().orElseThrow(() -> new IllegalArgumentException("That card is not answered yet"));
        boolean question = approval.request().providerRequest().requestedPermissions().contains(QUESTION_TOOL);
        String verb = decision.decision() == ApprovalDecision.Decision.DENY ? "Denied" : question ? "Answered" : decision.forTask() ? "Allowed for this task" : "Allowed once";
        String title = tasks.find(approval.request().taskId()).map(AgentTask::title).orElse("a task");
        AssistantReceipt receipt = AssistantReceipt.done(question ? Operation.ANSWER_QUESTION : Operation.ANSWER_APPROVAL, target(approval.request().taskId()));
        return AssistantItem.receipt(conversationId, clock.instant(), verb + " · " + clip(title, 80), receipt);
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
     * cards it showed, and what the conversation's reads handed out.
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

    private static boolean pullRequest(Request request)
    {
        return AgendaRefs.kind(request.text("from")).equals("pr");
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
                case OPEN, CREATE_EPIC, MAKE_AGENDA -> text("ref");
                case MARK_EPIC, ADD_TO_EPIC, ACKNOWLEDGE, SNOOZE -> text("item");
                case CREATE_WORK_ITEM -> optional("epic").orElse(text("workspace"));
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
                // The service's own rules said no, such as an item that cannot start now; the user reads why, without an HTTP status.
                String said = failure instanceof ResponseStatusException status ? status.getReason() : failure.getMessage();
                String reason = clip(Optional.ofNullable(said).filter(text -> !text.isBlank()).orElse("ByteQuay could not do that"), REASON);
                result = new Result(reason, new AssistantReceipt(request.operation(), Outcome.FAILED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty()), Optional.empty());
            }
            // The words go on every receipt, done or refused, so the user sees exactly what was taken as theirs.
            AssistantReceipt receipt = result.receipt().withWords(request.words());
            Optional<String> reason = receipt.outcome() == Outcome.DONE ? Optional.empty() : Optional.of(result.line());
            store.addAction(AssistantAction.of(message, request.operation(), request.target(), request.words(), receipt.outcome(), reason, result.result(), clock.instant()));
            receipts.accept(AssistantItem.receipt(message.conversationId(), clock.instant(), result.line(), receipt));
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
                case START_TASK -> {
                    checkArgument(request.optional("from").isPresent() || request.optional("workspace").isPresent(), "start_task needs from or workspace");
                    checkArgument(!pullRequest(request) || request.optional("agent").isEmpty(), "start_task from a pr: ref takes no agent");
                }
                case ANSWER_APPROVAL -> checkArgument(ImmutableSet.of("allow_once", "deny").contains(request.text("decision")), "decision must be allow_once or deny");
                case ANSWER_QUESTION -> checkArgument(body.path("answers").isObject() && !body.path("answers").isEmpty(), "answers are required");
                case REPLY_TO_TASK -> checkArgument(request.optional("task").isPresent(), "reply_to_task needs a task: ref");
                case MARK_EPIC -> checkArgument(MARKABLE.contains(AgendaRefs.kind(request.text("item"))), "mark_epic takes an issue:, jira: or work: ref");
                case ADD_TO_EPIC -> {
                    checkArgument(AgendaRefs.kind(request.text("epic")).equals("epic"), "add_to_epic needs an epic: ref");
                    checkArgument(JOINABLE.contains(AgendaRefs.kind(request.text("item"))), "add_to_epic adds a work:, issue:, jira:, pr: or task: ref");
                }
                case CREATE_WORK_ITEM -> checkArgument(request.optional("epic").map(epic -> AgendaRefs.kind(epic).equals("epic"))
                        .orElseGet(() -> request.text("workspace").startsWith("ws:")), "create_work_item needs a ws: or epic: ref");
                case ACKNOWLEDGE -> checkArgument(ATTENTION.contains(AgendaRefs.kind(request.text("item"))), "acknowledge takes a pr:, issue:, jira: or task: ref");
                case SNOOZE -> {
                    checkArgument(ATTENTION.contains(AgendaRefs.kind(request.text("item"))), "snooze takes a pr:, issue:, jira: or task: ref");
                    checkArgument(SNOOZES.containsKey(request.text("until")), "until must be hour, tomorrow or monday");
                }
            }
            checkArgument(!request.target().isEmpty(), "%s needs a ref", body.path("op").asText());
            return request;
        }

        /** The checks before anything is done, in order; the first that fails is the refusal. */
        private Optional<Result> check(Request request)
        {
            Operation operation = request.operation();
            AssistantPermissions allowed = permissions.permissions();
            Optional<String> off = switch (operation) {
                case START_TASK -> allowed.startTasks() ? Optional.empty() : Optional.of("Starting tasks is off");
                case MARK_EPIC, ADD_TO_EPIC, CREATE_WORK_ITEM -> allowed.changeWork() ? Optional.empty() : Optional.of("Changing Epics and work items is off");
                case ACKNOWLEDGE, SNOOZE -> allowed.markAttention() ? Optional.empty() : Optional.of("Acknowledging and snoozing is off");
                case ANSWER_APPROVAL, ANSWER_QUESTION, REPLY_TO_TASK, OPEN, CREATE_EPIC, MAKE_AGENDA ->
                        allowed.answerTasks() ? Optional.empty() : Optional.of("Answering tasks is off");
            };
            if (off.isPresent()) {
                return Optional.of(Result.refused(operation, off.orElseThrow(), Fix.SETTINGS));
            }
            Optional<String> unsaid = unsaid(message, request.words());
            if (unsaid.isPresent()) {
                return Optional.of(Result.refused(operation, unsaid.orElseThrow()));
            }
            if (!onScreen(request)) {
                return Optional.of(Result.refused(operation, "That isn't on screen"));
            }
            // An item's own rules, such as a ticket that already has a task, are its service's, and a refusal there reads as a failure.
            Optional<Result> state = switch (operation) {
                case START_TASK, OPEN, MARK_EPIC, ADD_TO_EPIC, CREATE_WORK_ITEM, CREATE_EPIC, ACKNOWLEDGE, SNOOZE, MAKE_AGENDA -> Optional.empty();
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
         * that was on screen or that the conversation's reads handed out.
         */
        private boolean onScreen(Request request)
        {
            if (request.operation() == Operation.ANSWER_APPROVAL || request.operation() == Operation.ANSWER_QUESTION) {
                return message.visible().contains(request.target());
            }
            Set<String> shown = shown(message, context);
            if (request.operation() == Operation.ADD_TO_EPIC) {
                return shown.contains(request.text("epic")) && shown.contains(request.text("item"));
            }
            if (WORK.contains(request.operation()) || MARKS.contains(request.operation())) {
                return shown.contains(request.target());
            }
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
            Matcher command = APPROVAL_COMMAND.matcher(normal(message.text()));
            if (!command.matches() || deny != ImmutableSet.of("deny", "reject").contains(command.group(1))) {
                return Optional.of(Result.refused(operation, "Use the card's buttons, or say 'allow once' or 'deny'", Fix.BUTTONS));
            }
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

        /** A reply goes to an open development task; one at work takes it after its current turn, as its own page's send does. */
        private Optional<Result> taskState(Request request)
        {
            if (AgendaRefs.kind(request.target()).equals("review")) {
                return Optional.of(Result.refused(request.operation(), "Reply to a review from its page"));
            }
            Optional<AgentTask> found = AssistantRows.taskId(AgendaRefs.id(request.target())).flatMap(tasks::find);
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
            return Optional.empty();
        }

        private Result act(Request request)
        {
            return switch (request.operation()) {
                case START_TASK -> start(request);
                case ANSWER_APPROVAL -> allowOrDeny(request);
                case ANSWER_QUESTION -> answerQuestion(request);
                case REPLY_TO_TASK -> send(request);
                case MARK_EPIC -> done(request, work.markEpic(request.text("item")));
                case ADD_TO_EPIC -> done(request, work.addToEpic(request.text("epic"), request.text("item")));
                case CREATE_WORK_ITEM -> done(request, work.createWorkItem(request.optional("epic"), request.optional("workspace"), request.words(),
                        UUID.nameUUIDFromBytes(identity(request).getBytes(StandardCharsets.UTF_8)).toString()));
                case ACKNOWLEDGE -> done(request, work.acknowledge(request.text("item")));
                case SNOOZE -> done(request, work.snooze(request.text("item"), SNOOZES.get(request.text("until"))));
                // assistant_show opens and makes the agenda, and only the user's click creates a draft's issue; this tool parses none of them.
                case OPEN, CREATE_EPIC, MAKE_AGENDA -> throw new IllegalStateException("assistant_act does not " + request.operation());
            };
        }

        /** A work operation's receipt; the same call on the same message names the same new work, so a retried reply creates it once. */
        private Result done(Request request, AssistantWork.Done done)
        {
            return new Result(done.line(), new AssistantReceipt(request.operation(), Outcome.DONE, done.detail(), done.target(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty()), Optional.of(done.result()));
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
            return new Result(result.line(), new AssistantReceipt(receipt.operation(), receipt.outcome(), Optional.of(clip(sent, REASON)), receipt.target(), receipt.page(),
                    receipt.turnId(), receipt.fix(), receipt.words()), result.result());
        }

        private Result decided(ApprovalId id, String verb, Operation operation, ApprovalDecision decided)
        {
            TaskId taskId = approvals.find(id).orElseThrow().request().taskId();
            String title = tasks.find(taskId).map(AgentTask::title).orElse("a task");
            return new Result(verb + clip(title, 80), AssistantReceipt.done(operation, target(taskId)), Optional.of(decided.decision().name()));
        }

        private Result send(Request request)
        {
            AgentTask task = AssistantRows.taskId(AgendaRefs.id(request.target())).flatMap(tasks::find).orElseThrow();
            // Steering the words into the running turn stays a button on the task's page; the turn service refuses a queue behind anything but that turn.
            boolean working = task.worktreeMutationClaim().isPresent();
            TaskInput.send(turns, task.id(), task.version(), request.words(), key(request), working, Optional.empty());
            if (working) {
                return new Result("Queued your words for " + clip(task.title(), 80), new AssistantReceipt(Operation.REPLY_TO_TASK, Outcome.DONE,
                        Optional.of("After its current turn"), target(task.id()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                        Optional.of(task.id().toString()));
            }
            return new Result("Sent your words to " + clip(task.title(), 80), AssistantReceipt.done(Operation.REPLY_TO_TASK, target(task.id())), Optional.of(task.id().toString()));
        }

        private Result start(Request request)
        {
            if (pullRequest(request)) {
                return startOnPullRequest(request);
            }
            ItemTaskStarter.Start start = request.optional("from").map(from -> starts.item(from).followedBy(NOTE.formatted(request.words())))
                    .orElseGet(() -> starts.freeText(request.text("workspace").substring("ws:".length()), request.words()));
            TaskId taskId = new TaskId(UUID.nameUUIDFromBytes(identity(request).getBytes(StandardCharsets.UTF_8)));
            String key = key(request);
            Optional<ItemTaskStarter.Started> started = starts.start(start, request.optional("agent"), taskId, key);
            if (started.isEmpty()) {
                return Result.refused(Operation.START_TASK, "That agent isn't ready");
            }
            AgentTask task = started.get().task();
            Optional<AgendaTarget> target = task.workspaceId().map(workspace -> AgendaTarget.of(AgendaTarget.Kind.TASK, workspace, task.id().toString()));
            target.ifPresent(opens -> context.serve(AgendaRefs.task(task.id().toString()), new ServedRef("act", Optional.of(opens))));
            if (!started.get().fresh()) {
                // An issue that has a task already opens that task instead of starting another.
                return new Result("Already started: “" + clip(task.title(), 80) + "”", AssistantReceipt.done(Operation.START_TASK, target), Optional.of(task.id().toString()));
            }
            ImmutableList.Builder<String> detail = ImmutableList.<String>builder().add(task.repository().toString(), started.get().agent());
            switch (started.get().automation().level()) {
                case COMMIT -> {}
                case DRAFT_PR -> detail.add("Auto-Draft PR");
                case CI_FIX -> detail.add("CI fix");
                case REVIEW_FIX -> detail.add("Auto-Review fix");
                case MERGE -> detail.add("Auto-merge");
            }
            Optional<String> turnId = turns.findTurnByIdempotencyKey(task.id(), key).map(turn -> turn.id().toString());
            return new Result("Started “" + clip(task.title(), 80) + "”", new AssistantReceipt(Operation.START_TASK, Outcome.DONE, Optional.of(String.join(" · ", detail.build())),
                    target, Optional.empty(), turnId, Optional.empty(), Optional.empty()), Optional.of(task.id().toString()));
        }

        /**
         * The pull request's own development task, as its page's Development task button opens or makes it, with the user's words as its next
         * message: the first one when the button just made it.
         */
        private Result startOnPullRequest(Request request)
        {
            AgendaRefs.Numbered pr = AgendaRefs.numbered(request.text("from")).orElseThrow(() -> new IllegalArgumentException("That pull request ref is not valid"));
            Workspace workspace = workspaces.findAll().stream().filter(watched -> watched.monitoredRepository().fullName().equalsIgnoreCase(pr.repo())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("That pull request's repository is not watched"));
            boolean had = pullRequestTasks.find(workspace.id(), pr.repo(), pr.number()).filter(found -> found.state() != TaskState.COMPLETED).isPresent();
            AgentTask task = pullRequestTasks.assign(workspace.id(), pr.repo(), pr.number());
            String key = key(request);
            // A task at work takes the words after its current turn, as a reply to it does.
            boolean working = task.worktreeMutationClaim().isPresent();
            TaskInput.Accepted accepted = TaskInput.accepted(turns, task.id(), key)
                    .orElseGet(() -> TaskInput.send(turns, task.id(), task.version(), request.words(), key, working, Optional.empty()));
            Optional<AgendaTarget> target = target(task.id());
            target.ifPresent(opens -> context.serve(AgendaRefs.task(task.id().toString()), new ServedRef("act", Optional.of(opens))));
            String line = !had ? "Started “%s”" : accepted.queuedInputId().isPresent() ? "Queued your words for “%s”" : "Sent your words to “%s”";
            return new Result(line.formatted(clip(task.title(), 80)), new AssistantReceipt(Operation.START_TASK, Outcome.DONE, Optional.of(pr.repo() + "#" + pr.number()),
                    target, Optional.empty(), accepted.turnId().map(TurnId::toString), Optional.empty(), Optional.empty()), Optional.of(task.id().toString()));
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
