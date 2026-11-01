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
package com.bytequay.integration.gitlab;

import com.bytequay.core.ci.CiHeadSnapshot;
import com.bytequay.core.ci.CiJob;
import com.bytequay.core.ci.CiRun;
import com.bytequay.core.ci.RerunFailedJobsCommand;
import com.bytequay.core.domain.CreatedPullRequest;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.HostedIssueSource;
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.PrDiffEtags;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.hosting.ActivityNotice;
import com.bytequay.core.hosting.CommentTarget;
import com.bytequay.core.hosting.DiscoveryQuery;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingAccounts;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingRepositories;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.HostingVersion;
import com.bytequay.core.hosting.IssueClient;
import com.bytequay.core.hosting.MergeStrategy;
import com.bytequay.core.hosting.PullRequestCi;
import com.bytequay.core.hosting.PullRequestContentReader;
import com.bytequay.core.hosting.PullRequestDiscovery;
import com.bytequay.core.hosting.PullRequestFetch;
import com.bytequay.core.hosting.PullRequestListPage;
import com.bytequay.core.hosting.PullRequestReader;
import com.bytequay.core.hosting.PullRequestWriter;
import com.bytequay.core.hosting.Reaction;
import com.bytequay.core.hosting.RemoteHostingAccount;
import com.bytequay.core.hosting.RemoteHostingRepository;
import com.bytequay.core.hosting.RepositoryMetadataReader;
import com.bytequay.core.hosting.ReviewVerdict;
import com.bytequay.core.hosting.TeamActivityReader;
import com.bytequay.core.hosting.TeamIdentity;
import com.bytequay.core.hosting.TeamObservation;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bytequay.core.hosting.DiscoveryQuery.Kind.AUTHORED;
import static com.bytequay.core.hosting.DiscoveryQuery.Kind.INVOLVED;
import static com.bytequay.core.hosting.DiscoveryQuery.Kind.REVIEWED;
import static com.bytequay.core.hosting.DiscoveryQuery.Kind.REVIEW_REQUESTED;
import static com.bytequay.core.hosting.DiscoveryQuery.State.ANY;
import static com.bytequay.core.hosting.DiscoveryQuery.State.CLOSED;
import static com.bytequay.core.hosting.DiscoveryQuery.State.OPEN;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Calls every hosting port of the real GitLab integration against a live server and reports which calls work, through the implementation
 * the app would pick from the server's release. It writes comments, threads, reactions, labels, approvals, a branch, a merge request and an
 * issue, so point it only at a disposable project, or set {@code BYTEQUAY_GITLAB_LIVE_READ_ONLY=true} to make only the reads. Skipped unless
 * {@code BYTEQUAY_GITLAB_LIVE_URL} and {@code BYTEQUAY_GITLAB_LIVE_TOKEN} are set; surefire's default includes leave it out of normal builds.
 *
 * <p>Fixtures, all in {@code BYTEQUAY_GITLAB_LIVE_PROJECT}: {@code _MR} a small open request with a text diff and a discussion, {@code _BIG_MR}
 * one past the server's diff limits, {@code _CI_MR} one whose head pipeline failed on a configuration error, optional {@code _JOB_MR} one whose
 * pipeline has jobs, {@code _ISSUE} an issue, optional {@code _REVIEWER} another login. {@code _OUT} names the report file.
 */
class LiveGitLabCompatibility
{
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");
    private static final String STALE_SHA = "0".repeat(40);
    private static final String PROBE_LABEL = "bytequay-probe";
    private static final String GRAPHQL = "/api/graphql";
    private static final String FAILURE_REASON_AND_ERRORS = """
            query($path: ID!, $iid: String!) {
              project(fullPath: $path) {
                mergeRequest(iid: $iid) {
                  headPipeline { id failureReason errorMessages { nodes { content } } }
                }
              }
            }
            """;
    private static final String ERROR_MESSAGES = """
            query($path: ID!, $iid: String!) {
              project(fullPath: $path) {
                mergeRequest(iid: $iid) {
                  headPipeline { id errorMessages { nodes { content } } }
                }
              }
            }
            """;
    // GitLabPullRequestCi.FAILURE_REASON: inspect() swallows its failure, so it is also sent on its own.
    private static final String FAILURE_REASON = """
            query($path: ID!, $iid: String!) {
              project(fullPath: $path) {
                mergeRequest(iid: $iid) {
                  headPipeline { id failureReason }
                }
              }
            }
            """;
    // GitLabIssueClient.CHILDREN
    private static final String CHILDREN = """
            query($path: ID!, $iid: String!) {
              namespace(fullPath: $path) {
                workItem(iid: $iid) {
                  widgets {
                    ... on WorkItemWidgetHierarchy {
                      children(first: 100) {
                        nodes { id iid title state webUrl namespace { fullPath } }
                        pageInfo { hasNextPage }
                      }
                    }
                  }
                }
              }
            }
            """;

    private final List<String> lines = new ArrayList<>();
    private final Map<Integer, PrRawDetail> details = new HashMap<>();
    private final String stamp = Instant.now().toString();
    private int succeeded;
    private int failed;
    private int skipped;

