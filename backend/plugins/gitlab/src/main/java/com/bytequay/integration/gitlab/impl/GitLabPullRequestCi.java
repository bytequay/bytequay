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
package com.bytequay.integration.gitlab.impl;

import com.bytequay.core.ci.CiDeliveryChecks;
import com.bytequay.core.ci.CiHeadSnapshot;
import com.bytequay.core.ci.CiJob;
import com.bytequay.core.ci.CiRerunResult;
import com.bytequay.core.ci.CiRun;
import com.bytequay.core.ci.RerunFailedJobsCommand;
import com.bytequay.core.domain.PullRequestCheckLog;
import com.bytequay.core.domain.PullRequestIdentity;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.PullRequestCi;
import com.bytequay.integration.gitlab.impl.GitLabMergeRequestResponse.Pipeline;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimaps;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bytequay.core.ci.CiRerunResult.Outcome.ACCEPTED;
import static com.bytequay.core.ci.CiRerunResult.Outcome.OBSOLETE;
import static com.bytequay.core.ci.CiRerunResult.Outcome.REJECTED;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.encode;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.mapped;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.mergeRequestPath;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.projectPath;
import static com.bytequay.integration.gitlab.impl.GitLabPullRequestContentReader.identity;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/** A merge request's head pipeline as its single CI run, read and retried with each target's own token. */
public final class GitLabPullRequestCi
        implements PullRequestCi
{
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\x1B(?:[@-Z\\\\-_]|\\[[0-?]*[ -/]*[@-~])");
    private static final Pattern SECTION_MARKER = Pattern.compile("section_(start|end):\\d+:([A-Za-z0-9_.-]+)(?:\\[[^\\]]*\\])?");
    private static final Pattern AUTHORIZATION_VALUE = Pattern.compile("(?i)((?:authorization\\s*[:=]\\s*(?:bearer|token)\\s+|private-token\\s*[:=]\\s*))\\S+");
    private static final Pattern GITLAB_TOKEN = Pattern.compile("\\bgl(?:pat|oas|dt|rt|rtr|cbt|ptt|ft|imt|agent|soat|ffct)-[A-Za-z0-9_-]{20,}");
    private static final String EXTERNAL = "; CI Autofix cannot repair this external check";
    private static final String CI_CONFIGURATION = "CI configuration";
    private static final String FAILURE_REASON = """
            query($path: ID!, $iid: String!) {
              project(fullPath: $path) {
                mergeRequest(iid: $iid) {
                  headPipeline { id failureReason }
                }
              }
            }
            """;

    private final GitLabHttpClient http;

    public GitLabPullRequestCi(HttpClient http, ObjectMapper mapper)
    {
        this(new GitLabHttpClient(http, mapper));
    }

    GitLabPullRequestCi(GitLabHttpClient http)
    {
        this.http = requireNonNull(http, "http is null");
    }

    @Override
    public CiHeadSnapshot inspect(HostingTarget target, int number)
    {
        GitLabMergeRequestResponse request = mergeRequest(target, number);
        PullRequestIdentity identity = identity(http, target, number, request);
        // GitLab reports a head pipeline only for the current head: its own commit, or the merged result built from it.
        List<CiRun> runs = Optional.ofNullable(request.headPipeline())
                .map(pipeline -> withFailureReason(target, number, run(target, identity.headSha(), pipeline)))
                .stream()
                .collect(toImmutableList());
        return mapped("merge request", () -> new CiHeadSnapshot(identity.pullRequest(), identity.state() == PullRequestIdentity.State.OPEN, identity.author(),
                identity.headRepository(), identity.headBranch(), identity.headSha(), identity.baseBranch(), identity.baseSha(), identity.url(), runs));
    }

    @Override
    public Optional<String> downloadLog(HostingTarget target, int number, long jobId, Path destination)
    {
        Pipeline pipeline = mergeRequest(target, number).headPipeline();
        if (pipeline == null) {
            return Optional.of("This request has no current pipeline");
        }
        ImmutableListMultimap<String, GitLabJob> jobs = jobs(target, pipeline);
        PullRequestCheckLog log;
        if (jobs.isEmpty() && jobId == pipeline.id()) {
            log = configErrors(target, pipeline)
                    .map(errors -> new PullRequestCheckLog(redact(Arrays.stream(errors.split("\n")).map(line -> "##[error]" + line).collect(joining("\n")), target.access().token()),
                            Optional.empty()))
                    .orElseGet(() -> new PullRequestCheckLog("", Optional.of("The pipeline no longer reports configuration errors")));
        }
        else if (jobs.values().stream().noneMatch(job -> job.id() == jobId)) {
            return Optional.of("This job is not part of the request's current pipeline");
        }
        else {
            log = log(target, pipeline, jobId);
        }
        if (log.unavailableReason().isPresent()) {
            return log.unavailableReason().map(reason -> Ascii.truncate(reason, 500, ""));
        }
        try {
            Files.writeString(destination, log.text(), StandardCharsets.UTF_8);
        }
        catch (IOException failure) {
            throw new UncheckedIOException("Cannot store raw CI log", failure);
        }
        return Optional.empty();
    }

    @Override
    public List<String> failureNotes(HostingTarget target, int number, long jobId)
    {
        GitLabJob job;
        try {
            Pipeline pipeline = mergeRequest(target, number).requireHeadPipeline();
            if (jobId == pipeline.id()) {
                // The CI configuration job is the pipeline itself, and its log holds the errors. ponytail: a real job whose ID equals its
                // pipeline's, possible only on a young self-managed instance, also gets no note
                return ImmutableList.of();
            }
            job = http.get(target.access(), pipeline.projectPath(target.repository()) + "/jobs/" + jobId, Optional.empty(), GitLabJob.class).body().orElseThrow();
        }
        catch (HostingFailure failure) {
            // Notes only add context; a token without job access still gets the log.
            return ImmutableList.of();
        }
        return Optional.ofNullable(job.failureReason()).flatMap(GitLabPullRequestCi::failureNote).stream().collect(toImmutableList());
    }

    @Override
    public Set<String> failedJobsOnBranch(HostingTarget target, long definitionId, String branch)
    {
        // Push pipelines only: a merge request pipeline may run for a source branch with the same name.
        String path = "projects/" + definitionId + "/pipelines?ref=" + encode(branch) + "&source=push&scope=finished&order_by=id&sort=desc&per_page=1";
        Pipeline[] latest = http.get(target.access(), path, Optional.empty(), Pipeline[].class).body().orElseThrow();
        return Arrays.stream(latest).findFirst()
                .map(pipeline -> run(target, pipeline.sha(), pipeline).jobs().stream()
                        .filter(CiJob::actionableFailure)
                        .map(CiJob::name)
                        .collect(toImmutableSet()))
                .orElse(ImmutableSet.of());
    }

    @Override
    public CiRerunResult rerunFailedJobs(HostingTarget target, int number, RerunFailedJobsCommand command)
    {
        requireNonNull(command, "command is null");
        if (!command.authorized()) {
            return new CiRerunResult(REJECTED, "Failed-job rerun was not authorized");
        }
        GitLabMergeRequestResponse request = mergeRequest(target, number);
        Pipeline pipeline = request.headPipeline();
        if (pipeline == null || pipeline.id() != command.runId() || !command.headSha().equals(request.sha())) {
            return new CiRerunResult(OBSOLETE, "Pipeline no longer matches the journaled failed attempt");
        }
        CiRun run = run(target, request.sha(), pipeline);
        if (run.attempt() != command.previousAttempt() || !"completed".equals(run.status()) || !failed(run.conclusion())) {
            return new CiRerunResult(OBSOLETE, "Pipeline no longer matches the journaled failed attempt");
        }
        try {
            retry(target, pipeline);
            return new CiRerunResult(ACCEPTED, "GitLab accepted the failed-job rerun");
        }
        catch (HostingFailure failure) {
            return switch (failure.kind()) {
                case FORBIDDEN -> new CiRerunResult(REJECTED, Ascii.truncate(failure.getMessage(), 500, ""));
                case MISSING_OR_INACCESSIBLE, REVISION_CONFLICT -> new CiRerunResult(OBSOLETE, Ascii.truncate(failure.getMessage(), 500, ""));
                default -> throw failure;
            };
        }
    }

    @Override
    public CiDeliveryChecks deliveryChecks(HostingTarget target, int number)
    {
        GitLabMergeRequestResponse request = mergeRequest(target, number);
        PullRequestIdentity identity = identity(http, target, number, request);
        Pipeline pipeline = request.headPipeline();
        List<GitLabJob> jobs = new ArrayList<>();
        if (pipeline != null) {
            jobs.addAll(currentJobs(target, pipeline));
            jobs.addAll(http.pages(target.access(), pipeline.path(target.repository()) + "/bridges", GitLabJob[].class));
        }
        // Commit statuses also list the head pipeline's own jobs; the rest come from outside it.
        Set<String> names = jobs.stream().map(GitLabJob::name).collect(toImmutableSet());
        List<GitLabJob> statuses = http.pages(target.access(), projectPath(target) + "/repository/commits/" + identity.headSha() + "/statuses", GitLabJob[].class).stream()
                .filter(status -> !names.contains(status.name()))
                .collect(toImmutableList());
        return mapped("pipeline check", () -> {
            List<String> waiting = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            if (pipeline != null) {
                check("CI pipeline #" + pipeline.id(), pipeline.status(), false, "", waiting, failures);
            }
            jobs.forEach(job -> check("CI job " + job.name(), job.status(), job.allowFailure(), "", waiting, failures));
            statuses.forEach(status -> check("CI status " + status.name(), status.status(), status.allowFailure(), EXTERNAL, waiting, failures));
            if (pipeline == null && statuses.isEmpty()) {
                waiting.add("No current-head CI checks have been reported");
            }
            return new CiDeliveryChecks(identity, waiting, failures);
        });
    }

    /** The current job of each name: GitLab keeps every retry as a new job. */
    List<GitLabJob> currentJobs(HostingTarget target, Pipeline pipeline)
    {
        return Multimaps.asMap(jobs(target, pipeline)).values().stream().map(List::getLast).collect(toImmutableList());
    }

    /** The job's trace as plain lines, with sections as {@code ##[group]} and runner errors as {@code ##[error]} lines, or why it is unavailable. */
    PullRequestCheckLog log(HostingTarget target, Pipeline pipeline, long jobId)
    {
        String trace;
        try {
            // ponytail: the whole trace is held in memory; stream it if traces near GitLab's size limit become common
            trace = http.getText(target.access(), pipeline.projectPath(target.repository()) + "/jobs/" + jobId + "/trace");
        }
        catch (HostingFailure failure) {
            if (Thread.currentThread().isInterrupted()) {
                throw failure;
            }
            return switch (failure.kind()) {
                case FORBIDDEN, MISSING_OR_INACCESSIBLE, RATE_LIMITED, UNAVAILABLE -> new PullRequestCheckLog("", Optional.of(failure.getMessage()));
                default -> throw failure;
            };
        }
        String text = Arrays.stream(trace.split("\n", -1)).map(GitLabPullRequestCi::plainLine).flatMap(Optional::stream).collect(joining("\n"));
        return new PullRequestCheckLog(redact(text, target.access().token()), Optional.empty());
    }

    /** Retries the pipeline's failed and canceled jobs in one request. */
    void retry(HostingTarget target, Pipeline pipeline)
    {
        http.post(target.access(), pipeline.path(target.repository()) + "/retry", ImmutableMap.of(), Void.class);
    }

    private GitLabMergeRequestResponse mergeRequest(HostingTarget target, int number)
    {
        return http.get(target.access(), mergeRequestPath(target, number), Optional.empty(), GitLabMergeRequestResponse.class).body().orElseThrow();
    }

    /** Every job of the pipeline by name, oldest first. */
    private ImmutableListMultimap<String, GitLabJob> jobs(HostingTarget target, Pipeline pipeline)
    {
        List<GitLabJob> jobs = http.pages(target.access(), pipeline.path(target.repository()) + "/jobs?include_retried=true", GitLabJob[].class);
        return mapped("pipeline job", () -> Multimaps.index(jobs.stream().sorted(Comparator.comparingLong(GitLabJob::id)).iterator(), GitLabJob::name));
    }

    /**
     * A job's attempt counts the jobs of its name. Every retry adds a job, so the run's attempt, one more than its retried jobs, grows with any rerun.
     * The run's definition is the request's target project, whose branches hold the base pipelines it compares with.
     */
    private CiRun run(HostingTarget target, String headSha, Pipeline pipeline)
    {
        ImmutableListMultimap<String, GitLabJob> jobs = jobs(target, pipeline);
        // GitLab stops a pipeline with an invalid configuration before any job; its errors become one job CI Autofix can repair.
        boolean configurationFailed = jobs.isEmpty() && configErrors(target, pipeline).isPresent();
        return mapped("pipeline", () -> new CiRun(
                pipeline.id(),
                Long.parseLong(target.repository().remoteId()),
                "Pipeline #" + pipeline.id(),
                1 + jobs.size() - jobs.keySet().size(),
                GitLabJob.state(pipeline.status(), false),
                GitLabJob.conclusion(pipeline.status(), false),
                headSha,
                pipeline.webUrl(),
                configurationFailed
                        ? ImmutableList.of(new CiJob(pipeline.id(), pipeline.id(), CI_CONFIGURATION, 1, "completed", Optional.of("failure"), pipeline.webUrl(), ImmutableList.of()))
                        : Multimaps.asMap(jobs).values().stream()
                                .map(named -> {
                                    GitLabJob job = named.getLast();
                                    return new CiJob(job.id(), pipeline.id(), job.name(), named.size(), GitLabJob.state(job.status(), job.allowFailure()),
                                            GitLabJob.conclusion(job.status(), job.allowFailure()), job.webUrl(), ImmutableList.of());
                                })
                                .sorted(Comparator.comparing(CiJob::name).thenComparingLong(CiJob::id))
                                .collect(toImmutableList())));
    }

    /** A failed pipeline's configuration errors; the pipeline is read again because lists and merge requests may leave them out. */
    private Optional<String> configErrors(HostingTarget target, Pipeline pipeline)
    {
        if (!"failed".equals(pipeline.status())) {
            return Optional.empty();
        }
        Pipeline current = http.get(target.access(), pipeline.path(target.repository()), Optional.empty(), Pipeline.class).body().orElseThrow();
        return Optional.ofNullable(current.yamlErrors()).filter(errors -> !errors.isBlank());
    }

    /** A pipeline GitLab failed before any job for another reason, such as an unverified account, names that reason; only GraphQL reports it. */
    private CiRun withFailureReason(HostingTarget target, int number, CiRun run)
    {
        if (!run.jobs().isEmpty() || !run.conclusion().equals(Optional.of("failure"))) {
            return run;
        }
        Optional<String> reason;
        try {
            FailureReason response = http.graphql(target.access(), FAILURE_REASON, ImmutableMap.of("path", target.repository().fullPath(), "iid", Integer.toString(number)),
                    FailureReason.class).body().orElseThrow();
            reason = Optional.ofNullable(response.data())
                    .map(FailureReason.Data::project)
                    .map(FailureReason.Project::mergeRequest)
                    .map(FailureReason.MergeRequest::headPipeline)
                    .filter(pipeline -> ("gid://gitlab/Ci::Pipeline/" + run.id()).equals(pipeline.id()))
                    .map(FailureReason.HeadPipeline::failureReason)
                    .filter(text -> !text.isBlank());
        }
        catch (HostingFailure failure) {
            // The reason only adds context; the pipeline link still shows it.
            reason = Optional.empty();
        }
        return reason
                .map(text -> new CiRun(run.id(), run.definitionId(), Ascii.truncate(run.name() + ": " + text, 300, "…"), run.attempt(), run.status(), run.conclusion(),
                        run.headSha(), run.url(), run.jobs()))
                .orElse(run);
    }

    private static void check(String name, String status, boolean allowFailure, String note, List<String> waiting, List<String> failures)
    {
        Optional<String> conclusion = GitLabJob.conclusion(status, allowFailure);
        if (conclusion.isEmpty()) {
            waiting.add("Waiting for " + name + " (" + status + ")");
        }
        else if (failed(conclusion)) {
            failures.add(name + " reported " + status + note);
        }
    }

    private static boolean failed(Optional<String> conclusion)
    {
        return conclusion.filter(value -> value.equals("failure") || value.equals("cancelled")).isPresent();
    }

    /** A carriage return rewrites its line, so the last written segment is what the job showed. */
    private static Optional<String> plainLine(String line)
    {
        Optional<String> section = Optional.empty();
        boolean marker = false;
        String text = "";
        for (String segment : ANSI_ESCAPE.matcher(line).replaceAll("").split("\r", -1)) {
            Matcher matcher = SECTION_MARKER.matcher(segment);
            if (matcher.matches()) {
                marker = true;
                if (matcher.group(1).equals("start")) {
                    section = Optional.of(matcher.group(2));
                    text = "";
                }
            }
            else if (!segment.isEmpty()) {
                text = segment;
            }
        }
        if (section.isPresent()) {
            return Optional.of("##[group]" + (text.isBlank() ? section.orElseThrow() : text.strip()));
        }
        if (marker && text.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(text.startsWith("ERROR: ") ? "##[error]" + text : text);
    }

    private static String redact(String text, String token)
    {
        text = text.replace(token, "[REDACTED]");
        text = AUTHORIZATION_VALUE.matcher(text).replaceAll("$1[REDACTED]");
        return GITLAB_TOKEN.matcher(text).replaceAll("[REDACTED]");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FailureReason(Data data)
    {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(Project project) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Project(MergeRequest mergeRequest) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record MergeRequest(HeadPipeline headPipeline) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record HeadPipeline(String id, String failureReason) {}
    }

    private static Optional<String> failureNote(String reason)
    {
        return switch (reason) {
            // The log already shows why a script failed.
            case "script_failure", "unknown_failure" -> Optional.empty();
            case "runner_system_failure" -> Optional.of("The runner failed while running the job, outside the job's script");
            case "stuck_or_timeout_failure" -> Optional.of("The job was stuck or timed out waiting for a runner");
            case "job_execution_timeout" -> Optional.of("The job exceeded its maximum run time");
            case "no_matching_runner" -> Optional.of("No runner matched the job's tags");
            case "missing_dependency_failure" -> Optional.of("An artifact the job depends on is missing or expired");
            default -> Optional.of("GitLab stopped the job: " + reason.replace('_', ' '));
        };
    }
}
