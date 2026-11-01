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

import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.MergedPullRequest;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrDiffEtags;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReaction;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequestCheckLog;
import com.bytequay.core.domain.PullRequestIdentity;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.HostingUrls;
import com.bytequay.core.hosting.PullRequestAssociation;
import com.bytequay.core.hosting.PullRequestContentReader;
import com.bytequay.integration.gitlab.impl.GitLabMergeRequestResponse.DiffRefs;
import com.bytequay.integration.gitlab.impl.GitLabMergeRequestResponse.Pipeline;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.MISSING_OR_INACCESSIBLE;
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.core.hosting.HostingFailure.Kind.UNAVAILABLE;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.encode;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.mapped;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.mergeRequestPath;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.projectPath;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.Math.toIntExact;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

/** GitLab payloads become the same detail, discussion, check and diff values used by the desktop. */
public final class GitLabPullRequestContentReader
        implements PullRequestContentReader
{
    private final GitLabHttpClient http;
    private final GitLabDiffs diffs;
    private final GitLabPullRequestCi ci;

    public GitLabPullRequestContentReader(HttpClient http, ObjectMapper mapper)
    {
        this(http, mapper, GitLabRelease.CURRENT);
    }

    public GitLabPullRequestContentReader(HttpClient http, ObjectMapper mapper, GitLabRelease release)
    {
        this.http = new GitLabHttpClient(http, mapper);
        this.diffs = release.diffs(this.http);
        this.ci = new GitLabPullRequestCi(this.http, release);
    }

    @Override
    public String headRef(int number)
    {
        checkArgument(number > 0, "number must be positive");
        return "refs/merge-requests/" + number + "/head";
    }

    @Override
    public PullRequestIdentity identity(HostingTarget target, int number)
    {
        return identity(http, target, number, get(target.access(), mergeRequestPath(target, number), GitLabMergeRequestResponse.class));
    }

    /** The identity of a request already read, so its other facts describe the same revision. */
    static PullRequestIdentity identity(GitLabHttpClient http, HostingTarget target, int number, GitLabMergeRequestResponse request)
    {
        if (!Long.valueOf(number).equals(request.iid()) || !target.repository().remoteId().equals(String.valueOf(request.targetProjectId()))) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned another merge request");
        }
        DiffRefs refs = request.diffRefs();
        if (refs == null || request.sha() == null || !request.sha().equals(refs.headSha())) {
            throw new HostingFailure(REVISION_CONFLICT, "GitLab has not prepared the current merge request diff");
        }
        Project source = http.get(target.access(), "projects/" + request.sourceProjectId(), Optional.empty(), Project.class).body().orElseThrow();
        RepoRef targetRepository = repositoryRef(target.repository().fullPath());
        return mapped("merge request", () -> {
            String url = HostingUrls.requireWithinBase(URI.create(request.webUrl()), target.access().server().webBaseUrl()).toASCIIString();
            PullRequestIdentity.State state = PullRequestIdentity.State.valueOf(request.requestState().toUpperCase(Locale.ROOT));
            return new PullRequestIdentity(PullRequestRef.of(targetRepository.owner(), targetRepository.repo(), number), url, repositoryRef(source.pathWithNamespace()),
                    request.sourceBranch(), request.sha(), targetRepository, request.targetBranch(), refs.startSha(), state, request.isDraft(), request.author().username());
        });
    }

    @Override
    public String description(HostingTarget target, int number)
    {
        GitLabMergeRequestResponse request = get(target.access(), mergeRequestPath(target, number), GitLabMergeRequestResponse.class);
        if (!Long.valueOf(number).equals(request.iid())) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned another merge request");
        }
        return Optional.ofNullable(request.description()).orElse("");
    }

    @Override
    public List<PullRequestIdentity> identitiesByHead(HostingTarget target, RepoRef sourceRepository, String branch)
    {
        Project source = get(target.access(), "projects/" + encode(sourceRepository.fullName()), Project.class);
        String path = projectPath(target) + "/merge_requests?scope=all&state=all&source_branch=" + encode(branch);
        return http.pages(target.access(), path, GitLabMergeRequestResponse[].class).stream()
                .filter(request -> request.sourceProjectId() != null && request.sourceProjectId() == source.id())
                .map(request -> identity(target, mapped("merge request", () -> toIntExact(request.iid())))).collect(toImmutableList());
    }

    @Override
    public MergedPullRequest merged(HostingTarget target, int number)
    {
        String path = mergeRequestPath(target, number);
        GitLabMergeRequestResponse before = get(target.access(), path, GitLabMergeRequestResponse.class);
        PullRequestIdentity identity = identity(http, target, number, before);
        if (identity.state() != PullRequestIdentity.State.MERGED || before.mergedAt() == null) {
            throw new HostingFailure(REVISION_CONFLICT, "The merge request is not verified merged");
        }
        // GitLab lists a merge request's commits newest first.
        List<String> commits = Lists.reverse(http.pages(target.access(), path + "/commits", Commit[].class).stream().map(Commit::id).collect(toImmutableList()));
        if (commits.isEmpty() || !commits.getLast().equals(identity.headSha()) || ImmutableSet.copyOf(commits).size() != commits.size()) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned an incomplete merge request commit list");
        }
        GitLabMergeRequestResponse after = get(target.access(), path, GitLabMergeRequestResponse.class);
        if (!identity(http, target, number, after).equals(identity) || !landed(after).equals(landed(before))) {
            throw new HostingFailure(REVISION_CONFLICT, "The merge request changed while its merge evidence was being read");
        }
        return mapped("merge request", () -> new MergedPullRequest(identity, before.targetProjectId(), instant(before.mergedAt()), landed(before), commits));
    }

    /** The commit that landed: the merge commit, else the squash commit, else the request's own head when it fast-forwarded. */
    private static String landed(GitLabMergeRequestResponse request)
    {
        return Optional.ofNullable(request.mergeCommitSha()).or(() -> Optional.ofNullable(request.squashCommitSha())).orElse(request.sha());
    }

    @Override
    public List<String> recentMergedTitles(HostingTarget target, String baseBranch, Instant since, int limit)
    {
        // Ordering by merge time needs GitLab 17.2; a merge also updates a request, so this orders by update time and keeps those merged since.
        String path = projectPath(target) + "/merge_requests?state=merged&target_branch=" + encode(baseBranch) + "&updated_after=" + encode(since.toString())
                + "&order_by=updated_at&sort=desc&per_page=" + limit;
        GitLabMergeRequestResponse[] requests = get(target.access(), path, GitLabMergeRequestResponse[].class);
        return mapped("merge request", () -> Arrays.stream(requests).filter(request -> request.mergedAt() != null && !instant(request.mergedAt()).isBefore(since))
                .sorted(comparing((GitLabMergeRequestResponse request) -> instant(request.mergedAt())).reversed())
                .map(GitLabMergeRequestResponse::title).limit(limit).collect(toImmutableList()));
    }

    @Override
    public List<PullRequestAssociation> mergedWithCommit(HostingTarget target, String sha)
    {
        List<GitLabMergeRequestResponse> requests = http.pages(target.access(), projectPath(target) + "/repository/commits/" + encode(sha) + "/merge_requests",
                GitLabMergeRequestResponse[].class);
        return mapped("merge request", () -> requests.stream()
                .filter(request -> "merged".equals(request.state()) && target.repository().remoteId().equals(String.valueOf(request.targetProjectId())))
                .map(request -> new PullRequestAssociation(toIntExact(request.iid()), Optional.ofNullable(request.title()).orElse(""),
                        HostingUrls.requireWithinBase(URI.create(request.webUrl()), target.access().server().webBaseUrl()).toASCIIString(), request.targetBranch(),
                        Optional.ofNullable(request.mergedAt()).orElse("")))
                .collect(toImmutableList()));
    }

    private static RepoRef repositoryRef(String path)
    {
        int slash = path.lastIndexOf('/');
        checkArgument(slash > 0 && slash < path.length() - 1, "GitLab returned invalid repository coordinates");
        return RepoRef.of(path.substring(0, slash), path.substring(slash + 1));
    }

    @Override
    public boolean viewerCanWrite(HostingTarget target)
    {
        Project project = get(target.access(), projectPath(target), Project.class);
        return Optional.ofNullable(project.permissions()).map(permissions -> Math.max(level(permissions.projectAccess()), level(permissions.groupAccess())) >= 30).orElse(false);
    }

    @Override
    public StoredPrDetail discussion(HostingTarget target, int number, StoredPrDetail existing)
    {
        String path = mergeRequestPath(target, number);
        List<String> unavailable = new ArrayList<>();
        List<PrReviewState> reviews = optional(StoredPrDetail.REVIEWS, unavailable, () -> get(target.access(), path + "/approvals", Approvals.class).approvedBy().stream()
                .map(approval -> new PrReviewState(null, approval.user().username(), approval.user().avatarUrl(), false,
                        "APPROVED", null, null, existing.raw().htmlUrl(), existing.raw().headSha())).collect(toImmutableList()));
        List<PrTimelineEvent> timeline = new ArrayList<>(optional(StoredPrDetail.TIMELINE, unavailable,
                () -> discussions(target.access(), path, existing.raw(), unavailable)));
        if (unavailable.contains(StoredPrDetail.TIMELINE)) {
            unavailable.add(StoredPrDetail.COMMENTS);
            unavailable.add(StoredPrDetail.REVIEW_THREADS);
        }
        timeline.addAll(optional(StoredPrDetail.COMMITS, unavailable, () -> commits(target.access(), path).stream()
                .map(commit -> new PrTimelineEvent(null, "committed", commit.author(), commit.authoredAt(), commit.message(),
                        PrTimelinePayload.builder().commitSha(commit.sha()).commitMessage(commit.message()).build(), Optional.empty()))
                .collect(toImmutableList())));
        List<PrReaction> reactions = optional(StoredPrDetail.REACTIONS, unavailable, () -> reactions(target.access(), path));
        if (!reactions.isEmpty()) {
            timeline.add(new PrTimelineEvent(null, "description_reactions", null, null, null,
                    PrTimelinePayload.builder().reactions(reactions).build(), Optional.empty()));
        }
        if (!existing.checkRunsComplete()) {
            unavailable.add(StoredPrDetail.CHECKS);
        }
        return new StoredPrDetail(existing.raw(), reviews, timeline, existing.checkRuns(), unavailable, existing.viewerCanWrite());
    }

    private List<PrTimelineEvent> discussions(HostingAccess access, String path, PrRawDetail raw, List<String> unavailable)
    {
        List<GitLabDiscussion> discussions = http.pages(access, path + "/discussions", GitLabDiscussion[].class);
        Map<Long, List<PrReaction>> reactions = noteReactions(access, path, discussions, unavailable);
        List<PrTimelineEvent> events = new ArrayList<>();
        String url = raw.htmlUrl();
        for (GitLabDiscussion discussion : discussions) {
            if (discussion.notes().isEmpty()) {
                continue;
            }
            GitLabNote root = discussion.notes().getFirst();
            if (discussion.individualNote() || root.system()) {
                for (GitLabNote note : discussion.notes()) {
                    events.add(new PrTimelineEvent(note.id(), note.system() ? "system_note" : "commented", note.author().username(), instant(note.createdAt()), note.timelineBody(),
                            PrTimelinePayload.builder().actorAvatarUrl(note.author().avatarUrl()).htmlUrl(url + "#note_" + note.id())
                                    .reactions(reactions.getOrDefault(note.id(), ImmutableList.of())).build(), Optional.empty()));
                }
                continue;
            }
            GitLabDiffPosition position = root.position();
            List<PrTimelineComment> comments = discussion.notes().stream().map(note -> new PrTimelineComment(note.id(), note.author().username(), note.author().avatarUrl(),
                    false, instant(note.createdAt()), note.body(), url + "#note_" + note.id(), null, note == root ? null : root.id(),
                    reactions.getOrDefault(note.id(), ImmutableList.of()))).collect(toImmutableList());
            PrTimelinePayload.Builder payload = PrTimelinePayload.builder().actorAvatarUrl(root.author().avatarUrl()).htmlUrl(url + "#note_" + root.id())
                    .resolved(root.resolved()).comments(comments);
            if (position != null) {
                position.applyTo(payload, raw.headSha(), raw.baseSha());
            }
            events.add(new PrTimelineEvent(root.id(), "review_thread", root.author().username(), instant(root.createdAt()), root.body(), payload.build(), Optional.empty()));
        }
        return ImmutableList.copyOf(events);
    }

    private Map<Long, List<PrReaction>> noteReactions(HostingAccess access, String path, List<GitLabDiscussion> discussions, List<String> unavailable)
    {
        Map<Long, Future<List<PrReaction>>> pending = new LinkedHashMap<>();
        try (var executor = Executors.newFixedThreadPool(4, Thread.ofVirtual().factory())) {
            try {
                discussions.stream().flatMap(discussion -> discussion.notes().stream()).filter(note -> !note.system())
                        .forEach(note -> pending.computeIfAbsent(note.id(), id -> executor.submit(() -> reactions(access, path + "/notes/" + id))));
                ImmutableMap.Builder<Long, List<PrReaction>> result = ImmutableMap.builder();
                pending.forEach((id, future) -> result.put(id, optional(StoredPrDetail.REACTIONS, unavailable, () -> awaitReactions(future))));
                return result.buildOrThrow();
            }
            finally {
                pending.values().forEach(future -> future.cancel(true));
            }
        }
    }

    private static List<PrReaction> awaitReactions(Future<List<PrReaction>> future)
    {
        try {
            return future.get();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HostingFailure(UNAVAILABLE, "GitLab reaction read was interrupted");
        }
        catch (ExecutionException e) {
            throwIfUnchecked(e.getCause());
            throw new HostingFailure(UNAVAILABLE, "Could not read GitLab reactions");
        }
    }

    @Override
    public Checks checks(HostingTarget target, int number, String headSha,
            List<PrCheckRunState> existing, Optional<String> checksEtag, Optional<String> statusesEtag)
    {
        GitLabMergeRequestResponse request = get(target.access(), mergeRequestPath(target, number), GitLabMergeRequestResponse.class);
        if (!headSha.equals(request.sha())) {
            throw new HostingFailure(REVISION_CONFLICT, "Merge request head changed while reading checks");
        }
        if (request.headPipeline() == null) {
            return new Checks(ImmutableList.of(), Optional.empty(), Optional.empty(), true);
        }
        Pipeline pipeline = request.headPipeline();
        String path = pipeline.path(target.repository());
        // Pipeline access can differ from merge request access, especially for fork pipelines.
        List<String> unavailable = new ArrayList<>();
        List<PrCheckRunState> checks = new ArrayList<>(optional(StoredPrDetail.CHECKS, unavailable, () -> http.pages(target.access(), path + "/jobs", GitLabJob[].class).stream()
                .map(job -> check(job.id(), job.name(), job.status(), job.webUrl(), "job", job.startedAt(), job.finishedAt(), job.allowFailure())).collect(toImmutableList())));
        checks.addAll(optional(StoredPrDetail.CHECKS, unavailable, () -> http.pages(target.access(), path + "/bridges", GitLabJob[].class).stream()
                .map(job -> check(job.id(), job.name(), job.status(), job.webUrl(), "status", job.startedAt(), job.finishedAt(), job.allowFailure())).collect(toImmutableList())));
        if (checks.isEmpty() || !unavailable.isEmpty()) {
            // A summary has no job identity; null replaces it on the next partial refresh.
            checks.add(mapped("pipeline", () -> check(null, "Pipeline #" + pipeline.id(), pipeline.status(), pipeline.webUrl(), "status", null, null, false)));
        }
        return new Checks(checks, Optional.empty(), Optional.empty(), unavailable.isEmpty());
    }

    private static PrCheckRunState check(Long id, String name, String state, String url, String source, String startedAt, String completedAt, boolean allowFailure)
    {
        return new PrCheckRunState(id, name, GitLabJob.state(state, allowFailure), GitLabJob.conclusion(state, allowFailure).orElse(null),
                url, source, instant(startedAt), instant(completedAt));
    }

    @Override
    public Diff diff(HostingTarget target, int number, Optional<String> baseSha, Optional<String> headSha,
            StoredPrDiff existing, PrDiffEtags etags)
    {
        String path = mergeRequestPath(target, number);
        List<GitLabFileDiff> files;
        List<DiffCommit> commits;
        if (baseSha.isPresent()) {
            Compare compare = get(target.access(), projectPath(target) + "/repository/compare?from=" + encode(baseSha.orElseThrow()) + "&to=" + encode(headSha.orElseThrow())
                    + "&straight=true", Compare.class);
            if (compare.compareTimeout()) {
                throw new HostingFailure(UNAVAILABLE, "GitLab comparison timed out; the diff is incomplete");
            }
            files = diffs.compared(compare.diffs());
            commits = mapped("comparison", () -> compare.commits().stream().map(GitLabPullRequestContentReader::commit).collect(toImmutableList()));
        }
        else {
            files = diffs.mergeRequest(target.access(), path);
            commits = commits(target.access(), path);
        }
        return mapped("changed file", () -> new Diff(files.stream().map(file -> {
            String patch = file.collapsed() || file.tooLarge() ? null : file.diff();
            int additions = patch == null ? 0 : (int) patch.lines().filter(line -> line.startsWith("+")).count();
            int deletions = patch == null ? 0 : (int) patch.lines().filter(line -> line.startsWith("-")).count();
            return new DiffFile(file.newPath(), file.newFile() ? "added" : file.deletedFile() ? "removed" : file.renamedFile() ? "renamed" : "modified",
                    additions, deletions, patch);
        }).collect(toImmutableList()), commits, PrDiffEtags.none()));
    }

    @Override
    public PullRequestCheckLog readCheckLog(HostingTarget target, int number, long jobId)
    {
        Pipeline pipeline = get(target.access(), mergeRequestPath(target, number), GitLabMergeRequestResponse.class).requireHeadPipeline();
        if (ci.currentJobs(target, pipeline).stream().noneMatch(job -> job.id() == jobId && "failed".equals(job.status()))) {
            throw new HostingFailure(REVISION_CONFLICT, "This failed job is no longer part of the current request checks");
        }
        return ci.log(target, pipeline, jobId);
    }

    @Override
    public DiffFileContent fileContent(HostingTarget target, int number, String path, String baseSha, String headSha, boolean fullDiff)
    {
        String targetProject = projectPath(target);
        GitLabMergeRequestResponse request = get(target.access(), mergeRequestPath(target, number), GitLabMergeRequestResponse.class);
        String source = request.sourceProjectId() == null ? targetProject : "projects/" + request.sourceProjectId();
        String contentBase = baseSha;
        if (fullDiff) {
            DiffRefs refs = request.diffRefs();
            if (refs == null || refs.baseSha() == null || !headSha.equals(request.sha()) || !headSha.equals(refs.headSha()) || !baseSha.equals(refs.startSha())) {
                throw new HostingFailure(REVISION_CONFLICT, "The merge request diff changed; refresh before opening file content");
            }
            contentBase = refs.baseSha();
        }
        Compare compare = get(target.access(), targetProject + "/repository/compare?from=" + encode(contentBase) + "&to=" + encode(headSha) + "&straight=true", Compare.class);
        if (compare.compareTimeout()) {
            throw new HostingFailure(UNAVAILABLE, "GitLab comparison timed out; the diff is incomplete");
        }
        GitLabFileDiff diff = mapped("comparison", () -> compare.diffs().stream().filter(file -> path.equals(file.newPath())).findFirst().orElse(null));
        String oldPath = Optional.ofNullable(diff).map(GitLabFileDiff::oldPath).orElse(path);
        return new DiffFileContent(diff != null && diff.newFile() ? null : file(target.access(), targetProject, oldPath, contentBase),
                diff != null && diff.deletedFile() ? null : file(target.access(), source, path, headSha));
    }

    private String file(HostingAccess access, String project, String path, String sha)
    {
        try {
            FileBody body = get(access, project + "/repository/files/" + encode(path) + "?ref=" + encode(sha), FileBody.class);
            if (!"base64".equals(body.encoding())) {
                throw new HostingFailure(INVALID_RESPONSE, "GitLab returned unsupported file encoding");
            }
            return mapped("file", () -> new String(Base64.getMimeDecoder().decode(body.content()), StandardCharsets.UTF_8));
        }
        catch (HostingFailure failure) {
            if (failure.kind() == MISSING_OR_INACCESSIBLE) {
                return null;
            }
            throw failure;
        }
    }

    private List<DiffCommit> commits(HostingAccess access, String path)
    {
        List<Commit> commits = http.pages(access, path + "/commits", Commit[].class);
        return mapped("commit", () -> commits.stream().map(GitLabPullRequestContentReader::commit).collect(toImmutableList()));
    }

    private static DiffCommit commit(Commit commit)
    {
        return new DiffCommit(commit.id(), commit.parentIds().stream().findFirst().orElse(null), commit.message(), commit.authorName(), instant(commit.authoredDate()));
    }

    private List<PrReaction> reactions(HostingAccess access, String path)
    {
        Map<String, Long> counts = http.pages(access, path + "/award_emoji", GitLabAward[].class).stream().collect(groupingBy(GitLabAward::name, counting()));
        return counts.entrySet().stream().map(entry -> new PrReaction(GitLabAward.reaction(entry.getKey()), toIntExact(entry.getValue()), null)).collect(toImmutableList());
    }

    private static <T> List<T> optional(String section, List<String> unavailable, Supplier<List<T>> supplier)
    {
        try {
            return mapped(section, supplier);
        }
        catch (HostingFailure failure) {
            if (Thread.currentThread().isInterrupted()) {
                throw failure;
            }
            switch (failure.kind()) {
                case AUTHENTICATION_REQUIRED, RATE_LIMITED -> throw failure;
                default -> {
                    if (!unavailable.contains(section)) {
                        unavailable.add(section);
                    }
                    return ImmutableList.of();
                }
            }
        }
    }

    private <T> T get(HostingAccess access, String path, Class<T> type)
    {
        return http.get(access, path, Optional.empty(), type).body().orElseThrow();
    }

    private static Instant instant(String value)
    {
        return Optional.ofNullable(value).map(Instant::parse).orElse(null);
    }

    private static int level(Access access)
    {
        return Optional.ofNullable(access).map(Access::level).orElse(0);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Project(long id, @JsonProperty("path_with_namespace") String pathWithNamespace, Permissions permissions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Permissions(@JsonProperty("project_access") Access projectAccess, @JsonProperty("group_access") Access groupAccess) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Access(@JsonProperty("access_level") int level) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Approval(GitLabUser user) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Approvals(@JsonProperty("approved_by") List<Approval> approvedBy) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Commit(String id, @JsonProperty("parent_ids") List<String> parentIds, String message,
            @JsonProperty("author_name") String authorName, @JsonProperty("authored_date") String authoredDate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Compare(List<GitLabFileDiff> diffs, List<Commit> commits, @JsonProperty("compare_timeout") boolean compareTimeout) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FileBody(String encoding, String content) {}
}
