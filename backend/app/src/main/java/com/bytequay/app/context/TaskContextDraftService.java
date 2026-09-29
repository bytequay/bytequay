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
package com.bytequay.app.context;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.app.agent.WorkflowAgentTasks;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RepositoryNotebook;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Captures proposed task notes from committed Git objects in the task-owned worktree. */
@Service
public class TaskContextDraftService
{
    private final WorktreeService worktrees;
    private final WorkspaceRepositoryService workspaces;
    private final ContextBaselineReader reader;
    private final RepositoryContextService notebooks;

    public TaskContextDraftService(WorktreeService worktrees, WorkspaceRepositoryService workspaces,
            ContextBaselineReader reader, RepositoryContextService notebooks)
    {
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.reader = requireNonNull(reader, "reader is null");
        this.notebooks = requireNonNull(notebooks, "notebooks is null");
    }

    /** Performs Git reads before the notebook/receipt write transaction. Agent text is never used as source evidence. */
    public Prepared prepare(AgentTask task, Proposal proposal, String draftId)
    {
        requireNonNull(task, "task is null");
        requireNonNull(proposal, "proposal is null");
        String workspaceId = task.workspaceId().orElseThrow();
        TaskWorktree tree = task.taskWorktree().orElseThrow();
        checkArgument(task.id().equals(tree.taskId()) && workspaceId.equals(tree.workspaceId()), "Task worktree ownership changed");
        checkArgument(proposal.sources().size() >= 1 && proposal.sources().size() <= 3, "Propose one to three source windows");
        WorkspaceRepositoryContext routing = workspaces.requireLocalRepository(workspaceId);
        checkArgument(task.repository().equals(tree.physicalRepository())
                && task.repository().equals(WorkflowAgentTasks.repositoryIdentity(routing.selectedRepository())),
                "Task repository differs from the selected workspace repository");
        long revision = notebooks.getNotebook(workspaceId).revision();
        String head = worktrees.inspect(tree).head();
        WorkspaceRepositoryContext snapshot = new WorkspaceRepositoryContext(workspaceId, tree.canonicalWorktree(),
                routing.selectedRepositoryId(), routing.selectedRepository(), OptionalLong.empty(),
                routing.selectedRepository(), routing.targetRemote(), routing.targetRemote(), routing.defaultBranch(), routing.extraRemotes());
        long repositoryId = RepositoryContextReader.repositoryId(snapshot);
        List<ContextSourceEvidence> captured = proposal.sources().stream().map(source -> {
            ContextBaselineReader.Read read = reader.read(snapshot, repositoryId, head, source.path(), source.startLine(), source.endLine());
            checkArgument(!read.excerpt().isBlank(), "A proposed source window is empty");
            return new ContextSourceEvidence("task-read:" + UUID.randomUUID(), read.reference(),
                    "Committed task HEAD " + head + ", blob " + read.blobId() + ", lines " + read.startLine() + "-" + read.endLine(),
                    read.excerpt(), ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.of(read.readAt()),
                    Optional.of(read.blobId()), Optional.of(head), read.truncated());
        }).collect(ImmutableList.toImmutableList());
        int capturedBytes = captured.stream().mapToInt(source -> ContextLimits.bytes(source.excerpt())).sum();
        checkArgument(capturedBytes <= 24 * 1024, "Task note captures exceed 24 KiB");
        checkArgument(worktrees.inspect(tree).head().equals(head), "Task HEAD changed while capturing note evidence");
        RepositoryContextService.TaskDraftProposal draft = new RepositoryContextService.TaskDraftProposal(draftId, task.id().toString(), proposal.kind(),
                proposal.scopeId(), proposal.entryId(), proposal.entryVersion(), proposal.title(), proposal.summary(), proposal.body(), head, captured);
        return new Prepared(workspaceId, revision, tree, draft, capturedBytes);
    }

    /** Writes only the pending draft. A changed HEAD or notebook revision requires a new proposal. */
    public Result commit(Prepared prepared)
    {
        requireNonNull(prepared, "prepared is null");
        checkArgument(worktrees.inspect(prepared.tree()).head().equals(prepared.proposal().baselineSha()),
                "Task HEAD changed before saving the draft");
        RepositoryNotebook updated = notebooks.proposeTaskDraft(prepared.workspaceId(), prepared.expectedRevision(), prepared.proposal());
        var saved = updated.pendingDrafts().stream()
                .filter(draft -> draft.id().equals(prepared.proposal().draftId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Task proposal was not retained"));
        return new Result(saved.id(), saved.entry().id(), updated.revision(), prepared.proposal().baselineSha(),
                saved.entry().sourceIds(), prepared.capturedBytes(), "PENDING_APPROVAL");
    }

    public record Source(String path, int startLine, Optional<Integer> endLine)
    {
        public Source
        {
            ContextLimits.text(path, "path", 1000);
            ContextLimits.pathPrefix(path + "/");
            checkArgument(startLine > 0, "startLine must be positive");
            requireNonNull(endLine, "endLine is null");
            checkArgument(endLine.isEmpty() || endLine.orElseThrow() >= startLine, "endLine precedes startLine");
        }
    }

    public record Proposal(ContextEntryKind kind, String scopeId, Optional<String> entryId, Optional<Long> entryVersion,
            String title, String summary, String body, List<Source> sources)
    {
        public Proposal
        {
            requireNonNull(kind, "kind is null");
            ContextLimits.text(scopeId, "scopeId", 1100);
            requireNonNull(entryId, "entryId is null");
            requireNonNull(entryVersion, "entryVersion is null");
            checkArgument(entryId.isPresent() == entryVersion.isPresent(), "entryId and entryVersion must be supplied together");
            ContextLimits.text(title, "title", 200);
            ContextLimits.text(summary, "summary", 1000);
            ContextLimits.text(body, "body", ContextLimits.MAX_BODY_BYTES);
            sources = ImmutableList.copyOf(sources);
        }
    }

    public record Prepared(String workspaceId, long expectedRevision, TaskWorktree tree,
            RepositoryContextService.TaskDraftProposal proposal, int capturedBytes) {}

    public record Result(String draftId, String entryId, long notebookRevision, String committedHead,
            List<String> sourceEvidenceIds, int capturedBytes, String status) {}
}
