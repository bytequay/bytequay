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
package com.bytequay.app.ci.e2e;

import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.app.ci.BoundedCiLog;
import com.bytequay.app.ci.CiHeadSnapshot;
import com.bytequay.app.ci.CiRerunResult;
import com.bytequay.app.ci.GitHubActionsClient;
import com.bytequay.app.ci.InspectCiHeadQuery;
import com.bytequay.app.ci.ReadCiLogQuery;
import com.bytequay.app.ci.RerunFailedJobsCommand;
import com.bytequay.core.domain.PullRequestRef;
import com.google.common.collect.ImmutableList;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

final class ScriptedGitHubActionsClient
        implements GitHubActionsClient
{
    private final TaskLogStore logs;
    private final Map<PullRequestRef, CiHeadSnapshot> snapshots = new HashMap<>();
    private final Map<LogKey, ScriptedLog> jobLogs = new HashMap<>();
    private final Map<RerunKey, ArrayDeque<CiRerunResult>> reruns = new HashMap<>();
    private final List<InspectCiHeadQuery> inspectCalls = new ArrayList<>();
    private final List<ReadCiLogQuery> logCalls = new ArrayList<>();
    private final List<RerunFailedJobsCommand> rerunCalls = new ArrayList<>();

    ScriptedGitHubActionsClient(TaskLogStore logs)
    {
        this.logs = requireNonNull(logs, "logs is null");
    }

    synchronized void reset()
    {
        snapshots.clear();
        jobLogs.clear();
        reruns.clear();
        inspectCalls.clear();
        logCalls.clear();
        rerunCalls.clear();
    }

    synchronized void setSnapshot(CiHeadSnapshot snapshot)
    {
        requireNonNull(snapshot, "snapshot is null");
        snapshots.put(snapshot.pullRequest(), snapshot);
    }

    synchronized void setLog(
            PullRequestRef pullRequest,
            long workflowRunId,
            int workflowAttempt,
            long jobId,
            int jobAttempt,
            String content,
            List<String> terminalErrorLines)
    {
        requireNonNull(content, "content is null");
        jobLogs.put(
                new LogKey(pullRequest, workflowRunId, workflowAttempt, jobId, jobAttempt),
                new ScriptedLog(content.getBytes(StandardCharsets.UTF_8), terminalErrorLines));
    }

    synchronized void enqueueRerun(RerunFailedJobsCommand command, CiRerunResult result)
    {
        reruns.computeIfAbsent(RerunKey.from(command), ignored -> new ArrayDeque<>()).add(result);
    }

    synchronized List<InspectCiHeadQuery> inspectCalls()
    {
        return ImmutableList.copyOf(inspectCalls);
    }

    synchronized List<ReadCiLogQuery> logCalls()
    {
        return ImmutableList.copyOf(logCalls);
    }

    synchronized List<RerunFailedJobsCommand> rerunCalls()
    {
        return ImmutableList.copyOf(rerunCalls);
    }

    @Override
    public synchronized CiHeadSnapshot inspect(InspectCiHeadQuery query)
    {
        requireNonNull(query, "query is null");
        inspectCalls.add(query);
        CiHeadSnapshot snapshot = snapshots.get(query.pullRequest());
        if (snapshot == null) {
            throw new IllegalStateException("No GitHub Actions snapshot scripted for "
                    + query.pullRequest().fullName());
        }
        return snapshot;
    }

    @Override
    public synchronized BoundedCiLog readLog(ReadCiLogQuery query)
    {
        requireNonNull(query, "query is null");
        logCalls.add(query);
        LogKey key = LogKey.from(query);
        ScriptedLog scripted = jobLogs.get(key);
        if (scripted == null) {
            throw new IllegalStateException("No GitHub Actions log scripted for " + key.identity());
        }
        int length = Math.min(scripted.content().length, query.maximumBytes());
        BlobRef blob = logs.writeBlob(
                query.taskId(),
                ByteBuffer.wrap(Arrays.copyOf(scripted.content(), length)),
                "text/plain");
        return new BoundedCiLog(
                java.util.Optional.of(blob),
                length,
                scripted.content().length > length,
                scripted.terminalErrorLines(),
                java.util.Optional.empty());
    }

    @Override
    public synchronized CiRerunResult rerunFailedJobs(RerunFailedJobsCommand command)
    {
        requireNonNull(command, "command is null");
        rerunCalls.add(command);
        RerunKey key = RerunKey.from(command);
        ArrayDeque<CiRerunResult> queue = reruns.get(key);
        if (queue == null || queue.isEmpty()) {
            throw new IllegalStateException("No failed-job rerun scripted for " + key.identity());
        }
        return queue.removeFirst();
    }

    private record ScriptedLog(byte[] content, List<String> terminalErrorLines)
    {
        private ScriptedLog
        {
            terminalErrorLines = ImmutableList.copyOf(terminalErrorLines);
        }
    }

    private record LogKey(
            PullRequestRef pullRequest,
            long workflowRunId,
            int workflowAttempt,
            long jobId,
            int jobAttempt)
    {
        private static LogKey from(ReadCiLogQuery query)
        {
            return new LogKey(
                    query.pullRequest(),
                    query.workflowRun().id(),
                    query.workflowRun().attempt(),
                    query.job().id(),
                    query.job().attempt());
        }

        private String identity()
        {
            return pullRequest.fullName() + ", workflow " + workflowRunId + "/" + workflowAttempt
                    + ", job " + jobId + "/" + jobAttempt;
        }
    }

    private record RerunKey(
            PullRequestRef pullRequest,
            long workflowRunId,
            String headSha,
            int previousAttempt,
            boolean authorized)
    {
        private static RerunKey from(RerunFailedJobsCommand command)
        {
            return new RerunKey(
                    command.pullRequest(),
                    command.workflowRunId(),
                    command.headSha(),
                    command.previousAttempt(),
                    command.authorized());
        }

        private String identity()
        {
            return pullRequest.fullName() + ", workflow " + workflowRunId + "/" + previousAttempt
                    + ", head " + headSha + ", authorized " + authorized;
        }
    }
}
