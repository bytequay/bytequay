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

import com.bytequay.core.domain.CreatedPullRequest;
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingUrls;
import com.bytequay.core.hosting.PullRequestWriter;
import com.bytequay.core.hosting.SubmittedReview;
import com.bytequay.integration.gitlab.impl.GitLabMergeRequestResponse.DiffRefs;
import com.bytequay.integration.gitlab.impl.GitLabMergeRequestResponse.Pipeline;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.encode;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.mergeRequestPath;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.projectPath;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.Math.toIntExact;

/** GitLab write semantics stay behind the existing desktop actions. */
public final class GitLabPullRequestWriter
        implements PullRequestWriter
{
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? [+](\\d+)(?:,\\d+)? @@.*");
    private final GitLabHttpClient http;
    private final GitLabMergeRequestDiffs diffs;

    public GitLabPullRequestWriter(HttpClient http, ObjectMapper mapper)
    {
        this.http = new GitLabHttpClient(http, mapper);
        this.diffs = new GitLabMergeRequestDiffs(this.http);
    }

    @Override
    public HostingProvider provider()
    {
        return HostingProvider.GITLAB;
    }

    @Override
    public PrTimelineEvent comment(HostingAccess access, HostingRepository repository, int number, String body)
    {
        GitLabNote note = http.post(access, mergeRequestPath(access, repository, number) + "/notes", new Body(body), GitLabNote.class).body().orElseThrow();
        return note(note, url(repository, number));
    }

    @Override
    public PrTimelineEvent createReviewComment(HostingAccess access, HostingRepository repository, int number, InlineReviewComment comment)
    {
        validateComment(comment);
        String path = mergeRequestPath(access, repository, number);
        GitLabMergeRequestResponse current = get(access, path, GitLabMergeRequestResponse.class);
        if (comment.replyToCommentId().isPresent()) {
            GitLabDiscussion discussion = discussion(access, repository, number, comment.replyToCommentId().orElseThrow());
            GitLabNote reply = http.post(access, path + "/discussions/" + encode(discussion.id()) + "/notes", new Body(comment.body()),
                    GitLabNote.class).body().orElseThrow();
            if (reply.id() == null && reply.summary() != null) {
                return commandResult(reply.summary());
            }
            List<GitLabNote> notes = ImmutableList.<GitLabNote>builder().addAll(discussion.notes()).add(reply).build();
            return thread(new GitLabDiscussion(discussion.id(), discussion.individualNote(), notes, null), url(repository, number), current);
        }
        checkHead(current, comment.commitId());
        GitLabDiffPosition position = position(diffs.read(access, path), current, comment);
        checkRevision(current, get(access, path, GitLabMergeRequestResponse.class));
        return createDiscussion(access, repository, number, comment.body(), position, current);
    }

    @Override
    public void replyToReviewComment(HostingAccess access, HostingRepository repository, int number, long commentId, String body)
    {
        GitLabDiscussion discussion = discussion(access, repository, number, commentId);
        http.post(access, mergeRequestPath(access, repository, number) + "/discussions/" + encode(discussion.id()) + "/notes", new Body(body), Void.class);
    }

    @Override
    public void editComment(HostingAccess access, HostingRepository repository, int number, String target, long commentId, String body)
    {
        checkArgument("comment".equals(target) || "review_comment".equals(target) || "issue_comment".equals(target), "GitLab can edit note comments only");
        http.put(access, mergeRequestPath(access, repository, number) + "/notes/" + commentId, new Body(body), Void.class);
    }

    @Override
    public boolean toggleReaction(HostingAccess access, HostingRepository repository, int number, String target, Long commentId, String content, Boolean viewerHasReacted)
    {
        String path = mergeRequestPath(access, repository, number) + (commentId == null ? "" : "/notes/" + commentId) + "/award_emoji";
        GitLabUser viewer = get(access, "user", GitLabUser.class);
        String emoji = switch (content) {
            case "+1" -> "thumbsup";
            case "-1" -> "thumbsdown";
            case "hooray" -> "tada";
            case "laugh" -> "smile";
            default -> content;
        };
        Optional<GitLabAward> existing = http.pages(access, path, GitLabAward[].class).stream()
                .filter(award -> award.name().equals(emoji) && viewer.id().equals(award.user().id())).findFirst();
        if (existing.isPresent()) {
            http.delete(access, path + "/" + existing.orElseThrow().id(), Void.class);
            return false;
        }
        http.post(access, path, new Emoji(emoji), Void.class);
        return true;
    }

    @Override
    public void updateTitle(HostingAccess access, HostingRepository repository, int number, String title)
    {
        http.put(access, mergeRequestPath(access, repository, number), new Title(title), Void.class);
    }

    @Override
    public void updateDescription(HostingAccess access, HostingRepository repository, int number, String description)
    {
        http.put(access, mergeRequestPath(access, repository, number), new Description(description), Void.class);
    }

    @Override
    public void close(HostingAccess access, HostingRepository repository, int number)
    {
        http.put(access, mergeRequestPath(access, repository, number), new State("close"), Void.class);
    }

    @Override
    public void markReady(HostingAccess access, HostingRepository repository, int number)
    {
        String path = mergeRequestPath(access, repository, number);
        GitLabMergeRequestResponse current = get(access, path, GitLabMergeRequestResponse.class);
        http.put(access, path, new Title(current.title().replaceFirst("(?i)^(?:draft|wip):\\s*|^\\[(?:draft|wip)\\]\\s*|^\\((?:draft|wip)\\)\\s*", "")), Void.class);
    }

    @Override
    public void updateMetadata(HostingAccess access, HostingRepository repository, int number, List<String> assignees, List<String> labels)
    {
        List<Long> ids = assignees.stream().map(login -> user(access, login).id()).collect(toImmutableList());
        http.put(access, mergeRequestPath(access, repository, number), new Metadata(ids, String.join(",", labels)), Void.class);
    }

    @Override
    public void updateReviewer(HostingAccess access, HostingRepository repository, int number, String login, boolean requested)
    {
        String path = mergeRequestPath(access, repository, number);
        GitLabMergeRequestResponse current = get(access, path, GitLabMergeRequestResponse.class);
        long reviewer = user(access, login).id();
        List<Long> ids = new ArrayList<>(current.reviewers().stream().map(GitLabUser::id).filter(id -> id != reviewer).collect(toImmutableList()));
        if (requested) {
            ids.add(reviewer);
        }
        http.put(access, path, new Reviewers(ids), Void.class);
    }

    @Override
    public void resolveThread(HostingAccess access, HostingRepository repository, int number, long commentId, boolean resolved)
    {
        GitLabDiscussion discussion = discussion(access, repository, number, commentId);
        http.put(access, mergeRequestPath(access, repository, number) + "/discussions/" + encode(discussion.id()), new Resolved(resolved), Void.class);
    }

    @Override
    public SubmittedReview submitReview(HostingAccess access, HostingRepository repository, int number, String event, String body,
            List<InlineReviewComment> comments, String commitId)
    {
        checkArgument("APPROVE".equals(event) || "COMMENT".equals(event), "GitLab request-changes reviews are not supported by this connection");
        comments.forEach(GitLabPullRequestWriter::validateComment);
        String path = mergeRequestPath(access, repository, number);
        GitLabMergeRequestResponse current = get(access, path, GitLabMergeRequestResponse.class);
        checkHead(current, commitId);
        Optional<GitLabUser> viewer = "APPROVE".equals(event) ? Optional.of(get(access, "user", GitLabUser.class)) : Optional.empty();
        List<GitLabFileDiff> files = comments.stream().anyMatch(comment -> comment.replyToCommentId().isEmpty())
                ? diffs.read(access, path) : ImmutableList.of();
        List<PrTimelineEvent> events = new ArrayList<>();
        int published = 0;
        try {
            for (InlineReviewComment comment : comments) {
                if (comment.replyToCommentId().isPresent()) {
                    events.add(createReviewComment(access, repository, number, comment));
                }
                else {
                    GitLabDiffPosition position = position(files, current, comment);
                    checkRevision(current, get(access, path, GitLabMergeRequestResponse.class));
                    events.add(createDiscussion(access, repository, number, comment.body(), position, current));
                }
                published++;
            }
            if (body != null && !body.isBlank()) {
                events.add(comment(access, repository, number, body));
                published++;
            }
            if ("APPROVE".equals(event)) {
                http.post(access, path + "/approve", new Sha(commitId), Void.class);
            }
        }
        catch (RuntimeException failure) {
            if (published > 0) {
                throw new HostingFailure(INVALID_RESPONSE, "GitLab published " + published + " comment(s), but the review did not finish. Refresh before retrying.");
            }
            throw failure;
        }
        // GitLab exposes an approval per user, without a review submission ID or timestamp.
        return new SubmittedReview(viewer.map(user -> new PrReviewState(null, user.username(), user.avatarUrl(), false,
                "APPROVED", null, null, current.webUrl(), current.sha())), events);
    }

    @Override
    public MergeResult merge(HostingAccess access, HostingRepository repository, int number, String strategy, String headSha)
    {
        checkArgument("default".equals(strategy) || "merge".equals(strategy) || "squash".equals(strategy),
                "GitLab's merge method is configured by the project; rebase merge is not a per-request choice");
        Object command = "default".equals(strategy) ? new Sha(headSha) : new Merge(headSha, "squash".equals(strategy));
        var result = http.put(access, mergeRequestPath(access, repository, number) + "/merge", command, GitLabMergeRequestResponse.class).body().orElseThrow();
        return new MergeResult(result.mergeCommitSha(), "merged".equals(result.state()), "merged".equals(result.state()) ? "Merged" : "GitLab has not merged this request");
    }

    @Override
    public void rerunFailedChecks(HostingAccess access, HostingRepository repository, int number)
    {
        GitLabMergeRequestResponse current = get(access, mergeRequestPath(access, repository, number), GitLabMergeRequestResponse.class);
        if (current.headPipeline() == null) {
            throw new HostingFailure(REVISION_CONFLICT, "This request has no current pipeline");
        }
        Pipeline pipeline = current.headPipeline();
        String project = "projects/" + (pipeline.projectId() == null ? repository.remoteId() : pipeline.projectId());
        List<GitLabJob> failed = http.pages(access, project + "/pipelines/" + pipeline.id() + "/jobs", GitLabJob[].class).stream()
                .filter(job -> "failed".equals(job.status())).collect(toImmutableList());
        if (failed.isEmpty()) {
            throw new HostingFailure(REVISION_CONFLICT, "No failed jobs are available to rerun");
        }
        int accepted = 0;
        try {
            for (GitLabJob job : failed) {
                http.post(access, project + "/jobs/" + job.id() + "/retry", ImmutableMap.of(), Void.class);
                accepted++;
            }
        }
        catch (RuntimeException failure) {
            if (accepted > 0) {
                throw new HostingFailure(INVALID_RESPONSE, "GitLab accepted " + accepted + " job retry(s), but remaining retries failed. Refresh before retrying.");
            }
            throw failure;
        }
    }

    @Override
    public void removeFromMergeQueue(HostingAccess access, HostingRepository repository, int number, boolean autoMerge)
    {
        checkArgument(autoMerge, "GitLab merge trains are not supported by this connection");
        http.post(access, mergeRequestPath(access, repository, number) + "/cancel_merge_when_pipeline_succeeds", ImmutableMap.of(), Void.class);
    }

    @Override
    public boolean hasMergeQueue(HostingAccess access, HostingRepository repository, String baseRef)
    {
        projectPath(access, repository);
        return false;
    }

    @Override
    public CreatedPullRequest create(HostingAccess access, HostingRepository repository, String head, String base, String title, String body, boolean draft)
    {
        checkArgument(!head.contains(":"), "Select the GitLab source project before creating a merge request from a fork");
        GitLabMergeRequestResponse created = http.post(access, projectPath(access, repository) + "/merge_requests", new Create(head, base, draft ? "Draft: " + title : title, body),
                GitLabMergeRequestResponse.class).body().orElseThrow();
        String url = HostingUrls.requireWithinBase(URI.create(created.webUrl()), access.server().webBaseUrl()).toASCIIString();
        return new CreatedPullRequest(created.id(), toIntExact(created.iid()), url);
    }

    private PrTimelineEvent createDiscussion(HostingAccess access, HostingRepository repository, int number, String body, GitLabDiffPosition position,
            GitLabMergeRequestResponse current)
    {
        GitLabDiscussion created = http.post(access, mergeRequestPath(access, repository, number) + "/discussions", new DiscussionBody(body, position), GitLabDiscussion.class)
                .body().orElseThrow();
        if (created.id() == null && created.summary() != null) {
            return commandResult(created.summary());
        }
        return thread(created, url(repository, number), current);
    }

    private static GitLabDiffPosition position(List<GitLabFileDiff> files, GitLabMergeRequestResponse current, InlineReviewComment comment)
    {
        if (current.diffRefs() == null || !current.sha().equals(current.diffRefs().headSha())) {
            throw new HostingFailure(REVISION_CONFLICT, "GitLab has not prepared the current merge request diff");
        }
        GitLabFileDiff file = files.stream().filter(diff -> diff.newPath().equals(comment.path()) || diff.oldPath().equals(comment.path()))
                .findFirst().orElseThrow(() -> new HostingFailure(REVISION_CONFLICT, "The file is not present in the current merge request diff"));
        int oldLine = 0;
        int newLine = 0;
        for (String line : Optional.ofNullable(file.diff()).orElse("").split("\\n")) {
            Matcher hunk = HUNK.matcher(line);
            if (hunk.matches()) {
                oldLine = Integer.parseInt(hunk.group(1));
                newLine = Integer.parseInt(hunk.group(2));
            }
            else if (!line.isEmpty() && " +-".indexOf(line.charAt(0)) >= 0) {
                boolean oldPresent = line.charAt(0) != '+';
                boolean newPresent = line.charAt(0) != '-';
                if (("LEFT".equals(comment.side()) && oldPresent && oldLine == comment.line())
                        || ("RIGHT".equals(comment.side()) && newPresent && newLine == comment.line())) {
                    DiffRefs refs = current.diffRefs();
                    return new GitLabDiffPosition("text", refs.baseSha(), refs.startSha(), refs.headSha(), file.oldPath(), file.newPath(),
                            oldPresent ? oldLine : null, newPresent ? newLine : null);
                }
                oldLine += oldPresent ? 1 : 0;
                newLine += newPresent ? 1 : 0;
            }
        }
        throw new HostingFailure(REVISION_CONFLICT, "The selected line is not present in the current merge request diff");
    }

    private GitLabDiscussion discussion(HostingAccess access, HostingRepository repository, int number, long commentId)
    {
        return http.pages(access, mergeRequestPath(access, repository, number) + "/discussions", GitLabDiscussion[].class).stream()
                .filter(discussion -> discussion.notes().stream().anyMatch(note -> note.id() == commentId)).findFirst()
                .orElseThrow(() -> new HostingFailure(REVISION_CONFLICT, "The selected discussion no longer exists"));
    }

    private GitLabUser user(HostingAccess access, String login)
    {
        return Arrays.stream(get(access, "users?username=" + encode(login), GitLabUser[].class)).filter(user -> user.username().equalsIgnoreCase(login)).findFirst()
                .orElseThrow(() -> new HostingFailure(INVALID_RESPONSE, "GitLab user was not found"));
    }

    private <T> T get(HostingAccess access, String path, Class<T> type)
    {
        return http.get(access, path, Optional.empty(), type).body().orElseThrow();
    }

    private static void checkHead(GitLabMergeRequestResponse current, String sha)
    {
        if (sha != null && !sha.equals(current.sha())) {
            throw new HostingFailure(REVISION_CONFLICT, "The merge request head changed; refresh before submitting");
        }
    }

    private static void checkRevision(GitLabMergeRequestResponse expected, GitLabMergeRequestResponse current)
    {
        checkHead(current, expected.sha());
        if (!expected.diffRefs().equals(current.diffRefs())) {
            throw new HostingFailure(REVISION_CONFLICT, "The merge request diff changed; refresh before submitting");
        }
    }

    private static void validateComment(InlineReviewComment comment)
    {
        if (comment.replyToCommentId().isPresent()) {
            return;
        }
        checkArgument(comment.startLine() == null || comment.startLine() == comment.line(), "GitLab multi-line review comments are not supported by this connection");
        checkArgument("LEFT".equals(comment.side()) || "RIGHT".equals(comment.side()), "Invalid review comment side");
    }

    private static PrTimelineEvent note(GitLabNote note, String url)
    {
        if (note.id() == null && note.summary() != null) {
            return commandResult(note.summary());
        }
        return new PrTimelineEvent(note.id(), "commented", note.author().username(), Instant.parse(note.createdAt()), note.body(),
                PrTimelinePayload.builder().actorAvatarUrl(note.author().avatarUrl()).htmlUrl(url + "#note_" + note.id()).build(), Optional.empty());
    }

    private static PrTimelineEvent commandResult(List<String> summary)
    {
        return new PrTimelineEvent(null, "system_note", null, null, String.join("\n", summary), PrTimelinePayload.empty(), Optional.empty());
    }

    private static PrTimelineEvent thread(GitLabDiscussion discussion, String url, GitLabMergeRequestResponse current)
    {
        GitLabNote root = discussion.notes().getFirst();
        GitLabDiffPosition position = root.position();
        List<PrTimelineComment> comments = discussion.notes().stream().map(note -> new PrTimelineComment(note.id(), note.author().username(), note.author().avatarUrl(), false,
                Instant.parse(note.createdAt()), note.body(), url + "#note_" + note.id(), null, note == root ? null : root.id(), ImmutableList.of())).collect(toImmutableList());
        PrTimelinePayload.Builder payload = PrTimelinePayload.builder().comments(comments).resolved(root.resolved()).htmlUrl(url + "#note_" + root.id());
        if (position != null) {
            position.applyTo(payload, current.sha(), current.diffRefs() == null ? null : current.diffRefs().startSha());
        }
        return new PrTimelineEvent(root.id(), "review_thread", root.author().username(), Instant.parse(root.createdAt()), root.body(), payload.build(), Optional.empty());
    }

    private static String url(HostingRepository repository, int number)
    {
        return repository.webUrl() + "/-/merge_requests/" + number;
    }

    private record Body(String body) {}
    private record Emoji(String name) {}
    private record Title(String title) {}
    private record Description(String description) {}
    private record State(@JsonProperty("state_event") String state) {}
    private record Metadata(@JsonProperty("assignee_ids") List<Long> assignees, String labels) {}
    private record Reviewers(@JsonProperty("reviewer_ids") List<Long> reviewers) {}
    private record Resolved(boolean resolved) {}
    private record Sha(String sha) {}
    private record Merge(String sha, boolean squash) {}
    private record Create(@JsonProperty("source_branch") String source, @JsonProperty("target_branch") String target, String title, String description) {}
    private record DiscussionBody(String body, GitLabDiffPosition position) {}
}
