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

import com.bytequay.app.service.hosting.HostingIntegrations;
import com.bytequay.core.ci.CiDeliveryChecks;
import com.bytequay.core.ci.CiHeadSnapshot;
import com.bytequay.core.ci.CiRerunResult;
import com.bytequay.core.ci.RerunFailedJobsCommand;
import com.bytequay.core.domain.PullRequestIdentity;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.PullRequestCi;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Scripted CI for every provider; logs go through the real shared log reader. */
final class ScriptedPullRequestCi
        implements PullRequestCi
{
    private final Map<PullRequestRef, CiHeadSnapshot> snapshots = new HashMap<>();
    private final Map<LogCall, String> jobLogs = new HashMap<>();
    private final Map<RerunFailedJobsCommand, ArrayDeque<CiRerunResult>> reruns = new HashMap<>();
    private final List<PullRequestRef> inspectCalls = new ArrayList<>();
    private final List<LogCall> logCalls = new ArrayList<>();
    private final List<RerunFailedJobsCommand> rerunCalls = new ArrayList<>();
    private final Map<Long, Set<String>> baseFailures = new HashMap<>();
    private Optional<Supplier<CiDeliveryChecks>> deliveryChecks = Optional.empty();

    /** The registry with this scripted CI in place of each provider's own. */
    static HostingIntegrations integrations(ObjectProvider<HostingIntegration> integrations, ScriptedPullRequestCi ci)
    {
        return new HostingIntegrations(integrations)
        {
            @Override
            public HostingIntegration of(HostingProvider provider)
            {
                HostingIntegration real = super.of(provider);
                return new HostingIntegration(real.provider(), real.accounts(), real.repositories(), real.pullRequests(), real.contents(), real.writer(), real.issues(),
                        real.metadata(), real.discovery(), ci, real.team());
            }
        };
    }

    synchronized void reset()
    {
        snapshots.clear();
        jobLogs.clear();
        reruns.clear();
        inspectCalls.clear();
        logCalls.clear();
        rerunCalls.clear();
        baseFailures.clear();
        deliveryChecks = Optional.empty();
    }

    synchronized void setBaseFailures(long definitionId, Set<String> jobNames)
    {
        baseFailures.put(definitionId, ImmutableSet.copyOf(jobNames));
    }

    synchronized void setSnapshot(CiHeadSnapshot snapshot)
    {
        requireNonNull(snapshot, "snapshot is null");
        snapshots.put(snapshot.pullRequest(), snapshot);
    }

    synchronized void setLog(PullRequestRef pullRequest, long jobId, String content)
    {
        jobLogs.put(new LogCall(pullRequest, jobId), requireNonNull(content, "content is null"));
    }

    synchronized void setDeliveryChecks(Supplier<CiDeliveryChecks> checks)
    {
        deliveryChecks = Optional.of(checks);
    }

    synchronized void enqueueRerun(RerunFailedJobsCommand command, CiRerunResult result)
    {
        reruns.computeIfAbsent(command, ignored -> new ArrayDeque<>()).add(result);
    }

    synchronized List<PullRequestRef> inspectCalls()
    {
        return ImmutableList.copyOf(inspectCalls);
    }

    synchronized List<LogCall> logCalls()
    {
        return ImmutableList.copyOf(logCalls);
    }

    synchronized List<RerunFailedJobsCommand> rerunCalls()
    {
        return ImmutableList.copyOf(rerunCalls);
    }

    @Override
    public synchronized CiHeadSnapshot inspect(HostingTarget target, int number)
    {
        PullRequestRef pullRequest = parseRef(target.repository().fullPath(), number);
        inspectCalls.add(pullRequest);
        return snapshot(pullRequest);
    }

    /** The scripted head as the pull request read reports it, which is not a CI inspection. */
    synchronized PullRequestIdentity identity(PullRequestRef pullRequest)
    {
        CiHeadSnapshot snapshot = snapshot(pullRequest);
        RepoRef base = RepoRef.of(pullRequest.owner(), pullRequest.repo());
        return new PullRequestIdentity(pullRequest, snapshot.url(), snapshot.headRepository(), snapshot.headRef(), snapshot.headSha(), base, snapshot.baseRef(),
                snapshot.baseSha(), snapshot.open() ? PullRequestIdentity.State.OPEN : PullRequestIdentity.State.CLOSED, false, snapshot.author());
    }

    @Override
    public synchronized Optional<String> downloadLog(HostingTarget target, int number, long jobId, Path destination)
    {
        LogCall call = new LogCall(parseRef(target.repository().fullPath(), number), jobId);
        logCalls.add(call);
        String content = jobLogs.get(call);
        checkState(content != null, "No CI log scripted for " + call.pullRequest().fullName() + ", job " + jobId);
        try {
            Files.writeString(destination, content, UTF_8);
        }
        catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return Optional.empty();
    }

    @Override
    public List<String> failureNotes(HostingTarget target, int number, long jobId)
    {
        return ImmutableList.of();
    }

    @Override
    public synchronized Set<String> failedJobsOnBranch(HostingTarget target, long definitionId, String branch)
    {
        return baseFailures.getOrDefault(definitionId, ImmutableSet.of());
    }

    @Override
    public synchronized CiRerunResult rerunFailedJobs(HostingTarget target, int number, RerunFailedJobsCommand command)
    {
        requireNonNull(command, "command is null");
        rerunCalls.add(command);
        ArrayDeque<CiRerunResult> queue = reruns.get(command);
        checkState(queue != null && !queue.isEmpty(), "No failed-job rerun scripted for " + command);
        return queue.removeFirst();
    }

    @Override
    public synchronized CiDeliveryChecks deliveryChecks(HostingTarget target, int number)
    {
        PullRequestRef pullRequest = parseRef(target.repository().fullPath(), number);
        return deliveryChecks.map(Supplier::get).orElseGet(() -> new CiDeliveryChecks(identity(pullRequest), ImmutableList.of(), ImmutableList.of()));
    }

    private CiHeadSnapshot snapshot(PullRequestRef pullRequest)
    {
        CiHeadSnapshot snapshot = snapshots.get(pullRequest);
        checkState(snapshot != null, "No CI snapshot scripted for " + pullRequest.fullName());
        return snapshot;
    }

    record LogCall(PullRequestRef pullRequest, long jobId) {}
}
