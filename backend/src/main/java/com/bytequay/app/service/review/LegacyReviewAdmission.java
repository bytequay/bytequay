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
package com.bytequay.app.service.review;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPassHostKind;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.threads.AgentScheduler;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import static java.util.Objects.requireNonNull;

/**
 * Thin mixed-version admission boundary for legacy review provider calls.
 * Review protocol state remains in its existing stores; this component only
 * derives an exact scope and executes under one shared read-only lease.
 */
@Component
public class LegacyReviewAdmission
{
    private final AgentScheduler scheduler;
    private final TaskStore tasks;
    private final ThreadStore threads;
    private final ThreadLocal<CapacityManager.CapacityRequest> current = new ThreadLocal<>();

    public LegacyReviewAdmission(
            AgentScheduler scheduler,
            TaskStore tasks,
            ThreadStore threads)
    {
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.threads = requireNonNull(threads, "threads is null");
    }

    public <T> T invoke(
            ReviewPass pass,
            ProviderLane lane,
            String attemptId,
            Callable<T> work)
    {
        CapacityManager.CapacityRequest request = request(pass, lane, attemptId);
        Callable<T> admitted = () -> withCurrent(request, work);
        if (current.get() != null) {
            return (lane == ProviderLane.CLI
                    ? scheduler.tryInvokeReviewCli(request, admitted)
                    : scheduler.tryInvokeReviewApi(request, admitted))
                    .orElseThrow(() -> new ReviewCapacityUnavailableException(
                            "review capacity unavailable for " + request.operationId()));
        }
        return lane == ProviderLane.CLI
                ? scheduler.invokeReviewCli(request, admitted)
                : scheduler.invokeReviewApi(request, admitted);
    }

    public <T> List<T> invokeAll(List<Work<T>> work)
    {
        requireNonNull(work, "work is null");
        List<AgentScheduler.ReviewWork<T>> admitted = work.stream()
                .map(item -> {
                    CapacityManager.CapacityRequest request = request(
                            item.pass(), item.lane(), item.attemptId());
                    return new AgentScheduler.ReviewWork<T>(
                            request, () -> withCurrent(request, item.work()));
                })
                .toList();
        if (current.get() != null) {
            return scheduler.tryInvokeReviewAll(admitted)
                    .orElseThrow(() -> new ReviewCapacityUnavailableException(
                            "review capacity unavailable for nested fan-out"));
        }
        return scheduler.invokeReviewAll(admitted);
    }

    /** Fail closed if a raw reviewer path is called outside its exact lease. */
    void requireCurrent(
            ReviewPass pass,
            ProviderLane lane,
            String attemptId)
    {
        CapacityManager.CapacityRequest expected = request(pass, lane, attemptId);
        if (!expected.equals(current.get())) {
            throw new IllegalStateException(
                    "review provider launch has no exact shared capacity lease");
        }
    }

    CapacityManager.CapacityRequest request(
            ReviewPass pass,
            ProviderLane lane,
            String attemptId)
    {
        requireNonNull(pass, "pass is null");
        requireNonNull(lane, "lane is null");
        requireNonBlank(attemptId, "attemptId");
        CapacityManager.CapacityLane providerLane = lane == ProviderLane.CLI
                ? CapacityManager.CapacityLane.CLI
                : CapacityManager.CapacityLane.API;
        String operationId = "legacy-review:" + pass.id() + ":" + attemptId;
        return new CapacityManager.CapacityRequest(
                operationId,
                CapacityManager.WorkflowSource.LEGACY,
                Set.of(CapacityManager.CapacityLane.REVIEW, providerLane),
                scope(pass),
                false,
                false,
                false);
    }

    private CapacityManager.CapacityScope scope(ReviewPass pass)
    {
        if (pass.hostKind() == ReviewPassHostKind.TASK_PHASE) {
            String taskId = requireNonBlank(pass.hostId(), "task-host id");
            Task task = tasks.findTaskById(taskId)
                    .orElseThrow(() -> new IllegalStateException(
                            "review pass has no exact Task: " + taskId));
            com.bytequay.app.domain.Thread trunk = threads.findThreadById(task.threadId())
                    .orElseThrow(() -> new IllegalStateException(
                            "review Task has no exact Trunk: " + task.threadId()));
            long taskEpoch = tasks.findTaskEpoch(taskId)
                    .orElseThrow(() -> new IllegalStateException(
                            "review Task has no exact epoch: " + taskId));
            return new CapacityManager.CapacityScope(
                    trunk.workspaceId(), trunk.id(), task.id(), taskEpoch);
        }
        if (pass.hostKind() == ReviewPassHostKind.THREAD) {
            String reviewThreadId = requireNonBlank(pass.hostId(), "review-thread host id");
            com.bytequay.app.domain.Thread reviewThread = threads.findThreadById(reviewThreadId)
                    .orElseThrow(() -> new IllegalStateException(
                            "review pass has no exact host Thread: " + reviewThreadId));
            return new CapacityManager.CapacityScope(
                    reviewThread.workspaceId(), null, null, null);
        }
        throw new IllegalStateException(
                "review pass has no supported exact capacity host: " + pass.id());
    }

    private <T> T withCurrent(
            CapacityManager.CapacityRequest request,
            Callable<T> work)
            throws Exception
    {
        CapacityManager.CapacityRequest previous = current.get();
        current.set(request);
        try {
            return work.call();
        }
        finally {
            if (previous == null) {
                current.remove();
            }
            else {
                current.set(previous);
            }
        }
    }

    static String attemptId(
            String role,
            String participantId,
            ReviewPhase phase,
            int round,
            String discriminator)
    {
        requireNonBlank(role, "role");
        requireNonBlank(participantId, "participantId");
        requireNonNull(phase, "phase is null");
        requireNonNull(discriminator, "discriminator is null");
        String digest = UUID.nameUUIDFromBytes(discriminator.getBytes(StandardCharsets.UTF_8))
                .toString();
        return role + ":" + participantId + ":" + phase.name() + ":" + round + ":" + digest;
    }

    private static String requireNonBlank(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum ProviderLane
    {
        API,
        CLI
    }

    static final class ReviewCapacityUnavailableException
            extends IllegalStateException
    {
        private ReviewCapacityUnavailableException(String message)
        {
            super(message);
        }
    }

    public record Work<T>(
            ReviewPass pass,
            ProviderLane lane,
            String attemptId,
            Callable<T> work)
    {
        public Work
        {
            requireNonNull(pass, "pass is null");
            requireNonNull(lane, "lane is null");
            requireNonBlank(attemptId, "attemptId");
            requireNonNull(work, "work is null");
        }
    }
}
