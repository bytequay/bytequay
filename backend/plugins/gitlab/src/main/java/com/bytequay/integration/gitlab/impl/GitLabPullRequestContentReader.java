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
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.MISSING_OR_INACCESSIBLE;
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.core.hosting.HostingFailure.Kind.UNAVAILABLE;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.encode;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.Math.toIntExact;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

/** GitLab payloads become the same detail, discussion, check and diff values used by the desktop. */
public final class GitLabPullRequestContentReader
        implements PullRequestContentReader
{
    private final GitLabHttpClient http;

    public GitLabPullRequestContentReader(HttpClient http, ObjectMapper mapper)
    {
        this.http = new GitLabHttpClient(http, mapper);
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
        MergeRequest request = get(access, request(access, repository, number), MergeRequest.class);
        checkArgument(request.iid() == number && repository.remoteId().equals(String.valueOf(request.targetProjectId())), "GitLab returned another merge request");
        Project source = get(access, "projects/" + request.sourceProjectId(), Project.class);
        RepoRef target = repositoryRef(repository.fullPath());
        DiffRefs refs = request.diffRefs();
        checkArgument(refs != null && request.sha().equals(refs.headSha()), "GitLab has not prepared the current merge request diff");
        String url = HostingUrls.requireWithinBase(URI.create(request.webUrl()), access.server().webBaseUrl()).toASCIIString();
        GitHubPullRequestIdentity.State state = switch (request.state()) {
            case "opened", "locked" -> GitHubPullRequestIdentity.State.OPEN;
            case "closed" -> GitHubPullRequestIdentity.State.CLOSED;
            case "merged" -> GitHubPullRequestIdentity.State.MERGED;
            default -> throw new HostingFailure(INVALID_RESPONSE, "GitLab returned an invalid merge request state");
        };
        return new GitHubPullRequestIdentity(PullRequestRef.of(target.owner(), target.repo(), number), url, repositoryRef(source.pathWithNamespace()),
                request.sourceBranch(), request.sha(), target, request.targetBranch(), refs.startSha(), state, request.draft(), request.author().username());
    }

    @Override
    public List<GitHubPullRequestIdentity> identitiesByHead(HostingAccess access, HostingRepository repository, RepoRef sourceRepository, String branch)
    {
        Project source = get(access, "projects/" + encode(sourceRepository.fullName()), Project.class);
        return pages(access, project(access, repository) + "/merge_requests?scope=all&state=all&source_branch=" + encode(branch), MergeRequest[].class).stream()
                .filter(request -> request.sourceProjectId() != null && request.sourceProjectId() == source.id())
                .map(request -> identity(access, repository, request.iid())).collect(toImmutableList());
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
        Project project = get(access, project(access, repository), Project.class);
        return Optional.ofNullable(project.permissions()).map(permissions -> Math.max(level(permissions.projectAccess()), level(permissions.groupAccess())) >= 30).orElse(false);
    }

    @Override
    public StoredPrDetail discussion(HostingAccess access, HostingRepository repository, int number, StoredPrDetail existing)
    {
        String path = request(access, repository, number);
        List<String> unavailable = new ArrayList<>();
        List<PrReviewState> reviews = optional(StoredPrDetail.REVIEWS, unavailable, () -> get(access, path + "/approvals", Approvals.class).approvedBy().stream()
                .map(approval -> new PrReviewState(null, approval.user().username(), approval.user().avatarUrl(), false,
                        "APPROVED", null, null, existing.raw().htmlUrl(), existing.raw().headSha())).collect(toImmutableList()));
        List<PrTimelineEvent> timeline = new ArrayList<>(optional(StoredPrDetail.TIMELINE, unavailable,
                () -> discussions(access, path, existing.raw().htmlUrl(), unavailable)));
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

    private List<PrTimelineEvent> discussions(HostingAccess access, String path, String url, List<String> unavailable)
    {
        List<PrTimelineEvent> events = new ArrayList<>();
        for (Discussion discussion : pages(access, path + "/discussions", Discussion[].class)) {
            if (discussion.notes().isEmpty()) {
                continue;
            }
            Note root = discussion.notes().getFirst();
            if (root.position() == null) {
                for (Note note : discussion.notes()) {
                    List<PrReaction> reactions = note.system() ? ImmutableList.of()
                            : optional(StoredPrDetail.REACTIONS, unavailable, () -> reactions(access, path + "/notes/" + note.id()));
                    events.add(new PrTimelineEvent(note.id(), note.system() ? "system_note" : "commented", note.author().username(), instant(note.createdAt()), note.body(),
                            PrTimelinePayload.builder().actorAvatarUrl(note.author().avatarUrl()).htmlUrl(url + "#note_" + note.id())
                                    .reactions(reactions).build(), Optional.empty()));
                }
                continue;
            }
            Position position = root.position();
            List<PrTimelineComment> comments = discussion.notes().stream().map(note -> new PrTimelineComment(note.id(), note.author().username(), note.author().avatarUrl(),
                    false, instant(note.createdAt()), note.body(), url + "#note_" + note.id(), null, note == root ? null : root.id(),
                    optional(StoredPrDetail.REACTIONS, unavailable, () -> reactions(access, path + "/notes/" + note.id())))).collect(toImmutableList());
            events.add(new PrTimelineEvent(root.id(), "review_thread", root.author().username(), instant(root.createdAt()), root.body(),
                    PrTimelinePayload.builder().actorAvatarUrl(root.author().avatarUrl()).htmlUrl(url + "#note_" + root.id())
                            .path(position.newLine() == null ? position.oldPath() : position.newPath()).line(position.newLine() == null ? position.oldLine() : position.newLine())
                            .side(position.newLine() == null ? "LEFT" : "RIGHT").resolved(root.resolved()).comments(comments).build(), Optional.empty()));
        }
        return ImmutableList.copyOf(events);
    }

    @Override
    public Checks checks(HostingAccess access, HostingRepository repository, int number, String headSha,
            List<PrCheckRunState> existing, Optional<String> checksEtag, Optional<String> statusesEtag)
    {
        MergeRequest request = get(access, request(access, repository, number), MergeRequest.class);
        checkArgument(headSha.equals(request.sha()), "Merge request head changed while reading checks");
        if (request.headPipeline() == null) {
            return new Checks(ImmutableList.of(), Optional.empty(), Optional.empty());
        }
        Pipeline pipeline = request.headPipeline();
        String path = "projects/" + (pipeline.projectId() == null ? repository.remoteId() : pipeline.projectId()) + "/pipelines/" + pipeline.id();
        List<PrCheckRunState> checks = new ArrayList<>(pages(access, path + "/jobs", Job[].class).stream()
                .map(job -> check(job.id(), job.name(), job.status(), job.webUrl(), "job", job.startedAt(), job.finishedAt())).collect(toImmutableList()));
        checks.addAll(pages(access, path + "/bridges", Job[].class).stream()
                .map(job -> check(job.id(), job.name(), job.status(), job.webUrl(), "status", job.startedAt(), job.finishedAt())).collect(toImmutableList()));
        if (checks.isEmpty()) {
            checks = ImmutableList.of(check(pipeline.id(), "Pipeline #" + pipeline.id(), pipeline.status(), pipeline.webUrl(), "status", null, null));
        }
        return new Checks(checks, Optional.empty(), Optional.empty());
    }

    private static PrCheckRunState check(long id, String name, String state, String url, String source, String startedAt, String completedAt)
    {
        String conclusion = switch (state) {
            case "success" -> "success";
            case "failed" -> "failure";
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
        String path = request(access, repository, number);
        List<FileDiff> files;
        List<DiffCommit> commits;
        if (baseSha.isPresent()) {
            Compare compare = get(access, project(access, repository) + "/repository/compare?from=" + encode(baseSha.orElseThrow()) + "&to=" + encode(headSha.orElseThrow())
                    + "&straight=true", Compare.class);
            if (compare.compareTimeout()) {
                throw new HostingFailure(UNAVAILABLE, "GitLab comparison timed out; the diff is incomplete");
            }
            files = compare.diffs();
            commits = compare.commits().stream().map(GitLabPullRequestContentReader::commit).collect(toImmutableList());
        }
        else {
            files = pages(access, path + "/diffs", FileDiff[].class);
            commits = commits(access, path);
        }
        return new Diff(files.stream().map(file -> {
            String patch = file.collapsed() || file.tooLarge() ? null : file.diff();
            int additions = patch == null ? 0 : (int) patch.lines().filter(line -> line.startsWith("+") && !line.startsWith("+++")).count();
            int deletions = patch == null ? 0 : (int) patch.lines().filter(line -> line.startsWith("-") && !line.startsWith("---")).count();
            return new DiffFile(file.newPath(), file.newFile() ? "added" : file.deletedFile() ? "removed" : file.renamedFile() ? "renamed" : "modified",
                    additions, deletions, patch);
        }).collect(toImmutableList()), commits, PrDiffEtags.none());
    }

    @Override
    public PullRequestCheckLog readCheckLog(HostingAccess access, HostingRepository repository, int number, long jobId)
    {
        MergeRequest current = get(access, request(access, repository, number), MergeRequest.class);
        if (current.headPipeline() == null) {
            throw new HostingFailure(REVISION_CONFLICT, "This request has no current pipeline");
        }
        Pipeline pipeline = current.headPipeline();
        String project = "projects/" + (pipeline.projectId() == null ? repository.remoteId() : pipeline.projectId());
        boolean failed = pages(access, project + "/pipelines/" + pipeline.id() + "/jobs", Job[].class).stream()
                .anyMatch(job -> job.id() == jobId && "failed".equals(job.status()));
        if (!failed) {
            throw new HostingFailure(REVISION_CONFLICT, "This failed job is no longer part of the current request checks");
        }
        try {
            return new PullRequestCheckLog(http.getText(access, project + "/jobs/" + jobId + "/trace"), Optional.empty());
        }
        catch (HostingFailure failure) {
            if (failure.kind() == MISSING_OR_INACCESSIBLE) {
                return new PullRequestCheckLog("", Optional.of("GitLab job log is unavailable"));
            }
            throw failure;
        }
    }

    @Override
    public DiffFileContent fileContent(HostingAccess access, HostingRepository repository, int number, String path, String baseSha, String headSha)
    {
        String target = project(access, repository);
        MergeRequest request = get(access, request(access, repository, number), MergeRequest.class);
        String source = request.sourceProjectId() == null ? target : "projects/" + request.sourceProjectId();
        Compare compare = get(access, target + "/repository/compare?from=" + encode(baseSha) + "&to=" + encode(headSha) + "&straight=true", Compare.class);
        FileDiff diff = compare.diffs().stream().filter(file -> file.newPath().equals(path)).findFirst().orElse(null);
        String oldPath = Optional.ofNullable(diff).map(FileDiff::oldPath).orElse(path);
        return new DiffFileContent(diff != null && diff.newFile() ? null : file(access, target, oldPath, baseSha),
                diff != null && diff.deletedFile() ? null : file(access, source, path, headSha));
    }

    private String file(HostingAccess access, String project, String path, String sha)
    {
        try {
            FileBody body = get(access, project + "/repository/files/" + encode(path) + "?ref=" + encode(sha), FileBody.class);
            if (!"base64".equals(body.encoding())) {
                throw new HostingFailure(INVALID_RESPONSE, "GitLab returned unsupported file encoding");
            }
            return new String(Base64.getMimeDecoder().decode(body.content()), StandardCharsets.UTF_8);
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
        return pages(access, path + "/commits", Commit[].class).stream().map(GitLabPullRequestContentReader::commit).collect(toImmutableList());
    }

    private static DiffCommit commit(Commit commit)
    {
        return new DiffCommit(commit.id(), commit.parentIds().stream().findFirst().orElse(null), commit.message(), commit.authorName(), instant(commit.authoredDate()));
    }

    private List<PrReaction> reactions(HostingAccess access, String path)
    {
        Map<String, Long> counts = pages(access, path + "/award_emoji", Award[].class).stream().collect(groupingBy(Award::name, counting()));
        return counts.entrySet().stream().map(entry -> new PrReaction(switch (entry.getKey()) {
            case "thumbsup" -> "+1";
            case "thumbsdown" -> "-1";
            case "tada" -> "hooray";
            default -> entry.getKey();
        }, toIntExact(entry.getValue()), null)).collect(toImmutableList());
    }

    private static <T> List<T> optional(String section, List<String> unavailable, Supplier<List<T>> supplier)
    {
        try {
            return supplier.get();
        }
        catch (HostingFailure failure) {
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

    private <T> List<T> pages(HostingAccess access, String path, Class<T[]> type)
    {
        ImmutableList.Builder<T> items = ImmutableList.builder();
        for (int page = 1; ; page++) {
            GitLabHttpClient.Response<T[]> response = http.get(access, path + (path.contains("?") ? "&" : "?") + "per_page=100&page=" + page, Optional.empty(), type);
            items.addAll(Arrays.asList(response.body().orElseThrow()));
            if (!response.hasNext()) {
                return items.build();
            }
        }
    }

    private static String request(HostingAccess access, HostingRepository repository, int number)
    {
        checkArgument(number > 0, "number must be positive");
        return project(access, repository) + "/merge_requests/" + number;
    }

    private static String project(HostingAccess access, HostingRepository repository)
    {
        checkArgument(repository.instanceId() == access.instanceId().orElseThrow(), "Repository belongs to another hosting instance");
        return "projects/" + encode(repository.remoteId());
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
    private record User(long id, String username, @JsonProperty("avatar_url") String avatarUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Approval(User user) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Approvals(@JsonProperty("approved_by") List<Approval> approvedBy) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Discussion(String id, List<Note> notes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Note(long id, String body, User author, @JsonProperty("created_at") String createdAt, boolean system, boolean resolved, Position position) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Position(@JsonProperty("new_path") String newPath, @JsonProperty("old_path") String oldPath,
            @JsonProperty("new_line") Integer newLine, @JsonProperty("old_line") Integer oldLine) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Commit(String id, @JsonProperty("parent_ids") List<String> parentIds, String message,
            @JsonProperty("author_name") String authorName, @JsonProperty("authored_date") String authoredDate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Award(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Pipeline(long id, @JsonProperty("project_id") Long projectId, String status, @JsonProperty("web_url") String webUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MergeRequest(int iid, String sha, String state, boolean draft, User author, @JsonProperty("web_url") String webUrl,
            @JsonProperty("source_project_id") Long sourceProjectId, @JsonProperty("target_project_id") Long targetProjectId,
            @JsonProperty("source_branch") String sourceBranch, @JsonProperty("target_branch") String targetBranch,
            @JsonProperty("diff_refs") DiffRefs diffRefs, @JsonProperty("head_pipeline") Pipeline headPipeline) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiffRefs(@JsonProperty("head_sha") String headSha, @JsonProperty("start_sha") String startSha) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Job(long id, String name, String status, @JsonProperty("web_url") String webUrl,
            @JsonProperty("started_at") String startedAt, @JsonProperty("finished_at") String finishedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FileDiff(@JsonProperty("old_path") String oldPath, @JsonProperty("new_path") String newPath, String diff,
            @JsonProperty("new_file") boolean newFile, @JsonProperty("renamed_file") boolean renamedFile, @JsonProperty("deleted_file") boolean deletedFile,
            boolean collapsed, @JsonProperty("too_large") boolean tooLarge) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Compare(List<FileDiff> diffs, List<Commit> commits, @JsonProperty("compare_timeout") boolean compareTimeout) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FileBody(String encoding, String content) {}
}
