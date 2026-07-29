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
package com.bytequay.app.service.brain;

import com.bytequay.app.beans.brain.BrainMessageResponse;
import com.bytequay.app.developmentflow.task.TaskBrainConversationRuntime;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.TurnLiveness;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.ids.IdGenerator;
import com.bytequay.app.service.stage.PlanSeedWindow;
import com.bytequay.app.service.threads.ChatAttachmentStore;
import com.bytequay.app.service.threads.MessageAttachments;
import com.bytequay.app.service.threads.PlanKickoffRequested;
import com.bytequay.app.service.threads.TaskPhaseTransitionedEvent;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

@Service
public class BrainServiceImpl
        implements BrainService
{
    private static final Logger log = LoggerFactory.getLogger(BrainServiceImpl.class);

    private final TaskStore taskStore;
    private final ThreadStore threadStore;
    private final StageStore stageStore;
    private final ThreadTurnScheduler scheduler;
    private final IdGenerator idGenerator;
    private final WorkModelResolver workModelResolver;
    private final ChatAttachmentStore attachmentStore;
    private final ObjectMapper mapper;
    private final TransactionTemplate planningTransactions;
    private TaskBrainConversationRuntime v2Brain;

    @Autowired
    public BrainServiceImpl(
            TaskStore taskStore,
            ThreadStore threadStore,
            StageStore stageStore,
            ThreadTurnScheduler scheduler,
            IdGenerator idGenerator,
            WorkModelResolver workModelResolver,
            ChatAttachmentStore attachmentStore,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager)
    {
        this(taskStore, threadStore, stageStore, scheduler, idGenerator,
                workModelResolver, attachmentStore, mapper,
                transactionTemplate(transactionManager));
    }

    /** Dependency-light constructor retained for focused unit tests. */
    public BrainServiceImpl(
            TaskStore taskStore,
            ThreadStore threadStore,
            StageStore stageStore,
            ThreadTurnScheduler scheduler,
            IdGenerator idGenerator,
            WorkModelResolver workModelResolver,
            ChatAttachmentStore attachmentStore,
            ObjectMapper mapper)
    {
        this(taskStore, threadStore, stageStore, scheduler, idGenerator,
                workModelResolver, attachmentStore, mapper, (TransactionTemplate) null);
    }

    private BrainServiceImpl(
            TaskStore taskStore,
            ThreadStore threadStore,
            StageStore stageStore,
            ThreadTurnScheduler scheduler,
            IdGenerator idGenerator,
            WorkModelResolver workModelResolver,
            ChatAttachmentStore attachmentStore,
            ObjectMapper mapper,
            TransactionTemplate planningTransactions)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.idGenerator = requireNonNull(idGenerator, "idGenerator is null");
        this.workModelResolver = requireNonNull(workModelResolver, "workModelResolver is null");
        this.attachmentStore = requireNonNull(attachmentStore, "attachmentStore is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.planningTransactions = planningTransactions;
    }

    @Override
    public BrainMessageResponse sendMessage(String taskId, String text, List<String> images)
    {
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "text is required");
        }
        if (v2Brain != null && v2Brain.isV2Task(taskId)) {
            return v2Brain.sendMessage(taskId, text, images);
        }
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        if (taskStore.isV2Task(task.id())) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(503),
                    "V2 Task Brain runtime is unavailable");
        }
        throw new ResponseStatusException(
                HttpStatusCode.valueOf(409),
                "LEGACY Task Brain turns are read-only; use a typed V2 Task control");
    }

    @Autowired(required = false)
    void setV2Brain(TaskBrainConversationRuntime v2Brain)
    {
        this.v2Brain = requireNonNull(v2Brain, "v2Brain is null");
    }

    private BrainMessageResponse sendLegacyMessage(
            String taskId, String text, List<String> images)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));

        Thread brain = threadStore.findBrainThreadByTask(taskId)
                .orElseGet(() -> createBrainThread(task));

        // Fold any pasted images into the turn input the same way the trunk/
        // task composer does — see MessageAttachments' doc for why this rides
        // inside the plain-text string rather than a new column.
        List<String> paths = attachmentStore.save(brain.id(), images);
        String input = MessageAttachments.encode(mapper, text.trim(), paths);

        // The brain agent persists the user message and the assistant reply
        // itself when the turn runs; the reply streams via the thread SSE.
        String turnId = scheduler.enqueueTaskTurn(
                brain, input, task.id(), TurnInitiator.user(), null, TurnLiveness.NARRATION);
        return new BrainMessageResponse(turnId, brain.id());
    }

    private static TransactionTemplate transactionTemplate(
            PlatformTransactionManager transactionManager)
    {
        requireNonNull(transactionManager, "transactionManager is null");
        return new TransactionTemplate(transactionManager);
    }

    /**
     * Start the brain agent's planning turn when a task is materialised.
     * Ensures the task's brain thread exists, then enqueues a planning turn
     * seeded from the user's opening prompt. The brain investigates and calls
     * {@code record_plan}; the user approves before any development begins.
     */
    public void onPlanKickoff(PlanKickoffRequested event)
    {
        Task task = taskStore.findTaskById(event.taskId()).orElse(null);
        if (task == null) {
            return;
        }
        Thread brain = planningTransactions == null
                ? ensureBrainThread(task)
                : planningTransactions.execute(status -> ensureBrainThread(task));
        var plan = stageStore.findActiveStage(task.id())
                .orElseThrow(() -> new IllegalStateException(
                        "task " + task.id() + " has no active PlanStage"));
        long attempt = 1 + stageStore.findEventsByStage(plan.id()).stream()
                .filter(stageEvent -> stageEvent.eventType() == StageEventType.PLAN_FAILED)
                .count();
        String turnId = scheduler.enqueueStageTurnOnce(
                "plan-kickoff:" + task.id() + ":" + plan.id() + ":" + attempt,
                brain, planningPrompt(event.taskId(), event.initialPrompt(), event.trunkPlan()),
                task.id(), plan.id().toString(), TurnInitiator.unattended("plan-kickoff"), null,
                TurnLiveness.NARRATION);
        log.debug("kicked off planning turn {} on brain thread {} for task {}",
                turnId, brain.id(), event.taskId());
    }

    /**
     * Kick off the trunk completion summary when a task reaches COMPLETED.
     * Ensures the task's brain thread exists, then enqueues a one-shot
     * "summarize this task" turn and records its turn id so {@code
     * TaskCompletionAnnouncer} can pick up the answer once the turn
     * finishes (see {@code TaskTurnFinishedEvent}) and write it as the
     * trunk's {@code task_summary} marker. Skips if a summary turn is
     * already pending for this task (a re-delivered COMPLETED transition).
     * No trunk write happens here — this only starts the brain thinking.
     */
    @Transactional
    public void onTaskCompleted(TaskPhaseTransitionedEvent event)
    {
        if (event.to() != TaskPhase.COMPLETED) {
            return;
        }
        if (taskStore.pendingCompletionSummaryTurnId(event.taskId()).isPresent()) {
            return;
        }
        Task task = taskStore.findTaskById(event.taskId()).orElse(null);
        if (task == null) {
            return;
        }
        Thread brain = threadStore.findBrainThreadByTask(event.taskId())
                .orElseGet(() -> createBrainThread(task));
        try {
            String turnId = scheduler.enqueueTaskTurn(
                    brain, completionSummaryPrompt(task), task.id(),
                    TurnInitiator.unattended("task-completion-summary"), null,
                    TurnLiveness.NARRATION);
            taskStore.setPendingCompletionSummaryTurnId(event.taskId(), turnId);
        }
        catch (RuntimeException e) {
            // No pending turn id recorded — TaskCompletionAnnouncer's
            // stale-completion sweep picks this up after its grace window
            // and writes the mechanical fallback instead.
            log.warn("completion-summary enqueue failed for task {}: {}", event.taskId(), e.getMessage());
        }
    }

    private static String completionSummaryPrompt(Task task)
    {
        String outcome = task.prNumber() == null
                ? "It completed without ever opening a pull request."
                : "closed".equals(task.prState())
                        ? "Its pull request (#" + task.prNumber() + ") was closed without merging."
                        : "Its pull request (#" + task.prNumber() + ") was merged.";
        return """
                This task just reached COMPLETED. %s Using your read-only tools as needed \
                (read_diff_summary, read_commit_summary, read_remote_pr_status, \
                list_unresolved_comments, read_phase_history, etc.), reply directly with a \
                concise plain-text summary (1-3 sentences, no markdown) of what this task did \
                and its outcome — this becomes the trunk's permanent record of the task. Do \
                not call a tool to answer; just reply with the summary text.""".formatted(outcome);
    }

    private static String planningPrompt(String taskId, String initialPrompt, JsonNode trunkPlan)
    {
        String request = initialPrompt == null || initialPrompt.isBlank()
                ? "(No opening prompt was given — infer the intent from the trunk conversation "
                        + "above, the task, and the branch.)"
                : initialPrompt.trim();
        String trunkNote = trunkPlan == null || trunkPlan.isNull() || trunkPlan.isMissingNode()
                ? ""
                : "\n\nA draft plan was handed off from the parent thread (already recorded on "
                        + "this PlanStage). Validate it against the project: if you agree, record "
                        + "it largely unchanged; if not, record a revision explaining what you "
                        + "changed and why.";
        return """
                You are the planning agent for a new development task. The conversation above is \
                the trunk discussion that led to this task — your planning seed. The user's request:

                %s%s

                Investigate the project as needed with your read-only introspection tools. For \
                non-trivial tasks, if your runtime supports native subagents, delegate at most \
                two parallel read-only investigations: one for architecture/risk (affected \
                components, existing patterns, hidden risks), and one for validation/data \
                (tests, migrations, compatibility, verification). Do not delegate trivial tasks. \
                Subagents must not edit files, execute commands, or publish anything. Wait for \
                their results and synthesize them yourself, then call \
                record_plan(task_id='%s', plan={…}) with a structured plan: what you \
                understand (affected components, existing patterns to follow, constraints), what \
                you intend to do (numbered steps, validation strategy, push strategy), and the \
                risk / effort / value signals. Set status to "finalized" when the plan is ready \
                for the user to review. Do NOT write code — the user approves the plan before \
                any development starts. After record_plan succeeds, do not restate or summarize \
                the plan in your assistant reply. Reply with exactly one concise status line: \
                "Plan recorded; Brain self-review is starting." If record_plan fails, instead \
                report the failure concisely so the user can act.""".formatted(request, trunkNote, taskId);
    }

    private Thread createBrainThread(Task task)
    {
        Thread parent = threadStore.findThreadById(task.threadId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no parent thread: " + task.threadId()));
        String workspaceId = parent.workspaceId();
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "parent thread " + parent.id() + " has no workspace");
        }
        // The parent resolver now returns its creation-time plan snapshot.
        // Copy it onto the child brain so the registry and scheduler keep the
        // same provider even if workspace defaults change before a later turn.
        WorkModel resolved = resolveBrainWorkModel(task.threadId());
        Instant now = Instant.now();
        Thread brain = new Thread(
                idGenerator.newThreadId(now),
                ThreadKind.BRAIN_AGENT,
                resolved.agentOrProvider(),
                /* agentSessionId */ null,
                "Brain · " + task.id(),
                ThreadStatus.IDLE,
                resolved.model() == null ? "" : resolved.model(),
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                now,
                now,
                /* endedAt */ null,
                /* errorMessage */ null,
                ThreadFlow.BUILD,
                workspaceId,
                resolved,
                /* parentReviewPassId */ null,
                /* parallelSlots */ 1,
                /* parentTaskId */ task.id());
        threadStore.saveThread(brain);
        seedFromTrunk(brain.id(), task);
        return brain;
    }

    private Thread ensureBrainThread(Task task)
    {
        return threadStore.findBrainThreadByTask(task.id())
                .orElseGet(() -> createBrainThread(task));
    }

    /** Copy the trunk's seed conversation (previous task → this cut) onto the
     *  brain thread as its first messages, preserving roles and original
     *  timestamps. The brain thread is the single source for the plan stage:
     *  the brain agent reads the seed as conversation history, the feed shows
     *  it, and the dev agent's read_plan_conversation reads it — all from this
     *  one thread, with no on-the-fly cross-thread reads. */
    private void seedFromTrunk(String brainThreadId, Task task)
    {
        long seq = 1;
        for (ThreadMessage m : PlanSeedWindow.trunkSeedMessages(taskStore, threadStore, task)) {
            threadStore.appendMessage(new ThreadMessage(
                    UUID.randomUUID().toString(), brainThreadId, /* taskId */ null, seq++,
                    m.role(), m.type(), m.contentJson(),
                    /* durationMs */ null, /* tokensIn */ null, /* tokensOut */ null,
                    /* costUsdMilli */ null, m.ts(), null, ThreadScope.TRUNK));
        }
    }

    /** Resolve the brain's work model from the parent trunk's plan snapshot,
     *  with the legacy workspace/global fallback handled by the resolver. */
    private WorkModel resolveBrainWorkModel(String devThreadId)
    {
        return requireNonNull(
                workModelResolver.resolveForThread(devThreadId).choice(),
                "resolved brain work model is null");
    }
}
