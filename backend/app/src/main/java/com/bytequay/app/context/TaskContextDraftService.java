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
import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RepositoryNotebook;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

/** Captures task notes from committed Git objects in the task-owned worktree and saves them. */
@Service
public class TaskContextDraftService
{
    private final WorktreeService worktrees;
    private final WorkspaceRepositoryService workspaces;
    private final ContextBaselineReader reader;
    private final RepositoryContextService notebooks;
    private final ContextSourceChecker checker;

    public TaskContextDraftService(WorktreeService worktrees, WorkspaceRepositoryService workspaces,
            ContextBaselineReader reader, RepositoryContextService notebooks, ContextSourceChecker checker)
    {
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.reader = requireNonNull(reader, "reader is null");
        this.notebooks = requireNonNull(notebooks, "notebooks is null");
        this.checker = requireNonNull(checker, "checker is null");
    }

    /** Performs Git reads before the notebook/receipt write transaction. Agent text is never used as source evidence. */
    public Prepared prepare(AgentTask task, Proposal proposal, String operationId)
    {
        requireNonNull(proposal, "proposal is null");
        Captured captured = capture(task, proposal.sources(), proposal.entryId());
        RepositoryContextService.TaskNote note = new RepositoryContextService.TaskNote(operationId, task.id().toString(), proposal.kind(),
                proposal.scopeId(), proposal.entryId(), proposal.entryVersion(), proposal.title(), proposal.summary(), proposal.body(),
                captured.head(), captured.evidence());
        return new Prepared(captured.workspaceId(), captured.notebook().revision(), captured.tree(), note, captured.capturedBytes(), captured.base());
    }

    /**
     * Captures cited committed lines at the task HEAD and checks them at the task's base on the monitored branch. When they
     * support a change to an existing note, that note must not come from newer code than the base. Git reads only; no writes.
     */
    public Captured capture(AgentTask task, List<Source> sources, Optional<String> changedEntryId)
    {
        requireNonNull(task, "task is null");
        String workspaceId = task.workspaceId().orElseThrow();
        TaskWorktree tree = task.taskWorktree().orElseThrow();
        checkArgument(task.id().equals(tree.taskId()) && workspaceId.equals(tree.workspaceId()), "Task worktree ownership changed");
        checkArgument(sources.size() >= 1 && sources.size() <= 3, "Cite one to three source windows");
        WorkspaceRepositoryContext routing = workspaces.requireLocalRepository(workspaceId);
        checkArgument(task.repository().equals(tree.physicalRepository())
                && task.repository().equals(WorkflowAgentTasks.repositoryIdentity(routing.selectedRepository())),
                "Task repository differs from the selected workspace repository");
        RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
        String head = worktrees.inspect(tree).head();
        WorkspaceRepositoryContext snapshot = new WorkspaceRepositoryContext(workspaceId, tree.canonicalWorktree(),
                routing.selectedRepositoryId(), routing.selectedRepository(), OptionalLong.empty(),
                routing.selectedRepository(), routing.targetRemote(), routing.targetRemote(), routing.defaultBranch(), routing.extraRemotes());
        long repositoryId = RepositoryContextReader.repositoryId(snapshot);
        List<ContextSourceEvidence> captured = sources.stream().map(source -> {
            ContextBaselineReader.Read read = reader.read(snapshot, repositoryId, head, source.path(), source.startLine(), source.endLine());
            checkArgument(!read.excerpt().isBlank(), "A proposed source window is empty");
            return new ContextSourceEvidence("task-read:" + UUID.randomUUID(), read.reference(),
                    "Committed task HEAD " + head + ", blob " + read.blobId() + ", lines " + read.startLine() + "-" + read.endLine(),
                    read.excerpt(), ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.of(read.readAt()),
                    Optional.of(read.blobId()), Optional.of(head), read.truncated());
        }).collect(ImmutableList.toImmutableList());
        int capturedBytes = captured.stream().mapToInt(source -> ContextLimits.bytes(source.excerpt())).sum();
        checkArgument(capturedBytes <= 24 * 1024, "Task note captures exceed 24 KiB");
        String base = checker.base(workspaceId, head)
                .orElseThrow(() -> new IllegalArgumentException("The monitored branch is not available locally, so cited lines cannot be checked against it"));
        checker.requireAtBase(workspaceId, base, captured.stream().collect(toImmutableMap(ContextSourceEvidence::id, source -> source)));
        changedEntryId.ifPresent(id -> {
            Set<String> cited = notebook.entries().stream().filter(entry -> entry.id().equals(id)).flatMap(entry -> entry.sourceIds().stream()).collect(toSet());
            checkArgument(!checker.newerThan(workspaceId, base, notebook.sourceEvidence().stream().filter(source -> cited.contains(source.id())).toList()),
                    "This note was saved from code newer than this task's base; don't update it from older code");
        });
        checkArgument(worktrees.inspect(tree).head().equals(head), "Task HEAD changed while capturing note evidence");
        return new Captured(workspaceId, tree, notebook, head, base, captured, capturedBytes);
    }

    /** Saves the note as the active version. A changed HEAD or notebook revision requires a new proposal. */
    public Result commit(Prepared prepared)
    {
        requireNonNull(prepared, "prepared is null");
        RepositoryContextService.TaskNote note = prepared.note();
        checkArgument(worktrees.inspect(prepared.tree()).head().equals(note.baselineSha()), "Task HEAD changed before saving the note");
        RepositoryNotebook updated = notebooks.saveTaskNote(prepared.workspaceId(), prepared.expectedRevision(), note);
        String id = note.entryId().orElseGet(() -> RepositoryContextService.taskNoteId(note));
        ContextEntry saved = updated.entries().stream().filter(entry -> entry.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Task note was not retained"));
        return new Result(saved.id(), saved.version(), updated.revision(), note.baselineSha(), saved.sourceIds(), prepared.capturedBytes(), "ACTIVE");
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

    /** {@code base} is where the cited lines were found on the monitored branch. */
    public record Prepared(String workspaceId, long expectedRevision, TaskWorktree tree,
            RepositoryContextService.TaskNote note, int capturedBytes, String base) {}

    /** Lines captured at {@code head} and found at {@code base} on the monitored branch. */
    public record Captured(String workspaceId, TaskWorktree tree, RepositoryNotebook notebook, String head, String base,
            List<ContextSourceEvidence> evidence, int capturedBytes)
    {
        public Captured
        {
            evidence = ImmutableList.copyOf(evidence);
        }
    }

    /** What the agent sees after saving; other tasks can find the saved version at once. */
    public record Result(String entryId, long entryVersion, long notebookRevision, String committedHead,
            List<String> sourceEvidenceIds, int capturedBytes, String status) {}
}
