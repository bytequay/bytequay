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
import com.bytequay.core.domain.GitHubPullRequestIdentity;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrDiffEtags;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReaction;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequestCheckLog;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingUrls;
import com.bytequay.core.hosting.PullRequestContentReader;
import com.bytequay.integration.gitlab.impl.GitLabMergeRequestResponse.DiffRefs;
import com.bytequay.integration.gitlab.impl.GitLabMergeRequestResponse.Pipeline;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

/** GitLab payloads become the same detail, discussion, check and diff values used by the desktop. */
public final class GitLabPullRequestContentReader
        implements PullRequestContentReader
{
    private final GitLabHttpClient http;
    private final GitLabMergeRequestDiffs mergeRequestDiffs;

    public GitLabPullRequestContentReader(HttpClient http, ObjectMapper mapper)
    {
        this.http = new GitLabHttpClient(http, mapper);
        this.mergeRequestDiffs = new GitLabMergeRequestDiffs(this.http);
    }

    @Override
    public HostingProvider provider()
    {
        return HostingProvider.GITLAB;
    }

    @Override
    public String headRef(int number)
    {
        checkArgument(number > 0, "number must be positive");
        return "refs/merge-requests/" + number + "/head";
    }

    @Override
    public GitHubPullRequestIdentity identity(HostingAccess access, HostingRepository repository, int number)
    {
        GitLabMergeRequestResponse request = get(access, mergeRequestPath(access, repository, number), GitLabMergeRequestResponse.class);
        if (!Long.valueOf(number).equals(request.iid()) || !repository.remoteId().equals(String.valueOf(request.targetProjectId()))) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned another merge request");
        }
        DiffRefs refs = request.diffRefs();
        if (refs == null || request.sha() == null || !request.sha().equals(refs.headSha())) {
            throw new HostingFailure(REVISION_CONFLICT, "GitLab has not prepared the current merge request diff");
        }
        Project source = get(access, "projects/" + request.sourceProjectId(), Project.class);
        RepoRef target = repositoryRef(repository.fullPath());
        return mapped("merge request", () -> {
            String url = HostingUrls.requireWithinBase(URI.create(request.webUrl()), access.server().webBaseUrl()).toASCIIString();
            GitHubPullRequestIdentity.State state = GitHubPullRequestIdentity.State.valueOf(request.requestState().toUpperCase(Locale.ROOT));
            return new GitHubPullRequestIdentity(PullRequestRef.of(target.owner(), target.repo(), number), url, repositoryRef(source.pathWithNamespace()),
                    request.sourceBranch(), request.sha(), target, request.targetBranch(), refs.startSha(), state, request.isDraft(), request.author().username());
        });
    }

    @Override
    public List<GitHubPullRequestIdentity> identitiesByHead(HostingAccess access, HostingRepository repository, RepoRef sourceRepository, String branch)
    {
        Project source = get(access, "projects/" + encode(sourceRepository.fullName()), Project.class);
        String path = projectPath(access, repository) + "/merge_requests?scope=all&state=all&source_branch=" + encode(branch);
        return http.pages(access, path, GitLabMergeRequestResponse[].class).stream()
                .filter(request -> request.sourceProjectId() != null && request.sourceProjectId() == source.id())
                .map(request -> identity(access, repository, mapped("merge request", () -> toIntExact(request.iid())))).collect(toImmutableList());
    }

    private static RepoRef repositoryRef(String path)
    {
        int slash = path.lastIndexOf('/');
        checkArgument(slash > 0 && slash < path.length() - 1, "GitLab returned invalid repository coordinates");
        return RepoRef.of(path.substring(0, slash), path.substring(slash + 1));
    }

    @Override
    public boolean viewerCanWrite(HostingAccess access, HostingRepository repository)
    {
        Project project = get(access, projectPath(access, repository), Project.class);
        return Optional.ofNullable(project.permissions()).map(permissions -> Math.max(level(permissions.projectAccess()), level(permissions.groupAccess())) >= 30).orElse(false);
    }

    @Override
    public StoredPrDetail discussion(HostingAccess access, HostingRepository repository, int number, StoredPrDetail existing)
    {
        String path = mergeRequestPath(access, repository, number);
        List<String> unavailable = new ArrayList<>();
        List<PrReviewState> reviews = optional(StoredPrDetail.REVIEWS, unavailable, () -> get(access, path + "/approvals", Approvals.class).approvedBy().stream()
                .map(approval -> new PrReviewState(null, approval.user().username(), approval.user().avatarUrl(), false,
                        "APPROVED", null, null, existing.raw().htmlUrl(), existing.raw().headSha())).collect(toImmutableList()));
        List<PrTimelineEvent> timeline = new ArrayList<>(optional(StoredPrDetail.TIMELINE, unavailable,
                () -> discussions(access, path, existing.raw(), unavailable)));
        if (unavailable.contains(StoredPrDetail.TIMELINE)) {
            unavailable.add(StoredPrDetail.COMMENTS);
            unavailable.add(StoredPrDetail.REVIEW_THREADS);
        }
        timeline.addAll(optional(StoredPrDetail.COMMITS, unavailable, () -> commits(access, path).stream()
                .map(commit -> new PrTimelineEvent(null, "committed", commit.author(), commit.authoredAt(), commit.message(),
                        PrTimelinePayload.builder().commitSha(commit.sha()).commitMessage(commit.message()).build(), Optional.empty()))
                .collect(toImmutableList())));
        List<PrReaction> reactions = optional(StoredPrDetail.REACTIONS, unavailable, () -> reactions(access, path));
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
                    events.add(new PrTimelineEvent(note.id(), note.system() ? "system_note" : "commented", note.author().username(), instant(note.createdAt()), note.body(),
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
    public Checks checks(HostingAccess access, HostingRepository repository, int number, String headSha,
            List<PrCheckRunState> existing, Optional<String> checksEtag, Optional<String> statusesEtag)
    {
        GitLabMergeRequestResponse request = get(access, mergeRequestPath(access, repository, number), GitLabMergeRequestResponse.class);
        if (!headSha.equals(request.sha())) {
            throw new HostingFailure(REVISION_CONFLICT, "Merge request head changed while reading checks");
        }
        if (request.headPipeline() == null) {
            return new Checks(ImmutableList.of(), Optional.empty(), Optional.empty(), true);
        }
        Pipeline pipeline = request.headPipeline();
        String path = pipeline.path(repository);
        // Pipeline access can differ from merge request access, especially for fork pipelines.
        List<String> unavailable = new ArrayList<>();
        List<PrCheckRunState> checks = new ArrayList<>(optional(StoredPrDetail.CHECKS, unavailable, () -> http.pages(access, path + "/jobs", GitLabJob[].class).stream()
                .map(job -> check(job.id(), job.name(), job.status(), job.webUrl(), "job", job.startedAt(), job.finishedAt(), job.allowFailure())).collect(toImmutableList())));
        checks.addAll(optional(StoredPrDetail.CHECKS, unavailable, () -> http.pages(access, path + "/bridges", GitLabJob[].class).stream()
                .map(job -> check(job.id(), job.name(), job.status(), job.webUrl(), "status", job.startedAt(), job.finishedAt(), job.allowFailure())).collect(toImmutableList())));
        if (checks.isEmpty() || !unavailable.isEmpty()) {
            // A summary has no job identity; null replaces it on the next partial refresh.
            checks.add(mapped("pipeline", () -> check(null, "Pipeline #" + pipeline.id(), pipeline.status(), pipeline.webUrl(), "status", null, null, false)));
        }
        return new Checks(checks, Optional.empty(), Optional.empty(), unavailable.isEmpty());
    }

    private static PrCheckRunState check(Long id, String name, String state, String url, String source, String startedAt, String completedAt, boolean allowFailure)
    {
        String conclusion = switch (state) {
            case "success" -> "success";
            case "failed" -> allowFailure ? "neutral" : "failure";
            case "manual" -> allowFailure ? "skipped" : null;
            case "canceled" -> "cancelled";
            case "skipped" -> "skipped";
            default -> null;
        };
        return new PrCheckRunState(id, name, conclusion != null ? "completed" : "running".equals(state) ? "in_progress" : "queued", conclusion,
                url, source, instant(startedAt), instant(completedAt));
    }

    @Override
    public Diff diff(HostingAccess access, HostingRepository repository, int number, Optional<String> baseSha, Optional<String> headSha,
            StoredPrDiff existing, PrDiffEtags etags)
    {
        String path = mergeRequestPath(access, repository, number);
        List<GitLabFileDiff> files;
        List<DiffCommit> commits;
        if (baseSha.isPresent()) {
            Compare compare = get(access, projectPath(access, repository) + "/repository/compare?from=" + encode(baseSha.orElseThrow()) + "&to=" + encode(headSha.orElseThrow())
                    + "&straight=true", Compare.class);
            if (compare.compareTimeout()) {
                throw new HostingFailure(UNAVAILABLE, "GitLab comparison timed out; the diff is incomplete");
            }
            files = compare.diffs();
            commits = mapped("comparison", () -> compare.commits().stream().map(GitLabPullRequestContentReader::commit).collect(toImmutableList()));
        }
        else {
            files = mergeRequestDiffs.read(access, path);
            commits = commits(access, path);
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
    public PullRequestCheckLog readCheckLog(HostingAccess access, HostingRepository repository, int number, long jobId)
    {
        Pipeline pipeline = get(access, mergeRequestPath(access, repository, number), GitLabMergeRequestResponse.class).requireHeadPipeline();
        boolean failed = http.pages(access, pipeline.path(repository) + "/jobs", GitLabJob[].class).stream()
                .anyMatch(job -> job.id() == jobId && "failed".equals(job.status()));
        if (!failed) {
            throw new HostingFailure(REVISION_CONFLICT, "This failed job is no longer part of the current request checks");
        }
        try {
            return new PullRequestCheckLog(http.getText(access, pipeline.projectPath(repository) + "/jobs/" + jobId + "/trace"), Optional.empty());
        }
        catch (HostingFailure failure) {
            if (failure.kind() == MISSING_OR_INACCESSIBLE) {
                return new PullRequestCheckLog("", Optional.of(failure.getMessage()));
            }
            throw failure;
        }
    }

    @Override
    public DiffFileContent fileContent(HostingAccess access, HostingRepository repository, int number, String path, String baseSha, String headSha, boolean fullDiff)
    {
        String target = projectPath(access, repository);
        GitLabMergeRequestResponse request = get(access, mergeRequestPath(access, repository, number), GitLabMergeRequestResponse.class);
        String source = request.sourceProjectId() == null ? target : "projects/" + request.sourceProjectId();
        String contentBase = baseSha;
        if (fullDiff) {
            DiffRefs refs = request.diffRefs();
            if (refs == null || refs.baseSha() == null || !headSha.equals(request.sha()) || !headSha.equals(refs.headSha()) || !baseSha.equals(refs.startSha())) {
                throw new HostingFailure(REVISION_CONFLICT, "The merge request diff changed; refresh before opening file content");
            }
            contentBase = refs.baseSha();
        }
        Compare compare = get(access, target + "/repository/compare?from=" + encode(contentBase) + "&to=" + encode(headSha) + "&straight=true", Compare.class);
        if (compare.compareTimeout()) {
            throw new HostingFailure(UNAVAILABLE, "GitLab comparison timed out; the diff is incomplete");
        }
        GitLabFileDiff diff = mapped("comparison", () -> compare.diffs().stream().filter(file -> path.equals(file.newPath())).findFirst().orElse(null));
        String oldPath = Optional.ofNullable(diff).map(GitLabFileDiff::oldPath).orElse(path);
        return new DiffFileContent(diff != null && diff.newFile() ? null : file(access, target, oldPath, contentBase),
                diff != null && diff.deletedFile() ? null : file(access, source, path, headSha));
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
