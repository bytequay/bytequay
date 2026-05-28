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

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Shared, lane-neutral home for tool handler logic. Each
 * {@link AgentTool}-annotated method here is both the tool's
 * declaration (the registry derives its schema from the args record)
 * and its real implementation: {@link AgentToolRegistry#invoke} binds
 * the args record from the call's JSON and dispatches here, returning
 * a {@link ToolOutcome} the calling lane adapts to its transport.
 *
 * <p>This is where handlers move as they come off
 * {@code McpController}'s hand-coded dispatch. The read tools live
 * here first because they are pure, synchronous, and touch no
 * approval / park machinery — the lowest-risk slice to prove the
 * registry-dispatch seam end-to-end. The publishers and gated tools
 * follow once their park / approval flow has a lane-neutral
 * representation.
 */
@Component
public class AgentToolHandlers
{
    private final TaskStore taskStore;
    private final PullRequestStore prStore;
    private final ThreadStore threadStore;
    private final WorkspaceService workspaces;
    private final ObjectMapper mapper;

    public AgentToolHandlers(
            TaskStore taskStore,
            PullRequestStore prStore,
            ThreadStore threadStore,
            WorkspaceService workspaces,
            ObjectMapper mapper)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.prStore = requireNonNull(prStore, "prStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** Args record for {@code read_task}. */
    public record ReadTaskArgs(
            @ToolParam(description = "Task id to look up. Returns the task row as JSON or "
                    + "an error envelope when missing.",
                    required = true, wireName = "task_id") String taskId) {}

    @AgentTool(
            name = "read_task",
            description = "Read one task row by id. Returns id, threadId, seq, status, "
                    + "branchName, worktreePath, baseBranch, workingDir, prNumber, "
                    + "linkedPrNumber, linkedIssueNumber, taskType, createdAt, endedAt, "
                    + "errorMessage, name. Pure read — no GitHub call.",
            security = SecurityType.TASK_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome readTask(ReadTaskArgs args, ToolCall call)
    {
        String taskId = args.taskId();
        if (taskId == null || taskId.isBlank()) {
            return ToolOutcome.Completed.error("task_id is required");
        }
        Optional<Task> match = taskStore.findTaskById(taskId);
        if (match.isEmpty()) {
            return ToolOutcome.Completed.error("task not found: " + taskId);
        }
        Task task = match.get();
        ObjectNode out = mapper.createObjectNode();
        out.put("id", task.id());
        out.put("threadId", task.threadId());
        out.put("seq", task.seq());
        out.put("status", task.status() == null ? null : task.status().name());
        out.put("branchName", task.branchName());
        out.put("worktreePath", task.worktreePath());
        out.put("baseBranch", task.baseBranch());
        out.put("workingDir", task.workingDir());
        out.put("prNumber", task.prNumber());
        out.put("linkedPrNumber", task.linkedPrNumber());
        out.put("linkedIssueNumber", task.linkedIssueNumber());
        out.put("taskType", task.taskType());
        out.put("createdAt", task.createdAt() == null ? null : task.createdAt().toString());
        out.put("endedAt", task.endedAt() == null ? null : task.endedAt().toString());
        out.put("errorMessage", task.errorMessage());
        out.put("name", task.name());
        return ToolOutcome.Completed.ok(toJson(out));
    }

    /** Args record for {@code read_pr}. */
    public record ReadPrArgs(
            @ToolParam(description = "owner/name string of the repo.",
                    required = true) String repo,
            @ToolParam(description = "PR number.",
                    required = true) Integer number) {}

    @AgentTool(
            name = "read_pr",
            description = "Read one pull request's row from the local cache. "
                    + "Returns id, repo, number, title, author, state, mergeable, "
                    + "headRef, baseRef, additions, deletions, commentCount, "
                    + "attentionReason, snoozedUntil, lastSyncedAt. Pure read against "
                    + "the local DB — no GitHub API call. Run the regular sync if "
                    + "you want a fresher snapshot.",
            security = SecurityType.VCS_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome readPr(ReadPrArgs args, ToolCall call)
    {
        String repo = args.repo() == null ? "" : args.repo();
        int number = args.number() == null ? 0 : args.number();
        if (repo.isBlank() || number <= 0) {
            return ToolOutcome.Completed.error("repo (owner/name) and number are required");
        }
        Optional<Long> prId = prStore.findIdByRepoAndNumber(repo, number);
        if (prId.isEmpty()) {
            return ToolOutcome.Completed.error(
                    "PR not in local cache: " + repo + "#" + number
                            + " — run sync or add the repo to watched repos.");
        }
        Optional<PullRequest> match = prStore.findById(prId.get());
        if (match.isEmpty()) {
            return ToolOutcome.Completed.error(
                    "PR row gone after id lookup: " + repo + "#" + number);
        }
        PullRequest pr = match.get();
        ObjectNode out = mapper.createObjectNode();
        out.put("id", pr.id());
        out.put("repo", pr.repo());
        out.put("number", pr.number());
        out.put("title", pr.title());
        out.put("author", pr.author());
        out.put("state", pr.state());
        out.put("draft", pr.draft());
        out.put("mergeable", pr.mergeable());
        out.put("mergeableState", pr.mergeableState());
        out.put("headRef", pr.headRef());
        out.put("additions", pr.additions());
        out.put("deletions", pr.deletions());
        out.put("commentCount", pr.commentCount());
        out.put("attentionReason", pr.attentionReason() == null ? null : pr.attentionReason().name());
        out.put("createdAt", pr.createdAt() == null ? null : pr.createdAt().toString());
        out.put("updatedAt", pr.updatedAt() == null ? null : pr.updatedAt().toString());
        out.put("closedAt", pr.closedAt() == null ? null : pr.closedAt().toString());
        out.put("mergedAt", pr.mergedAt() == null ? null : pr.mergedAt().toString());
        out.put("snoozedUntil", pr.snoozedUntil() == null ? null : pr.snoozedUntil().toString());
        return ToolOutcome.Completed.ok(toJson(out));
    }

    /** Args record for {@code read_workspace_memory} — no args; the
     *  workspace is derived from the thread's owning row. */
    public record ReadWorkspaceMemoryArgs() {}

    @AgentTool(
            name = "read_workspace_memory",
            description = "Read the active workspace's memory_md (the distilled brain — "
                    + "architecture decisions, conventions, blockers). Returns the raw "
                    + "markdown body so the agent can quote it or use it as context "
                    + "for the current turn.",
            security = SecurityType.MEMORY_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome readWorkspaceMemory(ReadWorkspaceMemoryArgs args, ToolCall call)
    {
        Optional<Thread> threadOpt = threadStore.findThreadById(call.threadId());
        if (threadOpt.isEmpty()) {
            return ToolOutcome.Completed.error("thread not found: " + call.threadId());
        }
        String workspaceId = threadOpt.get().workspaceId();
        if (workspaceId == null || workspaceId.isBlank()) {
            return ToolOutcome.Completed.error("thread has no workspace bound");
        }
        try {
            String body = workspaces.getMemory(workspaceId);
            ObjectNode out = mapper.createObjectNode();
            out.put("workspaceId", workspaceId);
            out.put("memoryMd", body == null ? "" : body);
            return ToolOutcome.Completed.ok(toJson(out));
        }
        catch (RuntimeException e) {
            return ToolOutcome.Completed.error(
                    "could not read memory for workspace " + workspaceId + ": " + e.getMessage());
        }
    }

    private String toJson(ObjectNode out)
    {
        try {
            return mapper.writeValueAsString(out);
        }
        catch (JsonProcessingException e) {
            return "{\"error\":\"serialisation failed: " + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }
}
