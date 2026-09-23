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
        List<RegisteredContextSource> documentationSources, long notebookRevision, Instant requestedAt, Capture capture)
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
        checkArgument(!scopeIds.isEmpty(), "Select at least one scope");
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
                laneId, defaults, scopeIds, history, documentationSources, notebookRevision, requestedAt, replacement);
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

    public record Capture(
            List<ContextSourceRead> sourceReads, Map<String, CapturedSource> evidence, Map<String, String> attempts,
            Map<String, Operation> operations, Optional<Result> result, List<String> gaps)
    {
        public Capture
        {
            sourceReads = ImmutableList.copyOf(sourceReads);
            evidence = ImmutableMap.copyOf(evidence);
            attempts = ImmutableMap.copyOf(attempts);
            operations = ImmutableMap.copyOf(operations);
            requireNonNull(result, "result is null");
            gaps = ImmutableList.copyOf(gaps);
            ContextLimits.unique(sourceReads.stream().map(ContextSourceRead::id).toList(), "build source receipts", ContextLimits.MAX_SOURCE_READS);
            checkArgument(evidence.size() <= 128 && attempts.size() <= 128 && operations.size() <= 20, "Build capture limit reached");
            attempts.forEach((key, value) -> {
                ContextLimits.text(key, "source attempt key", 1500);
                ContextLimits.text(value, "source attempt result", 2000);
            });
            operations.keySet().forEach(key -> ContextLimits.text(key, "operation key", 120));
            checkArgument(evidence.values().stream().mapToLong(source -> ContextLimits.bytes(source.excerpt())).sum() <= 256 * 1024, "Build evidence exceeds 256 KiB");
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
            return new Capture(ImmutableList.of(), ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), ImmutableList.of());
        }
    }
}
