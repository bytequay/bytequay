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
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.WorkModel;
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
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final ThreadTurnScheduler scheduler;
    private final IdGenerator idGenerator;
    private final WorkModelResolver workModelResolver;
    private final ChatAttachmentStore attachmentStore;
    private final ObjectMapper mapper;

    public BrainServiceImpl(
            TaskStore taskStore,
            ThreadStore threadStore,
            ThreadTurnScheduler scheduler,
            IdGenerator idGenerator,
            WorkModelResolver workModelResolver,
            ChatAttachmentStore attachmentStore,
            ObjectMapper mapper)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.idGenerator = requireNonNull(idGenerator, "idGenerator is null");
        this.workModelResolver = requireNonNull(workModelResolver, "workModelResolver is null");
        this.attachmentStore = requireNonNull(attachmentStore, "attachmentStore is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    @Transactional
    public BrainMessageResponse sendMessage(String taskId, String text, List<String> images)
    {
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "text is required");
        }
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
        String turnId = scheduler.enqueueTurn(brain, input, TurnInitiator.user());
        return new BrainMessageResponse(turnId, brain.id());
    }

    /**
     * Start the brain agent's planning turn when a task is materialised.
     * Ensures the task's brain thread exists, then enqueues a planning turn
     * seeded from the user's opening prompt. The brain investigates and calls
     * {@code record_plan}; the user approves before any development begins.
     */
    @EventListener
    @Transactional
    public void onPlanKickoff(PlanKickoffRequested event)
    {
        Task task = taskStore.findTaskById(event.taskId()).orElse(null);
        if (task == null) {
            return;
        }
        Thread brain = threadStore.findBrainThreadByTask(event.taskId())
                .orElseGet(() -> createBrainThread(task));
        String turnId = scheduler.enqueueTurn(
                brain, planningPrompt(event.taskId(), event.initialPrompt(), event.trunkPlan()),
                TurnInitiator.unattended("plan-kickoff"));
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
    @EventListener
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
            String turnId = scheduler.enqueueTurn(
                    brain, completionSummaryPrompt(task), TurnInitiator.unattended("task-completion-summary"));
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
        // The brain follows the parent thread's resolved work model. Store
        // that choice on the brain thread so the registry (which agent to
        // build) and scheduler (which lane) both read the same configuration.
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
                    /* costUsdMilli */ null, m.ts()));
        }
    }

    /** Resolve the brain's work model through the parent thread's normal
     *  thread → workspace → global-default cascade. */
    private WorkModel resolveBrainWorkModel(String devThreadId)
    {
        return requireNonNull(
                workModelResolver.resolveForThread(devThreadId).choice(),
                "resolved brain work model is null");
    }
}
