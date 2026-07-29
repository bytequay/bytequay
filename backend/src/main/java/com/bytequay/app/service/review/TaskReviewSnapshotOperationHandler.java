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

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.review.TaskReviewSnapshotRuntime.ExecutionSubject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.AsyncFamily.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK;
import static java.util.Objects.requireNonNull;

/** Captures one exact Task-owned AgentReview diff under its writer lease. */
@Component
public final class TaskReviewSnapshotOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND = "CAPTURE_TASK_REVIEW_SNAPSHOT";
    public static final String CALLBACK_ROUTE = "TASK_REVIEW_SNAPSHOT_RESULT";
    private static final int MAX_DIFF_CHARS = 240_000;

    private final TaskReviewSnapshotRuntime operations;
    private final CodeFingerprints fingerprints;
    private final GitRunner git;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public TaskReviewSnapshotOperationHandler(
            TaskReviewSnapshotRuntime operations,
            CodeFingerprints fingerprints,
            GitRunner git,
            ObjectMapper json)
    {
        this(operations, fingerprints, git, json, Clock.systemUTC());
    }

    TaskReviewSnapshotOperationHandler(
            TaskReviewSnapshotRuntime operations,
            CodeFingerprints fingerprints,
            GitRunner git,
            ObjectMapper json,
            Clock clock)
    {
        this.operations = requireNonNull(operations, "operations is null");
        this.fingerprints = requireNonNull(fingerprints, "fingerprints is null");
        this.git = requireNonNull(git, "git is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext context)
            throws Exception
    {
        requireNonNull(context, "context is null");
        DispatchTicket.DispatchEnvelope envelope = context.envelope();
        ExecutionSubject subject = operations.requireExecutionSubject(
                envelope.fence().operationId());
        requireEnvelope(envelope, subject);
        if (!subject.current()) {
            Instant now = clock.instant();
            SnapshotResult result = new SnapshotResult(
                    1, subject.operationId(), subject.reviewId(), subject.prId(),
                    subject.taskId(), subject.repository(),
                    subject.remotePrNumber(), subject.baseBranch(),
                    subject.prTitle(), subject.prDescription(),
                    subject.taskEpoch(), subject.worktreePath(),
                    subject.codeFingerprint(), subject.headSha(), subject.baseSha(),
                    false, "", List.of(), Map.of(), subject.codeFingerprint(), subject.headSha(),
                    now.toEpochMilli(), now.toEpochMilli());
            String encoded = write(result);
            return new DispatchTicket.DispatchResult(
                    envelope.fence(), SUCCEEDED, encoded, encoded, null);
        }
        context.onCancellation(Thread.currentThread()::interrupt);
        if (context.isCancellationRequested()
                || Thread.currentThread().isInterrupted()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Task review snapshot was canceled");
        }

        try {
            Instant startedAt = clock.instant();
            Path worktree = Path.of(subject.worktreePath());
            ObservedCode before = observe(worktree);
            boolean current = before.matches(subject);
            String diff = current
                    ? git.diff(worktree, subject.baseSha(), subject.headSha(),
                            MAX_DIFF_CHARS)
                    : "";
            CapturedFiles capturedFiles = current
                    ? captureChangedFiles(
                            git, worktree, subject.baseSha(), subject.headSha())
                    : new CapturedFiles(List.of(), Map.of());
            if (context.isCancellationRequested()
                    || Thread.currentThread().isInterrupted()) {
                throw new ExecutionPorts.OperationCanceledException(
                        "Task review snapshot was canceled");
            }
            ObservedCode after = observe(worktree);
            current = current && before.equals(after) && after.matches(subject);
            SnapshotResult result = new SnapshotResult(
                    1, subject.operationId(), subject.reviewId(), subject.prId(),
                    subject.taskId(), subject.repository(),
                    subject.remotePrNumber(), subject.baseBranch(),
                    subject.prTitle(), subject.prDescription(),
                    subject.taskEpoch(), subject.worktreePath(),
                    subject.codeFingerprint(), subject.headSha(), subject.baseSha(),
                    current, current ? diff : "",
                    current ? capturedFiles.files() : List.of(),
                    current ? capturedFiles.contents() : Map.of(),
                    after.fingerprint(),
                    after.headSha(), startedAt.toEpochMilli(),
                    clock.instant().toEpochMilli());
            String encoded = write(result);
            return new DispatchTicket.DispatchResult(
                    envelope.fence(), SUCCEEDED, encoded, encoded, null);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (context.isCancellationRequested()) {
                throw new ExecutionPorts.OperationCanceledException(
                        "Task review snapshot was canceled");
            }
            throw e;
        }
    }

    /** Snapshotting is read-only and safe to repeat against the same fence. */
    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
            throws Exception
    {
        return execute(context);
    }

    private ObservedCode observe(Path worktree)
            throws Exception
    {
        return new ObservedCode(
                fingerprints.fingerprint(worktree), git.headSha(worktree));
    }

    static CapturedFiles captureChangedFiles(
            GitRunner git, Path worktree, String baseSha, String headSha)
            throws IOException, InterruptedException
    {
        List<DiffFile> files = git.rangeFiles(worktree, baseSha, headSha).stream()
                .map(file -> new DiffFile(
                        file.path(), file.status(), file.additions(),
                        file.deletions(), null))
                .toList();
        List<String> paths = files.stream()
                .filter(file -> !"D".equals(file.status()))
                .map(DiffFile::filename)
                .toList();
        return new CapturedFiles(
                files, InvestigationReviewContext.captureChangedFiles(
                    git, worktree, headSha, paths));
    }

    private static void requireEnvelope(
            DispatchTicket.DispatchEnvelope envelope, ExecutionSubject subject)
    {
        DispatchTicket.OperationFence fence = envelope.fence();
        if (!OPERATION_KIND.equals(envelope.operationKind())
                || envelope.family() != LOCAL_GIT
                || envelope.owner().kind() != TASK
                || !subject.taskId().equals(envelope.owner().id())
                || !CALLBACK_ROUTE.equals(envelope.owner().callbackRoute())
                || !subject.operationId().equals(fence.operationId())
                || !Objects.equals(subject.taskEpoch(), fence.taskEpoch())
                || fence.stageId() != null || fence.stageGeneration() != null
                || fence.attempt() != 1
                || !subject.codeFingerprint().equals(
                        fence.expectedCodeFingerprint())
                || !subject.headSha().equals(fence.expectedHeadSha())
                || !subject.baseSha().equals(fence.expectedBaseSha())) {
            throw new IllegalArgumentException(
                    "Task review snapshot ticket differs from its exact subject");
        }
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not encode Task review snapshot", e);
        }
    }

    public record SnapshotResult(
            int schemaVersion,
            String operationId,
            String reviewId,
            String prId,
            String taskId,
            String repository,
            Integer remotePrNumber,
            String baseBranch,
            String prTitle,
            String prDescription,
            long taskEpoch,
            String worktreePath,
            String codeFingerprint,
            String headSha,
            String baseSha,
            boolean subjectCurrent,
            String diff,
            List<DiffFile> files,
            Map<String, String> fileContents,
            String observedCodeFingerprint,
            String observedHeadSha,
            long startedAtMs,
            long completedAtMs)
    {
        public SnapshotResult
        {
            if (schemaVersion != 1 || taskEpoch < 1 || startedAtMs < 0
                    || completedAtMs < startedAtMs) {
                throw new IllegalArgumentException(
                        "Task review snapshot identity is invalid");
            }
            requireNonNull(diff, "diff is null");
            files = List.copyOf(requireNonNull(files, "files is null"));
            requireNonNull(baseBranch, "baseBranch is null");
            requireNonNull(prTitle, "prTitle is null");
            requireNonNull(prDescription, "prDescription is null");
            if ((repository == null) != (remotePrNumber == null)
                    || remotePrNumber != null && remotePrNumber <= 0) {
                throw new IllegalArgumentException(
                        "Task review snapshot PR route is invalid");
            }
            fileContents = Map.copyOf(requireNonNull(
                    fileContents, "fileContents is null"));
            if (!subjectCurrent && (!diff.isEmpty() || !files.isEmpty()
                    || !fileContents.isEmpty())) {
                throw new IllegalArgumentException(
                        "A superseded Task review snapshot cannot carry evidence");
            }
        }
    }

    record CapturedFiles(List<DiffFile> files, Map<String, String> contents)
    {
        CapturedFiles
        {
            files = List.copyOf(files);
            contents = Map.copyOf(contents);
        }
    }

    private record ObservedCode(String fingerprint, String headSha)
    {
        private boolean matches(ExecutionSubject subject)
        {
            return fingerprint.equals(subject.codeFingerprint())
                    && headSha.equals(subject.headSha());
        }
    }
}
