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
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * The VALIDATING phase's subroutine: run every registered {@link
 * ValidationCheck} (tests + checkstyle + repo rules) once, then publish a {@link
 * ValidationPassFinishedEvent} the {@link
 * com.bytequay.app.service.threads.TaskPhaseMachine} reacts to.
 *
 * <p>Validation deliberately does not enqueue fixes itself. Fix turns are
 * asynchronous, so retrying inline would only rerun the same failing state
 * and queue duplicate work. A failed pass publishes its terminal event and
 * the phase machine parks the task at NEEDS_ATTENTION; a future event-driven
 * retry loop can add durable attempt state without weakening this gate.
 */
@Service
public class ValidationPassService
{
    private final List<ValidationCheck> checks;
    private final TaskStore taskStore;
    private final ValidationPassStore validationStore;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;

    public ValidationPassService(
            List<ValidationCheck> checks,
            TaskStore taskStore,
            ValidationPassStore validationStore,
            ApplicationEventPublisher events,
            ObjectMapper mapper)
    {
        this.checks = List.copyOf(requireNonNull(checks, "checks is null"));
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.validationStore = requireNonNull(validationStore, "validationStore is null");
        this.events = requireNonNull(events, "events is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /**
     * Run validation for {@code taskId}. On a clean result publishes
     * {@code ValidationPassFinishedEvent(passed=true)}; on failure publishes
     * it with {@code passed=false} + the failures from that pass.
     */
    public ValidationPassResult run(String taskId)
    {
        long rowId = validationStore.startPass(taskId, Instant.now());
        List<ValidationFailure> failures = runChecks(taskId);
        if (failures.isEmpty()) {
            validationStore.finishPass(rowId, Instant.now(), true, 0, "[]");
            events.publishEvent(new ValidationPassFinishedEvent(taskId, true, List.of()));
            return new ValidationPassResult(true, 0, List.of());
        }
        validationStore.finishPass(rowId, Instant.now(), false, 0, toJson(failures));
        events.publishEvent(new ValidationPassFinishedEvent(taskId, false, failures));
        return new ValidationPassResult(false, 0, failures);
    }

    /** Execute every registered check for the task's worktree — no
     *  audit row, no event; the claimed-validation path owns those. */
    List<ValidationFailure> runChecks(String taskId)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        Path worktree = task.worktreePath() == null || task.worktreePath().isBlank()
                ? null
                : Path.of(task.worktreePath());
        return checks.stream()
                .flatMap(check -> check.run(taskId, worktree).stream())
                .toList();
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
