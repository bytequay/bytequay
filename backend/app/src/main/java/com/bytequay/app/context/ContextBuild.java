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
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** The latest spec check turn and what its tools recorded; execution state belongs to its agent task. */
public record ContextBuild(
        long sequence, String requestKey, String requestDigest, String workspaceId, String taskId,
        RepositoryIdentity physicalRepository, long repositoryId, RepoRef repository, String baselineSha, String branch,
        String laneId, AgentTaskDefaults defaults, Instant requestedAt, Capture capture, Check check)
{
    public ContextBuild
    {
        checkArgument(sequence > 0 && repositoryId > 0, "Invalid context build identity");
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
        requireNonNull(requestedAt, "requestedAt is null");
        requireNonNull(capture, "capture is null");
        requireNonNull(check, "check is null");
    }

    /** Keyed by sequence as well: one task takes a check's planning turn and then a turn per reproduction. */
    public String turnKey()
    {
        return "context-build:" + taskId + ":" + sequence;
    }

    public ContextBuild withCapture(Capture replacement)
    {
        return new ContextBuild(sequence, requestKey, requestDigest, workspaceId, taskId, physicalRepository, repositoryId, repository, baselineSha, branch,
                laneId, defaults, requestedAt, replacement, check);
    }

    /**
     * The spec check a turn works for: its planning turn when nothing is being reproduced, otherwise the turn that reproduces one
     * candidate. The brief is what the turn starts from.
     */
    public record Check(String checkId, String directory, Optional<Reproduction> reproduction, String brief)
    {
        public static final int MAX_BRIEF_BYTES = 48 * 1024;

        public Check
        {
            ContextLimits.text(checkId, "check id", 100);
            ContextLimits.pathPrefix(directory);
            requireNonNull(reproduction, "reproduction is null");
            ContextLimits.text(brief, "check brief", MAX_BRIEF_BYTES);
        }
    }

    public record Reproduction(String candidateId, String title)
    {
        public Reproduction
        {
            ContextLimits.text(candidateId, "candidate id", 60);
            ContextLimits.text(title, "candidate title", 200);
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
            DONE, FAILED
        }
    }

    /** Attempts are keyed markers the tools leave: pages read, reports found and whether the turn saved its result. */
    public record Capture(Map<String, String> attempts, List<String> gaps, List<Activity> activity)
    {
        public static final int MAX_ACTIVITY = 200;
        public static final int MAX_GAPS = 40;

        public Capture
        {
            attempts = ImmutableMap.copyOf(attempts);
            gaps = ImmutableList.copyOf(gaps);
            activity = ImmutableList.copyOf(activity);
            checkArgument(activity.size() <= MAX_ACTIVITY, "Too many activity lines");
            attempts.forEach((key, value) -> {
                ContextLimits.text(key, "source attempt key", 1500);
                ContextLimits.text(value, "source attempt result", 2000);
            });
            checkArgument(gaps.size() <= MAX_GAPS, "Too many build limitations");
            gaps.forEach(gap -> ContextLimits.text(gap, "build limitation", 1000));
        }

        public static Capture empty()
        {
            return new Capture(ImmutableMap.of(), ImmutableList.of(), ImmutableList.of());
        }

        /** Keeps recent activity visible during long runs; the task transcript retains earlier operations. */
        public Capture logged(Instant at, String text, Activity.Tone tone)
        {
            return new Capture(attempts, gaps, ImmutableList.<Activity>builder()
                    .addAll(activity.subList(Math.max(0, activity.size() - MAX_ACTIVITY + 1), activity.size()))
                    .add(new Activity(at, ContextText.excerpt(text, 400), tone)).build());
        }

        /** Adds one limitation once, keeping at most {@link #MAX_GAPS}. */
        public Capture withGap(String gap)
        {
            String bounded = ContextText.excerpt(gap, 1000);
            return gaps.contains(bounded) || gaps.size() >= MAX_GAPS ? this
                    : new Capture(attempts, ImmutableList.<String>builder().addAll(gaps).add(bounded).build(), activity);
        }

        public Capture withAttempts(Map<String, String> added)
        {
            Map<String, String> merged = new LinkedHashMap<>(attempts);
            merged.putAll(added);
            return new Capture(merged, gaps, activity);
        }
    }
}
