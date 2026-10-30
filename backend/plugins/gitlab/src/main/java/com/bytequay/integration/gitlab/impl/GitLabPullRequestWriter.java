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
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingTarget;
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
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.encode;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.mapped;
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
    public PrTimelineEvent comment(HostingTarget target, int number, String body)
    {
        GitLabNote note = http.post(target.access(), mergeRequestPath(target, number) + "/notes", new Body(body), GitLabNote.class).body().orElseThrow();
        return mapped("note", () -> note(note, url(target.repository(), number)));
    }

    @Override
    public PrTimelineEvent createReviewComment(HostingTarget target, int number, InlineReviewComment comment)
    {
        validateComment(comment);
        String path = mergeRequestPath(target, number);
        GitLabMergeRequestResponse current = get(target.access(), path, GitLabMergeRequestResponse.class);
        if (comment.replyToCommentId().isPresent()) {
            GitLabDiscussion discussion = discussion(target, number, comment.replyToCommentId().orElseThrow());
            GitLabNote reply = http.post(target.access(), path + "/discussions/" + encode(discussion.id()) + "/notes", new Body(comment.body()),
                    GitLabNote.class).body().orElseThrow();
            if (reply.id() == null && reply.summary() != null) {
                return commandResult(reply.summary());
            }
            List<GitLabNote> notes = ImmutableList.<GitLabNote>builder().addAll(discussion.notes()).add(reply).build();
            return mapped("discussion", () -> thread(new GitLabDiscussion(discussion.id(), discussion.individualNote(), notes, null), url(target.repository(), number), current));
        }
        checkHead(current, comment.commitId());
        GitLabDiffPosition position = position(diffs.read(target.access(), path), current, comment);
        checkRevision(current, get(target.access(), path, GitLabMergeRequestResponse.class));
        return createDiscussion(target, number, comment.body(), position, current);
    }

    @Override
    public void replyToReviewComment(HostingTarget target, int number, long commentId, String body)
    {
        GitLabDiscussion discussion = discussion(target, number, commentId);
        http.post(target.access(), mergeRequestPath(target, number) + "/discussions/" + encode(discussion.id()) + "/notes", new Body(body), Void.class);
    }

    @Override
    public void editComment(HostingTarget target, int number, String commentKind, long commentId, String body)
    {
        checkArgument("comment".equals(commentKind) || "review_comment".equals(commentKind) || "issue_comment".equals(commentKind), "GitLab can edit note comments only");
        http.put(target.access(), mergeRequestPath(target, number) + "/notes/" + commentId, new Body(body), Void.class);
    }

    @Override
    public boolean toggleReaction(HostingTarget target, int number, String commentKind, Long commentId, String content, Boolean viewerHasReacted)
    {
        String path = mergeRequestPath(target, number) + (commentId == null ? "" : "/notes/" + commentId) + "/award_emoji";
        GitLabUser viewer = get(target.access(), "user", GitLabUser.class);
        String emoji = GitLabAward.awardName(content);
        List<GitLabAward> awards = http.pages(target.access(), path, GitLabAward[].class);
        Optional<GitLabAward> existing = mapped("award", () -> awards.stream()
                .filter(award -> emoji.equals(award.name()) && viewer.id().equals(award.user().id())).findFirst());
        if (existing.isPresent()) {
            http.delete(target.access(), path + "/" + existing.orElseThrow().id(), Void.class);
            return false;
        }
        http.post(target.access(), path, new Emoji(emoji), Void.class);
        return true;
    }

    @Override
    public void updateTitle(HostingTarget target, int number, String title)
    {
        http.put(target.access(), mergeRequestPath(target, number), new Title(title), Void.class);
    }

    @Override
    public void updateDescription(HostingTarget target, int number, String description)
    {
        http.put(target.access(), mergeRequestPath(target, number), new Description(description), Void.class);
    }

    @Override
    public void close(HostingTarget target, int number)
    {
        http.put(target.access(), mergeRequestPath(target, number), new State("close"), Void.class);
    }

    @Override
    public void markReady(HostingTarget target, int number)
    {
        String path = mergeRequestPath(target, number);
        GitLabMergeRequestResponse current = get(target.access(), path, GitLabMergeRequestResponse.class);
        String title = mapped("merge request", () -> current.title().replaceFirst("(?i)^(?:draft|wip):\\s*|^\\[(?:draft|wip)\\]\\s*|^\\((?:draft|wip)\\)\\s*", ""));
        http.put(target.access(), path, new Title(title), Void.class);
    }

    @Override
    public void updateMetadata(HostingTarget target, int number, List<String> assignees, List<String> labels)
    {
        List<Long> ids = assignees.stream().map(login -> http.user(target.access(), login).id()).collect(toImmutableList());
        http.put(target.access(), mergeRequestPath(target, number), new Metadata(ids, String.join(",", labels)), Void.class);
    }

    @Override
    public void updateReviewer(HostingTarget target, int number, String login, boolean requested)
    {
        String path = mergeRequestPath(target, number);
        GitLabMergeRequestResponse current = get(target.access(), path, GitLabMergeRequestResponse.class);
        long reviewer = http.user(target.access(), login).id();
        List<Long> ids = new ArrayList<>(mapped("merge request", () -> current.reviewers().stream().map(GitLabUser::id).filter(id -> id != reviewer).collect(toImmutableList())));
        if (requested) {
            ids.add(reviewer);
        }
        http.put(target.access(), path, new Reviewers(ids), Void.class);
    }

    @Override
    public void resolveThread(HostingTarget target, int number, long commentId, boolean resolved)
    {
        GitLabDiscussion discussion = discussion(target, number, commentId);
        http.put(target.access(), mergeRequestPath(target, number) + "/discussions/" + encode(discussion.id()), new Resolved(resolved), Void.class);
    }

    @Override
    public SubmittedReview submitReview(HostingTarget target, int number, String event, String body,
            List<InlineReviewComment> comments, String commitId)
    {
        checkArgument("APPROVE".equals(event) || "COMMENT".equals(event), "GitLab request-changes reviews are not supported by this connection");
        comments.forEach(GitLabPullRequestWriter::validateComment);
        String path = mergeRequestPath(target, number);
        GitLabMergeRequestResponse current = get(target.access(), path, GitLabMergeRequestResponse.class);
        checkHead(current, commitId);
        Optional<GitLabUser> viewer = "APPROVE".equals(event) ? Optional.of(get(target.access(), "user", GitLabUser.class)) : Optional.empty();
        List<GitLabFileDiff> files = comments.stream().anyMatch(comment -> comment.replyToCommentId().isEmpty())
                ? diffs.read(target.access(), path) : ImmutableList.of();
        List<PrTimelineEvent> events = new ArrayList<>();
        int published = 0;
        try {
            for (InlineReviewComment comment : comments) {
                if (comment.replyToCommentId().isPresent()) {
                    events.add(createReviewComment(target, number, comment));
                }
                else {
                    GitLabDiffPosition position = position(files, current, comment);
                    checkRevision(current, get(target.access(), path, GitLabMergeRequestResponse.class));
                    events.add(createDiscussion(target, number, comment.body(), position, current));
                }
                published++;
            }
            if (body != null && !body.isBlank()) {
                events.add(comment(target, number, body));
                published++;
            }
            if ("APPROVE".equals(event)) {
                http.post(target.access(), path + "/approve", new Sha(commitId), Void.class);
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
    public MergeResult merge(HostingTarget target, int number, String strategy, String headSha)
    {
        checkArgument("default".equals(strategy) || "merge".equals(strategy) || "squash".equals(strategy),
                "GitLab's merge method is configured by the project; rebase merge is not a per-request choice");
        Object command = "default".equals(strategy) ? new Sha(headSha) : new Merge(headSha, "squash".equals(strategy));
        var result = http.put(target.access(), mergeRequestPath(target, number) + "/merge", command, GitLabMergeRequestResponse.class).body().orElseThrow();
        return new MergeResult(result.mergeCommitSha(), "merged".equals(result.state()), "merged".equals(result.state()) ? "Merged" : "GitLab has not merged this request");
    }

    @Override
    public void rerunFailedChecks(HostingTarget target, int number)
    {
        Pipeline pipeline = get(target.access(), mergeRequestPath(target, number), GitLabMergeRequestResponse.class).requireHeadPipeline();
        List<GitLabJob> failed = http.pages(target.access(), pipeline.path(target.repository()) + "/jobs", GitLabJob[].class).stream()
                .filter(job -> "failed".equals(job.status())).collect(toImmutableList());
        if (failed.isEmpty()) {
            throw new HostingFailure(REVISION_CONFLICT, "No failed jobs are available to rerun");
        }
        int accepted = 0;
        try {
            for (GitLabJob job : failed) {
                http.post(target.access(), pipeline.projectPath(target.repository()) + "/jobs/" + job.id() + "/retry", ImmutableMap.of(), Void.class);
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
    public void removeFromMergeQueue(HostingTarget target, int number, boolean autoMerge)
    {
        checkArgument(autoMerge, "GitLab merge trains are not supported by this connection");
        http.post(target.access(), mergeRequestPath(target, number) + "/cancel_merge_when_pipeline_succeeds", ImmutableMap.of(), Void.class);
    }

    @Override
    public boolean hasMergeQueue(HostingTarget target, String baseRef)
    {
        projectPath(target);
        return false;
    }

    @Override
    public CreatedPullRequest create(HostingTarget target, String head, String base, String title, String body, boolean draft)
    {
        checkArgument(!head.contains(":"), "Select the GitLab source project before creating a merge request from a fork");
        GitLabMergeRequestResponse created = http.post(target.access(), projectPath(target) + "/merge_requests", new Create(head, base, draft ? "Draft: " + title : title, body),
                GitLabMergeRequestResponse.class).body().orElseThrow();
        return mapped("merge request", () -> new CreatedPullRequest(created.id(), toIntExact(created.iid()),
                HostingUrls.requireWithinBase(URI.create(created.webUrl()), target.access().server().webBaseUrl()).toASCIIString()));
    }

    private PrTimelineEvent createDiscussion(HostingTarget target, int number, String body, GitLabDiffPosition position,
            GitLabMergeRequestResponse current)
    {
        GitLabDiscussion created = http.post(target.access(), mergeRequestPath(target, number) + "/discussions", new DiscussionBody(body, position), GitLabDiscussion.class)
                .body().orElseThrow();
        if (created.id() == null && created.summary() != null) {
            return commandResult(created.summary());
        }
        return mapped("discussion", () -> thread(created, url(target.repository(), number), current));
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

    private GitLabDiscussion discussion(HostingTarget target, int number, long commentId)
    {
        List<GitLabDiscussion> discussions = http.pages(target.access(), mergeRequestPath(target, number) + "/discussions", GitLabDiscussion[].class);
        return mapped("discussion", () -> discussions.stream().filter(discussion -> discussion.notes().stream().anyMatch(note -> note.id() == commentId)).findFirst())
                .orElseThrow(() -> new HostingFailure(REVISION_CONFLICT, "The selected discussion no longer exists"));
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
