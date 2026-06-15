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
package com.bytequay.app.service.checks;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.service.threads.AgentScheduler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * The VALIDATING phase's subroutine: run every registered {@link
 * ValidationCheck} (tests + checkstyle + repo rules, once they land as
 * beans) in a bounded auto-fix loop, then publish a {@link
 * ValidationPassFinishedEvent} the {@link
 * com.bytequay.app.service.threads.TaskPhaseMachine} reacts to.
 *
 * <p>This is the SPI skeleton: with no {@code ValidationCheck} beans
 * registered the loop passes on round 0. The bounded loop, cap, audit
 * row, and event wiring are all in place so the real runners plug in as
 * beans without touching this class.
 *
 * <p>Caveat (tracked): the inline fix loop enqueues a fix turn through
 * the {@link AgentScheduler}, which is asynchronous — a fully
 * synchronous fix-then-recheck (or an event-driven re-validation) lands
 * with the real runners. The structure here is the seam for it.
 */
@Service
public class ValidationPassService
{
    private static final Logger log = LoggerFactory.getLogger(ValidationPassService.class);

    /** Default bounded auto-fix rounds. Per-repo overrideable later via
     *  REPO.md ("Validation fix-loop cap"). */
    static final int CAP_FIX_ROUNDS = 3;

    private final List<ValidationCheck> checks;
    private final TaskStore taskStore;
    private final ThreadStore threadStore;
    private final AgentScheduler scheduler;
    private final ValidationPassStore validationStore;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;

    public ValidationPassService(
            List<ValidationCheck> checks,
            TaskStore taskStore,
            ThreadStore threadStore,
            AgentScheduler scheduler,
            ValidationPassStore validationStore,
            ApplicationEventPublisher events,
            ObjectMapper mapper)
    {
        this.checks = List.copyOf(requireNonNull(checks, "checks is null"));
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.validationStore = requireNonNull(validationStore, "validationStore is null");
        this.events = requireNonNull(events, "events is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /**
     * Run validation for {@code taskId}. On a clean result publishes
     * {@code ValidationPassFinishedEvent(passed=true)}; on a cap hit
     * publishes it with {@code passed=false} + the remaining failures.
     */
    public ValidationPassResult run(String taskId)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        Path worktree = task.worktreePath() == null || task.worktreePath().isBlank()
                ? null
                : Path.of(task.worktreePath());

        long rowId = validationStore.startPass(taskId, Instant.now());
        List<ValidationFailure> last = List.of();
        for (int round = 0; round < CAP_FIX_ROUNDS; round++) {
            List<ValidationFailure> failures = new ArrayList<>();
            for (ValidationCheck check : checks) {
                failures.addAll(check.run(taskId, worktree));
            }
            if (failures.isEmpty()) {
                validationStore.finishPass(rowId, Instant.now(), true, round, "[]");
                events.publishEvent(new ValidationPassFinishedEvent(taskId, true, List.of()));
                return new ValidationPassResult(true, round, List.of());
            }
            last = failures;
            enqueueFixTurn(task, failures);
        }
        validationStore.finishPass(rowId, Instant.now(), false, CAP_FIX_ROUNDS, toJson(last));
        events.publishEvent(new ValidationPassFinishedEvent(taskId, false, last));
        return new ValidationPassResult(false, CAP_FIX_ROUNDS, last);
    }

    private void enqueueFixTurn(Task task, List<ValidationFailure> failures)
    {
        Thread thread = threadStore.findThreadById(task.threadId()).orElse(null);
        if (thread == null) {
            return;
        }
        try {
            scheduler.enqueueTurn(thread, fixPrompt(failures));
        }
        catch (RuntimeException e) {
            log.warn("enqueue validation fix turn for task {} failed: {}", task.id(), e.getMessage());
        }
    }

    private static String fixPrompt(List<ValidationFailure> failures)
    {
        StringBuilder sb = new StringBuilder("Validation failed. Fix these and we'll re-run the checks:\n");
        for (ValidationFailure f : failures) {
            sb.append("- [").append(f.source()).append("] ").append(f.detail()).append('\n');
        }
        return sb.toString();
    }

    private String toJson(List<ValidationFailure> failures)
    {
        try {
            return mapper.writeValueAsString(failures);
        }
        catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
