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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Typed wire shapes for the publish-tool proposals that park a task at
 * {@code AWAITING_REVIEW}. Each variant is a record whose components
 * become top-level JSON fields; the {@code action} discriminator and
 * the {@code source} attribution are derived from the type so handlers
 * don't repeat them as string literals at every call site.
 *
 * <p>The interface is purely a marker — {@link ParkedProposalService}
 * accepts any Jackson-serialisable payload and the notification body is
 * stored as JSON text. Today's read path (the publish dispatcher) still
 * reads back as {@link JsonNode}, so wire-shape stability matters: the
 * fields below match the previous {@code LinkedHashMap} construction
 * verbatim, with {@code @JsonInclude(NON_NULL)} carrying the same
 * "omitted when not applicable" semantics the old code had via
 * conditional {@code Map.put} calls.
 */
public interface ParkedProposal
{
    /** Action discriminator, e.g. {@code "request_review"}. Each record
     *  variant returns its own constant so the parking helper can log it
     *  on failure without inspecting the JSON. Also serialised as the
     *  wire {@code "action"} field by each implementation. */
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
    @JsonPropertyOrder({"action", "summary", "draftReply", "branch", "baseBranch",
            "worktreePath", "diffBase", "diff", "diffError", "source"})
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
        @Override @JsonProperty("action") public String action() { return "request_review"; }
        @Override @JsonProperty("source") public String source() { return "mcp:request_review"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"action", "body", "pr", "source"})
    record PostComment(String body, PrRef pr)
            implements ParkedProposal
    {
        @Override @JsonProperty("action") public String action() { return "post_comment"; }
        @Override @JsonProperty("source") public String source() { return "mcp:post_comment"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"action", "branch", "baseBranch", "worktreePath",
            "diffBase", "diff", "diffError", "source"})
    record Push(
            String branch,
            String baseBranch,
            String worktreePath,
            String diffBase,
            String diff,
            String diffError)
            implements ParkedProposal
    {
        @Override @JsonProperty("action") public String action() { return "push"; }
        @Override @JsonProperty("source") public String source() { return "mcp:push"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"action", "rootCommentId", "body", "pr", "source"})
    record ReplyReviewThread(long rootCommentId, String body, PrRef pr)
            implements ParkedProposal
    {
        @Override @JsonProperty("action") public String action() { return "reply_review_thread"; }
        @Override @JsonProperty("source") public String source() { return "mcp:reply_review_thread"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"action", "body", "pr", "source"})
    record ApprovePr(String body, PrRef pr)
            implements ParkedProposal
    {
        @Override @JsonProperty("action") public String action() { return "approve_pr"; }
        @Override @JsonProperty("source") public String source() { return "mcp:approve_pr"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"action", "strategy", "pr", "source"})
    record MergePr(String strategy, PrRef pr)
            implements ParkedProposal
    {
        @Override @JsonProperty("action") public String action() { return "merge_pr"; }
        @Override @JsonProperty("source") public String source() { return "mcp:merge_pr"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"action", "body", "filePath", "line", "side", "commitId",
            "startLine", "startSide", "pr", "source"})
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
        @Override @JsonProperty("action") public String action() { return "create_review_comment"; }
        @Override @JsonProperty("source") public String source() { return "mcp:create_review_comment"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"action", "body", "pr", "source"})
    record UpdatePrBody(String body, PrRef pr)
            implements ParkedProposal
    {
        @Override @JsonProperty("action") public String action() { return "update_pr_body"; }
        @Override @JsonProperty("source") public String source() { return "mcp:update_pr_body"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"action", "reviewer", "pr", "source"})
    record RequestReviewer(String reviewer, PrRef pr)
            implements ParkedProposal
    {
        @Override @JsonProperty("action") public String action() { return "request_reviewer"; }
        @Override @JsonProperty("source") public String source() { return "mcp:request_reviewer"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"action", "body", "issue", "source"})
    record CommentOnIssue(String body, IssueRef issue)
            implements ParkedProposal
    {
        @Override @JsonProperty("action") public String action() { return "comment_on_issue"; }
        @Override @JsonProperty("source") public String source() { return "mcp:comment_on_issue"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"action", "state", "issue", "source"})
    record SetIssueState(String state, IssueRef issue)
            implements ParkedProposal
    {
        @Override @JsonProperty("action") public String action() { return "set_issue_state"; }
        @Override @JsonProperty("source") public String source() { return "mcp:set_issue_state"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"action", "title", "head", "base", "body", "draft", "repo", "source"})
    record OpenPr(
            String title,
            String head,
            String base,
            String body,
            boolean draft,
            RepoRef repo)
            implements ParkedProposal
    {
        @Override @JsonProperty("action") public String action() { return "open_pr"; }
        @Override @JsonProperty("source") public String source() { return "mcp:open_pr"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"action", "event", "body", "comments", "pr", "source"})
    record PublishReview(
            String event,
            String body,
            JsonNode comments,
            PrRef pr)
            implements ParkedProposal
    {
        @Override @JsonProperty("action") public String action() { return "publish_review"; }
        @Override @JsonProperty("source") public String source() { return "mcp:publish_review"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"action", "threadId", "taskId", "branch", "baseBranch",
            "worktreePath", "nextTitle", "baseMode",
            "diffBase", "diff", "diffError", "source"})
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
        @Override @JsonProperty("action") public String action() { return "next_task"; }
        @Override @JsonProperty("source") public String source() { return "mcp:next_task"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"action", "threadId", "taskId", "branch", "baseBranch",
            "worktreePath", "nextTitle", "baseMode",
            "diffBase", "diff", "diffError", "source"})
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
            String diffError)
            implements ParkedProposal
    {
        @Override @JsonProperty("action") public String action() { return "ship_task"; }
        @Override @JsonProperty("source") public String source() { return "mcp:ship_task"; }
    }
}
