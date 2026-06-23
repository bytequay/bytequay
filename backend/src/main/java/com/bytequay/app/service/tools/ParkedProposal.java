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
package com.bytequay.app.service.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Typed wire shapes for the publish-tool proposals that park a task at
 * {@code AWAITING_REVIEW}. Each variant is a record whose components
 * become top-level JSON fields; the {@code action} discriminator and
 * the {@code source} attribution are derived from the type so neither
 * call sites nor the consumer carry string literals.
 *
 * <h3>Wire round-trip</h3>
 *
 * Jackson polymorphism on the {@code "action"} property closes the
 * loop: {@link com.bytequay.app.service.threads.ParkedProposalService}
 * serialises the variant a publisher hands it, and {@link
 * com.bytequay.app.service.threads.PublishService} reads the stored JSON
 * back as a {@code ParkedProposal} subtype so its dispatcher is a Java
 * 21 pattern-switch over the sealed type rather than a JsonNode field
 * lookup. {@code ignoreUnknown = true} lets the round-trip succeed even
 * though {@code source} is written as a virtual getter on the way out
 * with no matching constructor parameter on the way in.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "action", include = JsonTypeInfo.As.PROPERTY)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ParkedProposal.RequestReview.class, name = "request_review"),
        @JsonSubTypes.Type(value = ParkedProposal.PostComment.class, name = "post_comment"),
        @JsonSubTypes.Type(value = ParkedProposal.Push.class, name = "push"),
        @JsonSubTypes.Type(value = ParkedProposal.ReplyReviewThread.class, name = "reply_review_thread"),
        @JsonSubTypes.Type(value = ParkedProposal.ResolveReviewThread.class, name = "resolve_review_thread"),
        @JsonSubTypes.Type(value = ParkedProposal.ApprovePr.class, name = "approve_pr"),
        @JsonSubTypes.Type(value = ParkedProposal.MergePr.class, name = "merge_pr"),
        @JsonSubTypes.Type(value = ParkedProposal.CreateReviewComment.class, name = "create_review_comment"),
        @JsonSubTypes.Type(value = ParkedProposal.UpdatePrBody.class, name = "update_pr_body"),
        @JsonSubTypes.Type(value = ParkedProposal.RequestReviewer.class, name = "request_reviewer"),
        @JsonSubTypes.Type(value = ParkedProposal.CommentOnIssue.class, name = "comment_on_issue"),
        @JsonSubTypes.Type(value = ParkedProposal.SetIssueState.class, name = "set_issue_state"),
        @JsonSubTypes.Type(value = ParkedProposal.OpenPr.class, name = "open_pr"),
        @JsonSubTypes.Type(value = ParkedProposal.PublishReview.class, name = "publish_review"),
        @JsonSubTypes.Type(value = ParkedProposal.NextTask.class, name = "next_task"),
        @JsonSubTypes.Type(value = ParkedProposal.ShipTask.class, name = "ship_task")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface ParkedProposal
        permits ParkedProposal.RequestReview, ParkedProposal.PostComment, ParkedProposal.Push,
                ParkedProposal.ReplyReviewThread, ParkedProposal.ResolveReviewThread,
                ParkedProposal.ApprovePr, ParkedProposal.MergePr,
                ParkedProposal.CreateReviewComment, ParkedProposal.UpdatePrBody,
                ParkedProposal.RequestReviewer, ParkedProposal.CommentOnIssue,
                ParkedProposal.SetIssueState, ParkedProposal.OpenPr, ParkedProposal.PublishReview,
                ParkedProposal.NextTask, ParkedProposal.ShipTask
{
    /** Action discriminator, e.g. {@code "request_review"}. Each record
     *  variant returns its own constant so the parking helper can log it
     *  without inspecting JSON. Jackson serialises the discriminator via
     *  {@link JsonTypeInfo}, so this accessor is for Java callers only —
     *  it carries no {@code @JsonProperty} annotation. */
    String action();

    /** Attribution for where the proposal originated, e.g. {@code
     *  "mcp:request_review"}. Each variant returns its own constant;
     *  serialised as the wire {@code "source"} field. */
    String source();

    /** {@code {"owner", "repo", "number"}} — the PR coordinates a
     *  proposal targets. Same shape on the wire as the old {@code prMap}
     *  helper produced. */
    record PrRef(String owner, String repo, int number) {}

    /** {@code {"owner", "repo", "number"}} — the issue coordinates a
     *  proposal targets. Distinct type from {@link PrRef} so the call
     *  site reads as "this is an issue, not a PR". */
    record IssueRef(String owner, String repo, int number) {}

    /** {@code {"owner", "repo"}} — the repo coordinates for proposals
     *  that don't carry a number (e.g. {@code open_pr}). */
    record RepoRef(String owner, String repo) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RequestReview(
            String summary,
            String draftReply,
            String branch,
            String baseBranch,
            String worktreePath,
            String diffBase,
            String diff,
            String diffError)
            implements ParkedProposal
    {
        @Override public String action() { return "request_review"; }
        @Override @JsonProperty("source") public String source() { return "mcp:request_review"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PostComment(String body, PrRef pr)
            implements ParkedProposal
    {
        @Override public String action() { return "post_comment"; }
        @Override @JsonProperty("source") public String source() { return "mcp:post_comment"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Push(
            String branch,
            String baseBranch,
            String worktreePath,
            String diffBase,
            String diff,
            String diffError)
            implements ParkedProposal
    {
        @Override public String action() { return "push"; }
        @Override @JsonProperty("source") public String source() { return "mcp:push"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ReplyReviewThread(long rootCommentId, String body, PrRef pr)
            implements ParkedProposal
    {
        @Override public String action() { return "reply_review_thread"; }
        @Override @JsonProperty("source") public String source() { return "mcp:reply_review_thread"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ResolveReviewThread(long rootCommentId, boolean resolved, PrRef pr)
            implements ParkedProposal
    {
        @Override public String action() { return "resolve_review_thread"; }
        @Override @JsonProperty("source") public String source() { return "mcp:resolve_review_thread"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ApprovePr(String body, PrRef pr)
            implements ParkedProposal
    {
        @Override public String action() { return "approve_pr"; }
        @Override @JsonProperty("source") public String source() { return "mcp:approve_pr"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MergePr(String strategy, PrRef pr)
            implements ParkedProposal
    {
        @Override public String action() { return "merge_pr"; }
        @Override @JsonProperty("source") public String source() { return "mcp:merge_pr"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CreateReviewComment(
            String body,
            String filePath,
            int line,
            String side,
            String commitId,
            Integer startLine,
            String startSide,
            PrRef pr)
            implements ParkedProposal
    {
        @Override public String action() { return "create_review_comment"; }
        @Override @JsonProperty("source") public String source() { return "mcp:create_review_comment"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UpdatePrBody(String body, PrRef pr)
            implements ParkedProposal
    {
        @Override public String action() { return "update_pr_body"; }
        @Override @JsonProperty("source") public String source() { return "mcp:update_pr_body"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RequestReviewer(String reviewer, PrRef pr)
            implements ParkedProposal
    {
        @Override public String action() { return "request_reviewer"; }
        @Override @JsonProperty("source") public String source() { return "mcp:request_reviewer"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CommentOnIssue(String body, IssueRef issue)
            implements ParkedProposal
    {
        @Override public String action() { return "comment_on_issue"; }
        @Override @JsonProperty("source") public String source() { return "mcp:comment_on_issue"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SetIssueState(String state, IssueRef issue)
            implements ParkedProposal
    {
        @Override public String action() { return "set_issue_state"; }
        @Override @JsonProperty("source") public String source() { return "mcp:set_issue_state"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OpenPr(
            String title,
            String head,
            String base,
            String body,
            boolean draft,
            RepoRef repo)
            implements ParkedProposal
    {
        @Override public String action() { return "open_pr"; }
        @Override @JsonProperty("source") public String source() { return "mcp:open_pr"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PublishReview(
            String event,
            String body,
            List<InlineComment> comments,
            PrRef pr)
            implements ParkedProposal
    {
        @Override public String action() { return "publish_review"; }
        @Override @JsonProperty("source") public String source() { return "mcp:publish_review"; }

        /** Wire shape for one inline review comment in a {@code
         *  publish_review} proposal. The agent emits snake_case keys
         *  mirroring GitHub's inline review-comment API; {@link
         *  JsonProperty} maps them to the Java camelCase fields. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record InlineComment(
                @JsonProperty("file_path") String filePath,
                int line,
                String body,
                String side,
                @JsonProperty("start_line") Integer startLine,
                @JsonProperty("start_side") String startSide) {}
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NextTask(
            String threadId,
            String taskId,
            String branch,
            String baseBranch,
            String worktreePath,
            String nextTitle,
            String baseMode,
            String diffBase,
            String diff,
            String diffError)
            implements ParkedProposal
    {
        @Override public String action() { return "next_task"; }
        @Override @JsonProperty("source") public String source() { return "mcp:next_task"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ShipTask(
            String threadId,
            String taskId,
            String branch,
            String baseBranch,
            String worktreePath,
            String nextTitle,
            String baseMode,
            String diffBase,
            String diff,
            String diffError,
            String prTitle,
            String prBody)
            implements ParkedProposal
    {
        @Override public String action() { return "ship_task"; }
        @Override @JsonProperty("source") public String source() { return "mcp:ship_task"; }
    }
}