    private String web;
    private String token;
    private String project;
    private int mr;
    private int bigMr;
    private int ciMr;
    private Optional<Integer> jobMr;
    private int issue;
    private Optional<String> reviewer;
    private boolean readOnly;
    private HttpClient http;
    private ObjectMapper mapper;
    private HostingAccess access;
    private HostingIntegration gitlab;
    private HostingTarget target;
    private String base;
    private String login;
    private long viewerId;
    private Optional<Integer> mergedRequest = Optional.empty();
    private Optional<String> description = Optional.empty();
    private Optional<PullRequestContentReader.Diff> smallDiff = Optional.empty();
    private Optional<Long> existingThread = Optional.empty();

    @Test
    void probeServer()
            throws IOException
    {
        Optional<String> url = env("BYTEQUAY_GITLAB_LIVE_URL");
        Optional<String> secret = env("BYTEQUAY_GITLAB_LIVE_TOKEN");
        assumeTrue(url.isPresent() && secret.isPresent(), "Set BYTEQUAY_GITLAB_LIVE_URL and BYTEQUAY_GITLAB_LIVE_TOKEN to probe a live GitLab server");
        web = url.orElseThrow().replaceFirst("/+$", "");
        token = secret.orElseThrow();
        project = required("BYTEQUAY_GITLAB_LIVE_PROJECT");
        mr = Integer.parseInt(required("BYTEQUAY_GITLAB_LIVE_MR"));
        bigMr = Integer.parseInt(required("BYTEQUAY_GITLAB_LIVE_BIG_MR"));
        ciMr = Integer.parseInt(required("BYTEQUAY_GITLAB_LIVE_CI_MR"));
        jobMr = env("BYTEQUAY_GITLAB_LIVE_JOB_MR").map(Integer::parseInt);
        issue = Integer.parseInt(required("BYTEQUAY_GITLAB_LIVE_ISSUE"));
        reviewer = env("BYTEQUAY_GITLAB_LIVE_REVIEWER");
        // A read-only run changes nothing on the server, for checking a server whose data is not disposable.
        readOnly = env("BYTEQUAY_GITLAB_LIVE_READ_ONLY").map(Boolean::parseBoolean).orElse(false);
        // Production passes Spring Boot's auto-configured mapper: the Jackson modules on the class path, unknown fields ignored, ISO dates.
        mapper = JsonMapper.builder()
                .findAndAddModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .build();
        // HostingConfiguration.hostingHttpClient
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            http = client;
            access = new HostingAccess(new HostingInstance(1, GITLAB, URI.create(web), URI.create(web + "/api/v4")), token);
            // The app picks the implementation from the release it reads when an account connects.
            HostingIntegration current = GitLabIntegration.create(http, mapper);
            Optional<HostingVersion> release = current.accounts().serverVersion(access);
            gitlab = release.filter(version -> !version.isAtLeast(current.since().orElseThrow())).isPresent() ? GitLabIntegration.createLegacy(http, mapper) : current;
            lines.add("GitLab compatibility probe of " + web + " (" + release.map(HostingVersion::toString).orElse("release unknown") + ", "
                    + (gitlab == current ? "current" : "legacy") + " implementation) project " + project + " at " + stamp);
            try {
                rawProbes();
                accounts();
                Optional<HostingTarget> resolved = repositories();
                if (resolved.isEmpty()) {
                    fail("repositories.findByPath(" + project + ") failed, so no request or issue could be probed; see the report");
                }
                target = resolved.orElseThrow();
                base = target.repository().defaultBranch().orElse("main");
                pullRequests();
                contents();
                metadata();
                discovery();
                team();
                ci();
                if (readOnly) {
                    skip("writer.*", "read-only run");
                }
                else {
                    writer();
                }
                issues();
            }
            finally {
                report(env("BYTEQUAY_GITLAB_LIVE_OUT"));
            }
        }
    }

    private void rawProbes()
            throws IOException
    {
        get("http GET /api/v4/version", "/api/v4/version");
        String projectPath = "/api/v4/projects/" + URLEncoder.encode(project, StandardCharsets.UTF_8);
        for (int number : ImmutableList.of(mr, bigMr)) {
            String path = projectPath + "/merge_requests/" + number;
            Optional<String> diffs = get("http GET !" + number + "/diffs?per_page=100&page=1", path + "/diffs?per_page=100&page=1");
            Optional<String> changes = get("http GET !" + number + "/changes", path + "/changes");
            Optional<String> rawChanges = get("http GET !" + number + "/changes?access_raw_diffs=true", path + "/changes?access_raw_diffs=true");
            Optional<String> versions = get("http GET !" + number + "/versions", path + "/versions");
            Optional<String> versionId = versions.flatMap(this::firstId);
            Optional<String> version = versionId.flatMap(id -> get("http GET !" + number + "/versions/" + id, path + "/versions/" + id));
            probe("compare diff sources of !" + number, () -> "diffs page 1: " + stats(diffs, "") + " | changes: " + stats(changes, "changes")
                    + " | raw changes: " + stats(rawChanges, "changes") + " | version: " + stats(version, "diffs"));
        }
        get("http GET /api/v4/personal_access_tokens/self", "/api/v4/personal_access_tokens/self");
        graphql("graphql headPipeline id failureReason errorMessages (!" + ciMr + ")", FAILURE_REASON_AND_ERRORS, ciMr);
        graphql("graphql headPipeline id errorMessages (!" + ciMr + ")", ERROR_MESSAGES, ciMr);
        graphql("graphql headPipeline id failureReason, the plugin's query (!" + ciMr + ")", FAILURE_REASON, ciMr);
        graphql("graphql namespace.workItem children, the plugin's query (#" + issue + ")", CHILDREN, issue);
    }

    private void accounts()
    {
        HostingAccounts accounts = gitlab.accounts();
        Optional<RemoteHostingAccount> viewer = probe("accounts.verify()", () -> accounts.verify(access));
        probe("accounts.checkPermissions()", () -> accounts.checkPermissions(access));
        // ponytail: later calls need a login; root is the Docker image's administrator.
        login = viewer.map(RemoteHostingAccount::login).orElse("root");
        viewerId = viewer.map(account -> Long.parseLong(account.remoteId())).orElse(1L);
    }

    private Optional<HostingTarget> repositories()
    {
        HostingRepositories repositories = gitlab.repositories();
        String name = project.substring(project.lastIndexOf('/') + 1);
        probe("repositories.search(\"\", 5)", () -> repositories.search(access, "", 5));
        probe("repositories.search(\"" + name + "\", 5)", () -> repositories.search(access, name, 5));
        probe("repositories.search(\"" + project + "\", 5)", () -> repositories.search(access, project, 5));
        probe("repositories.visibility(" + project + ")", () -> repositories.visibility(access, project));
        Optional<RemoteHostingRepository> remote = probe("repositories.findByPath(" + project + ")", () -> repositories.findByPath(access, project));
        probe("repositories.findById(id of " + project + ")", () -> repositories.findById(access, need(remote, "findByPath failed").remoteId()));
        return remote.map(found -> new HostingTarget(access, new HostingRepository(1, 1, found.remoteId(), found.fullPath(), found.webUrl(), found.httpCloneUrl(),
                found.sshCloneUrl(), found.defaultBranch())));
    }

    private void pullRequests()
    {
        PullRequestReader reader = gitlab.pullRequests();
        probe("pullRequests.list(open, page 1 of 20)", () -> reader.list(target, true, 1, 20));
        Optional<PullRequestListPage> all = probe("pullRequests.list(all, page 1 of 100)", () -> reader.list(target, false, 1, 100));
        mergedRequest = all.flatMap(page -> page.items().stream().filter(item -> "merged".equals(item.state())).map(FetchedPullRequest::number).findFirst());
        for (int number : requests()) {
            Optional<PullRequestFetch> fetch = probe("pullRequests.fetch(!" + number + ")", () -> reader.fetch(target, number, Optional.empty()));
            fetch.flatMap(PullRequestFetch::detail).ifPresent(detail -> details.put(number, detail));
            if (number == mr) {
                probe("pullRequests.fetch(!" + mr + ", If-None-Match)",
                        () -> reader.fetch(target, mr, Optional.of(need(fetch.flatMap(PullRequestFetch::etag), "the first read returned no ETag"))));
            }
        }
    }

    private void contents()
    {
        PullRequestContentReader contents = gitlab.contents();
        probe("contents.headRef(" + mr + ")", () -> contents.headRef(mr));
        probe("contents.identity(!" + mr + ")", () -> contents.identity(target, mr));
        description = probe("contents.description(!" + mr + ")", () -> contents.description(target, mr));
        probe("contents.identitiesByHead(" + project + ", source branch of !" + mr + ")",
                () -> contents.identitiesByHead(target, repoRef(project), need(detail(mr), "fetch failed").headRef()));
        int merged = mergedRequest.orElse(mr);
        probe("contents.merged(!" + merged + (mergedRequest.isPresent() ? ")" : ", still open: expect REVISION_CONFLICT)"), () -> contents.merged(target, merged));
        probe("contents.recentMergedTitles(" + base + ", 30 days, 10)", () -> contents.recentMergedTitles(target, base, Instant.now().minus(Duration.ofDays(30)), 10));
        probe("contents.mergedWithCommit(head of !" + mr + ")", () -> contents.mergedWithCommit(target, need(head(mr), "fetch failed")));
        probe("contents.viewerCanWrite()", () -> contents.viewerCanWrite(target));
        probe("contents.reviewRequested(!" + mr + ", " + login + ")", () -> contents.reviewRequested(target, mr, login));
        probe("contents.probeDiscussion(!" + mr + ", no ETags)", () -> contents.probeDiscussion(target, mr, ImmutableMap.of()));
        Optional<StoredPrDetail> discussion = probe("contents.discussion(!" + mr + ")", () -> contents.discussion(target, mr, stored(need(detail(mr), "fetch failed"))));
        existingThread = discussion.flatMap(LiveGitLabCompatibility::firstThread);
        for (int number : requests()) {
            probe("contents.checks(!" + number + ")",
                    () -> contents.checks(target, number, need(head(number), "fetch failed"), ImmutableList.of(), Optional.empty(), Optional.empty()));
        }
        StoredPrDiff none = new StoredPrDiff(ImmutableList.of(), ImmutableList.of());
        smallDiff = probe("contents.diff(!" + mr + ")", () -> contents.diff(target, mr, Optional.empty(), Optional.empty(), none, PrDiffEtags.none()));
        probe("contents.diff(!" + mr + ", start..head)", () -> contents.diff(target, mr, Optional.of(need(start(mr), "fetch returned no start sha")),
                Optional.of(need(head(mr), "fetch failed")), none, PrDiffEtags.none()));
        probe("contents.diff(!" + bigMr + ")", () -> contents.diff(target, bigMr, Optional.empty(), Optional.empty(), none, PrDiffEtags.none()));
        Optional<DiffLine> line = smallDiff.flatMap(LiveGitLabCompatibility::firstAddedLine);
        for (boolean fullDiff : ImmutableList.of(true, false)) {
            probe("contents.fileContent(!" + mr + ", " + line.map(DiffLine::path).orElse("?") + (fullDiff ? ", full diff)" : ", start..head)"),
                    () -> contents.fileContent(target, mr, need(line, "the diff has no added line").path(), need(start(mr), "fetch returned no start sha"),
                            need(head(mr), "fetch failed"), fullDiff));
        }
    }

    private void metadata()
    {
        RepositoryMetadataReader metadata = gitlab.metadata();
        probe("metadata.overview()", () -> metadata.overview(target));
        probe("metadata.contributors()", () -> metadata.contributors(target));
        probe("metadata.repositoryUsers()", () -> metadata.repositoryUsers(target));
        probe("metadata.repositoryTeams()", () -> metadata.repositoryTeams(target));
        probe("metadata.repositoryLabels()", () -> metadata.repositoryLabels(target));
    }

    private void discovery()
    {
        PullRequestDiscovery discovery = gitlab.discovery();
        Instant week = Instant.now().minus(Duration.ofDays(7));
        for (DiscoveryQuery query : ImmutableList.of(DiscoveryQuery.of(AUTHORED, OPEN), DiscoveryQuery.of(AUTHORED, CLOSED), DiscoveryQuery.of(REVIEW_REQUESTED, OPEN),
                DiscoveryQuery.of(REVIEWED, ANY), DiscoveryQuery.of(INVOLVED, ANY), DiscoveryQuery.repository(project), DiscoveryQuery.repository(project).updatedSince(week))) {
            probe("discovery.find(" + query.key() + (query.updatedSince().isPresent() ? ", updated in 7 days" : "") + ")", () -> discovery.find(access, query, 1, 20));
        }
        List<ActivityNotice> notices = new ArrayList<>();
        probe("discovery.poll(" + login + ")", () -> {
            discovery.poll(access, login, notices::add);
            return notices;
        });
        probe("discovery.reviewRequests(" + login + ")", () -> discovery.reviewRequests(login));
        probe("discovery.mentions(" + login + ")", () -> discovery.mentions(login));
        List<PullRequestRef> refs = requests().stream().map(this::pullRequestRef).collect(toImmutableList());
        probe("discovery.summaries(" + requests() + ")", () -> discovery.summaries(access, refs, login));
        probe("discovery.summaryBatchSize()", discovery::summaryBatchSize);
        probe("discovery.hasAuthorReplyAfter(!" + mr + ")",
                () -> discovery.hasAuthorReplyAfter(access, pullRequestRef(mr), login, login, Instant.now().minus(Duration.ofDays(1)), "probe"));
    }

    private void team()
    {
        TeamActivityReader team = gitlab.team();
        TeamIdentity identity = new TeamIdentity(viewerId, login, ImmutableMap.of(viewerId, login));
        probe("team.person(" + login + ")", () -> team.person(access, login));
        probe("team.person(reviewer " + reviewer.orElse("?") + ")", () -> team.person(access, need(reviewer, "BYTEQUAY_GITLAB_LIVE_REVIEWER is not set")));
        probe("team.personActivity(" + login + ")", () -> team.personActivity(access, login, identity));
        probe("team.repositoryActivity(" + project + ", offset 0)", () -> team.repositoryActivity(access, project, identity, 0, (_, _) -> Optional.empty()));
    }

    private void ci()
    {
        long projectId = Long.parseLong(target.repository().remoteId());
        probe("ci.failedJobsOnBranch(" + projectId + ", " + base + ")", () -> gitlab.ci().failedJobsOnBranch(target, projectId, base));
        pipeline(ciMr);
        if (jobMr.isPresent()) {
            pipeline(jobMr.orElseThrow());
        }
        else {
            skip("ci.* and contents.readCheckLog on a request with jobs", "BYTEQUAY_GITLAB_LIVE_JOB_MR is not set");
        }
    }

    /** Reads the request's head pipeline, then reruns it; a pipeline that failed before any job reports its errors as one job with the pipeline's id. */
    private void pipeline(int number)
    {
        PullRequestCi ci = gitlab.ci();
        Optional<CiHeadSnapshot> snapshot = probe("ci.inspect(!" + number + ")", () -> ci.inspect(target, number));
        Optional<CiRun> run = snapshot.flatMap(found -> found.runs().stream().findFirst());
        Optional<CiJob> failedOrFirst = run.flatMap(found -> found.jobs().stream().filter(candidate -> candidate.conclusion().equals(Optional.of("failure"))).findFirst()
                .or(() -> found.jobs().stream().findFirst()));
        Optional<Long> job = failedOrFirst.map(CiJob::id).or(() -> run.map(CiRun::id));
        String what = "!" + number + ", job " + job.map(String::valueOf).orElse("?");
        probe("ci.downloadLog(" + what + ")", () -> downloadLog(number, need(job, "inspect found no run")));
        probe("ci.failureNotes(" + what + ")", () -> ci.failureNotes(target, number, need(job, "inspect found no run")));
        probe("contents.readCheckLog(" + what + ")", () -> gitlab.contents().readCheckLog(target, number, need(job, "inspect found no run")));
        probe("ci.deliveryChecks(!" + number + ")", () -> ci.deliveryChecks(target, number));
        if (readOnly) {
            skip("ci.rerunFailedJobs(!" + number + ")", "read-only run");
            return;
        }
        probe("ci.rerunFailedJobs(!" + number + ", authorized)", () -> ci.rerunFailedJobs(target, number, new RerunFailedJobsCommand(need(run, "inspect found no run").id(),
                need(snapshot, "inspect failed").headSha(), need(run, "inspect found no run").attempt(), true)));
    }

    private String downloadLog(int number, long jobId)
            throws IOException
    {
        Path file = Files.createTempFile("gitlab-job-", ".log");
        try {
            Optional<String> unavailable = gitlab.ci().downloadLog(target, number, jobId, file);
            return unavailable.map(reason -> "unavailable: " + reason).orElse(Files.size(file) + " bytes: " + Files.readString(file));
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    private void writer()
            throws IOException
    {
        PullRequestWriter writer = gitlab.writer();
        Optional<Long> note = probe("writer.comment(!" + mr + ")", () -> writer.comment(target, mr, "ByteQuay probe comment " + stamp)).map(PrTimelineEvent::remoteId);
        act("writer.editComment(!" + mr + ", ISSUE_COMMENT note)",
                () -> writer.editComment(target, mr, CommentTarget.ISSUE_COMMENT, need(note, "comment failed"), "ByteQuay probe comment, edited " + stamp));
        for (String pass : ImmutableList.of("add", "remove")) {
            probe("writer.toggleReaction(!" + mr + ", note, +1, " + pass + ")",
                    () -> writer.toggleReaction(target, mr, CommentTarget.ISSUE_COMMENT, need(note, "comment failed"), Reaction.THUMBS_UP, pass.equals("remove")));
            probe("writer.toggleReaction(!" + mr + ", request, eyes, " + pass + ")",
                    () -> writer.toggleReaction(target, mr, CommentTarget.PULL_REQUEST, null, Reaction.EYES, pass.equals("remove")));
        }

        Optional<DiffLine> line = smallDiff.flatMap(LiveGitLabCompatibility::firstAddedLine);
        String where = line.map(found -> found.path() + ":" + found.line()).orElse("?");
        Optional<Long> created = probe("writer.createReviewComment(!" + mr + ", " + where + " RIGHT)",
                () -> writer.createReviewComment(target, mr, inline(need(line, "the diff has no added line"), "ByteQuay probe line comment " + stamp))).map(PrTimelineEvent::remoteId);
        // Without a new thread, the request's own discussion stands in.
        Optional<Long> thread = created.or(() -> existingThread);
        probe("writer.createReviewComment(!" + mr + ", reply to thread " + thread.orElse(0L) + ")", () -> writer.createReviewComment(target, mr,
                new InlineReviewComment(line.map(DiffLine::path).orElse(""), "ByteQuay probe reply " + stamp, 0, "RIGHT", null, null, null,
                        OptionalLong.of(need(thread, "no thread to reply to")))));
        act("writer.replyToReviewComment(!" + mr + ", thread)", () -> writer.replyToReviewComment(target, mr, need(thread, "no thread"), "ByteQuay probe reply 2 " + stamp));
        act("writer.editComment(!" + mr + ", REVIEW_COMMENT thread root)",
                () -> writer.editComment(target, mr, CommentTarget.REVIEW_COMMENT, need(thread, "no thread"), "ByteQuay probe line comment, edited " + stamp));
        act("writer.resolveThread(!" + mr + ", thread, resolved)", () -> writer.resolveThread(target, mr, need(thread, "no thread"), true));
        act("writer.resolveThread(!" + mr + ", thread, unresolved)", () -> writer.resolveThread(target, mr, need(thread, "no thread"), false));

        Optional<PrRawDetail> detail = detail(mr);
        act("writer.updateTitle(!" + mr + ", \"Draft: \" + title)", () -> writer.updateTitle(target, mr, "Draft: " + need(detail, "fetch failed").title()));
        act("writer.markReady(!" + mr + ")", () -> writer.markReady(target, mr));
        act("writer.updateDescription(!" + mr + ", with a probe line)",
                () -> writer.updateDescription(target, mr, need(description, "description failed") + "\n\nByteQuay probe " + stamp));
        act("writer.updateDescription(!" + mr + ", original)", () -> writer.updateDescription(target, mr, need(description, "description failed")));
        act("writer.updateMetadata(!" + mr + ", [" + login + "], labels + " + PROBE_LABEL + ")", () -> writer.updateMetadata(target, mr, ImmutableList.of(login),
                ImmutableSet.<String>builder().addAll(need(detail, "fetch failed").labels()).add(PROBE_LABEL).build().asList()));
        String other = reviewer.orElse(login);
        act("writer.updateReviewer(!" + mr + ", " + other + ", requested)", () -> writer.updateReviewer(target, mr, other, true));
        act("writer.updateReviewer(!" + mr + ", " + other + ", removed)", () -> writer.updateReviewer(target, mr, other, false));
        probe("writer.submitReview(!" + mr + ", COMMENT, body + line comment at " + where + ")", () -> writer.submitReview(target, mr, ReviewVerdict.COMMENT,
                "ByteQuay probe review " + stamp, ImmutableList.of(inline(need(line, "the diff has no added line"), "ByteQuay probe review line " + stamp)),
                need(head(mr), "fetch failed")));
        probe("writer.submitReview(!" + mr + ", APPROVE)", () -> writer.submitReview(target, mr, ReviewVerdict.APPROVE, "", ImmutableList.of(), need(head(mr), "fetch failed")));
        probe("writer.merge(!" + mr + ", DEFAULT, stale sha: expect a conflict)", () -> writer.merge(target, mr, MergeStrategy.DEFAULT, STALE_SHA));
        skip("writer.merge(!" + mr + ", current head)", "merge skipped: the small request stays open");
        for (int number : ImmutableList.<Integer>builder().add(ciMr).addAll(jobMr.stream().iterator()).build()) {
            act("writer.rerunFailedChecks(!" + number + ")", () -> writer.rerunFailedChecks(target, number));
        }
        act("writer.removeFromMergeQueue(!" + mr + ", auto-merge)", () -> writer.removeFromMergeQueue(target, mr, true));
        probe("writer.hasMergeQueue(" + base + ")", () -> writer.hasMergeQueue(target, base));

        // The port opens a request from an existing branch; GitLab's commit API makes one without git.
        String branch = "bytequay-probe-" + Instant.now().getEpochSecond();
        String commit = mapper.writeValueAsString(ImmutableMap.of("branch", branch, "start_branch", base, "commit_message", "ByteQuay probe branch",
                "actions", ImmutableList.of(ImmutableMap.of("action", "create", "file_path", "bytequay-probe/" + branch + ".txt", "content", stamp + "\n"))));
        exchange("http POST repository/commits: branch " + branch + " for writer.create", "POST",
                "/api/v4/projects/" + target.repository().remoteId() + "/repository/commits", Optional.of(commit));
        Optional<CreatedPullRequest> request = probe("writer.create(" + branch + " -> " + base + ", draft)",
                () -> writer.create(target, repoRef(project), branch, base, "ByteQuay probe " + stamp, "Opened by the GitLab compatibility probe", true));
        probe("contents.identitiesByHead(" + project + ", " + branch + ")", () -> gitlab.contents().identitiesByHead(target, repoRef(project), branch));
        act("writer.markReady(new request)", () -> writer.markReady(target, need(request, "create failed").number()));
        act("writer.close(new request)", () -> writer.close(target, need(request, "create failed").number()));
        probe("contents.discussion(!" + mr + ", after the writes)", () -> gitlab.contents().discussion(target, mr, stored(need(detail, "fetch failed"))));
    }

    private void issues()
    {
        IssueClient issues = gitlab.issues();
        probe("issues.list(open, page 1)", () -> issues.list(target, Optional.empty(), 1));
        probe("issues.list(updated in 30 days, page 1)", () -> issues.list(target, Optional.of(Instant.now().minus(Duration.ofDays(30))), 1));
        probe("issues.find(#" + issue + ")", () -> issues.find(target, issue));
        probe("issues.preview(#" + issue + ")", () -> issues.preview(target, issue));
        probe("issues.detail(#" + issue + ")", () -> issues.detail(target, issue));
        probe("issues.source(#" + issue + ", any state)", () -> issues.source(target, issue, false));
        probe("issues.source(#" + issue + ", open only)", () -> issues.source(target, issue, true));
        probe("issues.activity(#" + issue + ")", () -> issues.activity(target, issue));
        probe("issues.children(#" + issue + ")", () -> issues.children(target, issue));
        if (readOnly) {
            skip("issues.create and its writes", "read-only run");
            return;
        }
        Optional<HostedIssueSource> created = probe("issues.create()", () -> issues.create(target, "ByteQuay probe issue " + stamp, "Opened by the GitLab compatibility probe"));
        int scratch = created.map(HostedIssueSource::number).orElse(issue);
        String which = "#" + scratch + (created.isPresent() ? ", new" : ", the fixture: create failed");
        probe("issues.comment(" + which + ")", () -> issues.comment(target, scratch, "ByteQuay probe issue comment " + stamp));
        probe("issues.updateMetadata(" + which + ", [" + login + "], [" + PROBE_LABEL + "])",
                () -> issues.updateMetadata(target, scratch, ImmutableList.of(login), ImmutableList.of(PROBE_LABEL)));
        probe("issues.updateType(" + which + ", incident)", () -> issues.updateType(target, scratch, "incident"));
        probe("issues.updateType(" + which + ", issue)", () -> issues.updateType(target, scratch, "issue"));
        act("issues.close(new issue)", () -> issues.close(target, need(created, "create failed; the fixture issue stays open").number()));
        probe("issues.detail(new issue, after the writes)", () -> issues.detail(target, need(created, "create failed").number()));
    }

    private <T> Optional<T> probe(String label, Callable<T> call)
    {
        try {
            T result = call.call();
            record(label, "OK " + clip(summary(result), 200));
            succeeded++;
            return Optional.ofNullable(result);
        }
        catch (Skipped skip) {
            skip(label, skip.getMessage());
        }
        catch (Exception failure) {
            String type = failure instanceof HostingFailure hosting ? "HostingFailure(" + hosting.kind() + ")" : failure.getClass().getSimpleName();
            record(label, "FAIL " + type + ": " + clip(String.valueOf(failure.getMessage()), 200));
            failed++;
        }
        return Optional.empty();
    }

    private void act(String label, Action action)
    {
        probe(label, () -> {
            action.run();
            return "done";
        });
    }

    private void skip(String label, String reason)
    {
        record(label, "SKIP " + reason);
        skipped++;
    }

    private void record(String label, String outcome)
    {
        lines.add(label + " | " + outcome);
    }

    private static <T> T need(Optional<T> value, String reason)
    {
        return value.orElseThrow(() -> new Skipped(reason));
    }

    private Optional<String> get(String label, String path)
    {
        return exchange(label, "GET", path, Optional.empty());
    }

    private void graphql(String label, String query, int iid)
            throws IOException
    {
        String body = mapper.writeValueAsString(ImmutableMap.of("query", query, "variables", ImmutableMap.of("path", project, "iid", Integer.toString(iid))));
        exchange(label, "POST", GRAPHQL, Optional.of(body));
    }

    /** One plain request with the same token; its line holds the status and the first 300 characters of the body. */
    private Optional<String> exchange(String label, String method, String path, Optional<String> json)
    {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(web + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .method(method, json.map(HttpRequest.BodyPublishers::ofString).orElse(HttpRequest.BodyPublishers.noBody()));
        json.ifPresent(_ -> request.header("Content-Type", "application/json"));
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            // GraphQL answers a query it cannot run with 200 and errors.
            boolean ok = response.statusCode() / 100 == 2 && !(path.equals(GRAPHQL) && hasErrors(response.body()));
            record(label, (ok ? "OK" : "FAIL") + " HTTP " + response.statusCode() + " " + clip(response.body(), 300));
            if (ok) {
                succeeded++;
                return Optional.of(response.body());
            }
        }
        catch (IOException | InterruptedException failure) {
            record(label, "FAIL " + failure.getClass().getSimpleName() + ": " + clip(String.valueOf(failure.getMessage()), 200));
        }
        failed++;
        return Optional.empty();
    }

    private boolean hasErrors(String body)
    {
        try {
            return mapper.readTree(body).has("errors");
        }
        catch (IOException e) {
            return true;
        }
    }

    private Optional<String> firstId(String body)
    {
        try {
            JsonNode versions = mapper.readTree(body);
            return versions.isArray() && !versions.isEmpty() ? Optional.of(versions.get(0).path("id").asText()) : Optional.empty();
        }
        catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Files, diff characters and truncation flags of a diff payload; {@code field} names its file list, or is empty for a top-level list. */
    private String stats(Optional<String> body, String field)
            throws IOException
    {
        if (body.isEmpty()) {
            return "unavailable";
        }
        JsonNode root = mapper.readTree(body.orElseThrow());
        JsonNode files = field.isEmpty() ? root : root.path(field);
        long characters = 0;
        int empty = 0;
        int tooLarge = 0;
        int collapsed = 0;
        for (JsonNode file : files) {
            String diff = file.path("diff").asText("");
            characters += diff.length();
            empty += diff.isEmpty() ? 1 : 0;
            tooLarge += file.path("too_large").asBoolean() ? 1 : 0;
            collapsed += file.path("collapsed").asBoolean() ? 1 : 0;
        }
        return "files=" + files.size() + " diffChars=" + characters + " emptyDiffs=" + empty + " tooLarge=" + tooLarge + " collapsed=" + collapsed
                + (root.has("overflow") ? " overflow=" + root.get("overflow").asBoolean() : "");
    }

    private void report(Optional<String> out)
            throws IOException
    {
        lines.add("");
        lines.add("OK " + succeeded + ", FAIL " + failed + ", SKIP " + skipped);
        String text = String.join("\n", lines) + "\n";
        System.out.print(text);
        if (out.isPresent()) {
            Files.writeString(Path.of(out.orElseThrow()), text);
        }
    }

    private static String summary(Object value)
    {
        return switch (value) {
            case null -> "null";
            case String text -> text;
            case PullRequestFetch fetch -> fetch.pullRequest().map(request -> "!" + request.number() + " " + request.state() + " draft=" + request.draft()
                    + " mergeable=" + request.mergeable()).orElse("not modified") + " etag=" + fetch.etag().isPresent()
                    + fetch.detail().map(detail -> " head=" + detail.headSha() + " start=" + detail.baseSha() + " changedFiles=" + detail.changedFiles()).orElse("");
            case PullRequestListPage page -> page.items().size() + " requests, hasNext=" + page.hasNext()
                    + page.items().stream().findFirst().map(first -> ", first !" + first.number() + " " + first.state()).orElse("");
            case PullRequestContentReader.Diff diff -> diff.files().size() + " files, " + diff.files().stream().filter(file -> file.patch() != null).count() + " with a patch of "
                    + diff.files().stream().filter(file -> file.patch() != null).mapToLong(file -> file.patch().length()).sum() + " chars, " + diff.commits().size() + " commits";
            case PullRequestContentReader.Checks checks -> checks.checkRuns().stream()
                    .map(run -> run.name() + ":" + Optional.ofNullable(run.conclusion()).orElse(run.status())).toList() + " complete=" + checks.complete();
            case StoredPrDetail detail -> detail.reviews().size() + " approvals, timeline " + events(detail.timeline()) + ", unavailable " + detail.unavailableSections();
            case RepositoryIssueDetail detail -> "timeline " + events(detail.timeline()) + ", type " + detail.currentType() + " of " + detail.availableTypes();
            case CiHeadSnapshot snapshot -> "head " + snapshot.headSha() + ", runs " + snapshot.runs().stream().map(run -> run.id() + " " + run.name() + " " + run.status()
                    + "/" + run.conclusion().orElse("-") + " jobs " + run.jobs().stream().map(job -> job.id() + ":" + job.name() + ":" + job.conclusion().orElse(job.status()))
                    .toList()).toList();
            case TeamObservation observation -> observation.events().size() + " events, errors " + observation.errors() + ", rateLimited=" + observation.rateLimited();
            case PrTimelineEvent event -> event.event() + " id=" + event.remoteId() + " " + event.body();
            case Collection<?> items -> items.size() + " items" + (items.isEmpty() ? "" : ", first " + items.iterator().next());
            default -> value.toString();
        };
    }

    private static Map<String, Long> events(List<PrTimelineEvent> timeline)
    {
        return timeline.stream().collect(groupingBy(PrTimelineEvent::event, TreeMap::new, counting()));
    }

    private static String clip(String text, int length)
    {
        String line = text.replaceAll("\\s*\\R\\s*", " / ");
        return line.length() <= length ? line : line.substring(0, length) + "...";
    }

    private static StoredPrDetail stored(PrRawDetail raw)
    {
        return new StoredPrDetail(raw, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), true);
    }

    private static Optional<Long> firstThread(StoredPrDetail detail)
    {
        return detail.timeline().stream().filter(event -> event.remoteId() != null && event.event().equals("review_thread")).findFirst()
                .or(() -> detail.timeline().stream().filter(event -> event.remoteId() != null && event.event().equals("commented")).findFirst())
                .map(PrTimelineEvent::remoteId);
    }

    /** The first added line of the first file that has one, where a line comment sits on the new side. */
    private static Optional<DiffLine> firstAddedLine(PullRequestContentReader.Diff diff)
    {
        for (DiffFile file : diff.files()) {
            int line = 0;
            for (String text : Optional.ofNullable(file.patch()).orElse("").split("\n")) {
                Matcher hunk = HUNK.matcher(text);
                if (hunk.matches()) {
                    line = Integer.parseInt(hunk.group(1));
                }
                else if (line > 0 && text.startsWith("+")) {
                    return Optional.of(new DiffLine(file.filename(), line));
                }
                else if (line > 0 && text.startsWith(" ")) {
                    line++;
                }
            }
        }
        return Optional.empty();
    }

    private InlineReviewComment inline(DiffLine line, String body)
    {
        return new InlineReviewComment(line.path(), body, line.line(), "RIGHT", null, null, head(mr).orElse(null), OptionalLong.empty());
    }

    private List<Integer> requests()
    {
        return ImmutableList.<Integer>builder().add(mr, bigMr, ciMr).addAll(jobMr.stream().iterator()).build();
    }

    private Optional<PrRawDetail> detail(int number)
    {
        return Optional.ofNullable(details.get(number));
    }

    private Optional<String> head(int number)
    {
        return detail(number).map(PrRawDetail::headSha);
    }

    /** GitLab's start sha, the target tip the diff starts from; the reader leaves it out while the diff is stale. */
    private Optional<String> start(int number)
    {
        return detail(number).map(PrRawDetail::baseSha);
    }

    private PullRequestRef pullRequestRef(int number)
    {
        RepoRef repository = repoRef(project);
        return PullRequestRef.of(repository.owner(), repository.repo(), number);
    }

    private static RepoRef repoRef(String path)
    {
        int slash = path.lastIndexOf('/');
        return RepoRef.of(path.substring(0, slash), path.substring(slash + 1));
    }

    private static Optional<String> env(String name)
    {
        return Optional.ofNullable(System.getenv(name)).map(String::strip).filter(value -> !value.isEmpty());
    }

    private static String required(String name)
    {
        return env(name).orElseThrow(() -> new IllegalStateException(name + " is not set"));
    }

    private record DiffLine(String path, int line) {}

    @FunctionalInterface
    private interface Action
    {
        void run()
                throws Exception;
    }

    /** A call that could not run because an earlier call or an optional fixture is missing. */
    private static final class Skipped
            extends RuntimeException
    {
        Skipped(String reason)
        {
            super(reason);
        }
    }
}
