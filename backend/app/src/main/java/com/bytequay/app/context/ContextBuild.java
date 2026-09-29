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
package com.bytequay.app.context;

import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.app.context.RepositoryContextPreparation.CapturedSource;
import com.bytequay.core.context.ContextHistoryRead;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextPreparationResult;
import com.bytequay.core.context.ContextSourceRead;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** The latest explicit learning request and its captured evidence; execution state belongs to its agent task. */
public record ContextBuild(
        long sequence, String requestKey, String requestDigest, String workspaceId, String taskId,
        RepositoryIdentity physicalRepository, long repositoryId, RepoRef repository, String baselineSha, String branch,
        String laneId, AgentTaskDefaults defaults, List<String> scopeIds, List<History> history,
        List<RegisteredContextSource> documentationSources, long notebookRevision, Instant requestedAt, Capture capture, Mode mode, Optional<Assignment> assignment,
        Optional<String> continuesReport)
{
    public ContextBuild
    {
        checkArgument(sequence > 0 && notebookRevision >= 0 && repositoryId > 0, "Invalid context build identity");
        ContextLimits.text(requestKey, "requestKey", 100);
        ContextLimits.text(workspaceId, "workspaceId", 200);
        ContextLimits.text(taskId, "taskId", 200);
        checkArgument(requestDigest.matches("sha256:[0-9a-f]{64}"), "Invalid build request digest");
        checkArgument(baselineSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Invalid build baseline");
        requireNonNull(physicalRepository, "physicalRepository is null");
        requireNonNull(repository, "repository is null");
        requireNonNull(branch, "branch is null");
        requireNonNull(laneId, "laneId is null");
        requireNonNull(defaults, "defaults is null");
        scopeIds = ImmutableList.copyOf(scopeIds);
        history = ImmutableList.copyOf(history);
        documentationSources = ImmutableList.copyOf(documentationSources);
        ContextLimits.unique(scopeIds, "scopeIds", 100);
        requireNonNull(mode, "mode is null");
        requireNonNull(assignment, "assignment is null");
        checkArgument(mode == Mode.LEARN || assignment.isEmpty(), "Only learning has a topic assignment");
        requireNonNull(continuesReport, "continuesReport is null");
        checkArgument(mode == Mode.DISCOVER || continuesReport.isEmpty(), "Only discovery continues a saved report");
        checkArgument(mode == Mode.DISCOVER ? scopeIds.isEmpty() && history.isEmpty() && documentationSources.isEmpty() : !scopeIds.isEmpty(),
                "Discovery has no configured sources; learning requires selected scopes");
        ContextLimits.unique(history, "history", 10);
        requireNonNull(requestedAt, "requestedAt is null");
        requireNonNull(capture, "capture is null");
    }

    public String turnKey()
    {
        return "context-build:" + taskId;
    }

    public ContextBuild withCapture(Capture replacement)
    {
        return new ContextBuild(sequence, requestKey, requestDigest, workspaceId, taskId, physicalRepository, repositoryId, repository, baselineSha, branch,
                laneId, defaults, scopeIds, history, documentationSources, notebookRevision, requestedAt, replacement, mode, assignment, continuesReport);
    }

    /** One accepted plan question; progress is the checkpoint an earlier run saved for it, empty when it has not started. */
    public record Assignment(String planId, String topicId, String topicTitle, int questionIndex, String question, String progress,
            List<String> paths, List<String> repositoryFiles)
    {
        public Assignment
        {
            ContextLimits.text(planId, "planId", 200);
            ContextLimits.text(topicId, "topicId", 200);
            ContextLimits.text(topicTitle, "topic title", 200);
            checkArgument(questionIndex >= 0, "Invalid learning question index");
            ContextLimits.text(question, "learning question", 1000);
            checkArgument(ContextLimits.bytes(progress) <= 2000, "Saved question progress is too long");
            paths = ImmutableList.copyOf(paths);
            repositoryFiles = ImmutableList.copyOf(repositoryFiles);
            ContextLimits.unique(paths, "learning paths", 20);
            paths.forEach(ContextLimits::pathPrefix);
            ContextLimits.unique(repositoryFiles, "learning source files", 40);
            repositoryFiles.forEach(path -> ContextLimits.pathPrefix(path + "/"));
        }
    }

    public enum Mode
    {
        LEARN, DISCOVER
    }

    public record History(ContextHistoryReader.SourceKind kind, int number)
    {
        public History
        {
            requireNonNull(kind, "kind is null");
            checkArgument(number > 0, "History number must be positive");
        }
    }

    public record Operation(String digest, String result)
    {
        public Operation
        {
            checkArgument(digest.matches("sha256:[0-9a-f]{64}"), "Invalid operation digest");
            ContextLimits.text(result, "operation result", 64 * 1024);
        }
    }

    public record Result(List<ContextPreparationResult> scopeResults)
    {
        public Result
        {
            scopeResults = ImmutableList.copyOf(scopeResults);
        }
    }

    /** A short, user-facing line recorded as the run's tools act; the task transcript keeps the full detail. */
    public record Activity(Instant at, String text, Tone tone)
    {
        public Activity
        {
            requireNonNull(at, "at is null");
            ContextLimits.text(text, "activity", 400);
            requireNonNull(tone, "tone is null");
        }

        public enum Tone
        {
            DONE, FAILED, LIMIT
        }
    }

    public record Capture(
            List<ContextSourceRead> sourceReads, Map<String, CapturedSource> evidence, Map<String, String> attempts,
            Map<String, Operation> operations, Optional<Result> result, List<String> gaps, List<Activity> activity)
    {
        public static final int MAX_ACTIVITY = 200;

        public Capture
        {
            sourceReads = ImmutableList.copyOf(sourceReads);
            evidence = ImmutableMap.copyOf(evidence);
            attempts = ImmutableMap.copyOf(attempts);
            operations = ImmutableMap.copyOf(operations);
            requireNonNull(result, "result is null");
            gaps = ImmutableList.copyOf(gaps);
            activity = ImmutableList.copyOf(activity);
            checkArgument(activity.size() <= MAX_ACTIVITY, "Too many activity lines");
            checkArgument(sourceReads.stream().map(ContextSourceRead::id).distinct().count() == sourceReads.size(), "Build source receipts contain duplicates");
            checkArgument(operations.size() <= 20, "Too many preparation receipts");
            attempts.forEach((key, value) -> {
                ContextLimits.text(key, "source attempt key", 1500);
                ContextLimits.text(value, "source attempt result", 2000);
            });
            operations.keySet().forEach(key -> ContextLimits.text(key, "operation key", 120));
            checkArgument(evidence.values().stream().mapToLong(source -> ContextLimits.bytes(source.excerpt())).sum() <= 2 * 1024 * 1024, "Build evidence exceeds 2 MiB");
            checkArgument(gaps.size() <= 40, "Too many build limitations");
            gaps.forEach(gap -> ContextLimits.text(gap, "build limitation", 1000));
            Map<String, CapturedSource> captured = evidence;
            sourceReads.forEach(read -> read.evidenceRef().ifPresent(ref -> {
                checkArgument(captured.containsKey(ref), "Build receipt evidence is missing");
                checkArgument(ContextLimits.bytes(captured.get(ref).excerpt()) == read.utf8Bytes(), "Build receipt differs from its captured excerpt");
                if (read instanceof ContextHistoryRead historical) {
                    historical.checkExcerpt(captured.get(ref).excerpt());
                }
            }));
        }

        public static Capture empty()
        {
            return new Capture(ImmutableList.of(), ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), ImmutableList.of(), ImmutableList.of());
        }

        /** Keeps recent activity visible during long runs; the task transcript retains earlier operations. */
        public Capture logged(Instant at, String text, Activity.Tone tone)
        {
            return new Capture(sourceReads, evidence, attempts, operations, result, gaps, ImmutableList.<Activity>builder()
                    .addAll(activity.subList(Math.max(0, activity.size() - MAX_ACTIVITY + 1), activity.size()))
                    .add(new Activity(at, ContextText.excerpt(text, 400), tone)).build());
        }
    }
}
