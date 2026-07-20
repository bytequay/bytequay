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
package com.bytequay.app.domain;

import com.bytequay.app.service.concepts.Concept;
import com.bytequay.app.service.concepts.ConceptKind;

import java.time.Instant;

/**
 * One unit of work within a {@link Thread}. Owns a git branch, a
 * worktree directory at {@code <repo>/.worktrees/<task-id>/}, the
 * commits the agent makes there, and (once opened) a PR + CI status.
 *
 * <p>A thread accumulates tasks over its lifetime; at most one task
 * is foreground at a time (the others are parked but alive). "Next →"
 * parks the foreground task at AWAITING_REVIEW and starts a sibling
 * cut from a fresh main; Ship finalizes a task and returns to the
 * thread trunk.
 *
 * <p>Per-execution fields ({@code processPid}, {@code logPath}) are
 * populated while a CLI agent subprocess is alive and cleared on
 * exit. The CLI's {@code --resume} id lives <strong>on this Task</strong>
 * — each Task forks from the trunk planning session at creation and
 * thereafter owns its own conversation. {@link Thread#agentSessionId}
 * is the trunk planning session, which the foreground Task's session
 * forks from but never shares.
 *
 * @param seq monotonically increasing within the thread (1, 2, 3...)
 * @param baseBranch the branch this task was cut from — 'main',
 *                   'upstream/master', or a sibling task's branch when
 *                   stacked (rare escape hatch).
 * @param workingDir the repo root that {@code worktreePath} was cut
 *                   from; useful when the worktree itself is reaped
 *                   after the PR opens.
 * @param agentSessionId the CLI {@code --resume} id for this task's
 *                       worktree. {@code null} until the first turn
 *                       captures a session_started event; thereafter
 *                       sticky for the life of the task so reopens
 *                       continue the same conversation.
 */
@Concept(
        name = "task",
        kind = ConceptKind.NOUN,
        definition = "One unit of work within a thread — owns a git branch, a worktree, "
                + "the commits an agent makes there, and (once opened) a PR + CI status. "
                + "A thread accumulates tasks over time; at most one is foreground.",
        examples = {
                "request_review parks the foreground task at AWAITING_REVIEW.",
                "next → cuts a sibling task from a fresh main."
        },
        relatedTools = {"request_review", "ship", "next"},
        relatedConcepts = {"thread", "trunk", "awaiting_review"})
public record Task(
        String id,
        String threadId,
        long seq,
        TaskStatus status,
        String branchName,
        String worktreePath,
        String baseBranch,
        String workingDir,
        Integer processPid,
        String logPath,
        Integer prNumber,
        String prState,
        String ciState,
        String taskType,
        Integer linkedPrNumber,
        Integer linkedIssueNumber,
        long costUsdMilli,
        long tokensIn,
        long tokensOut,
        String agentSessionId,
        Instant createdAt,
        Instant endedAt,
        String errorMessage,
        /** User-supplied rename, e.g. "Cost & tokens parser". Null
         *  means fall back to the humanised branch name. */
        String name,
        /** Versioned ByteQuay role reference (for example {@code task@1}).
         *  Legacy rows may contain the old frozen prompt body or null; the
         *  role registry resolves both shapes without provider-specific files. */
        String roleSkill,
        /** Per-task override on the work-model cascade — the most
         *  specific scope. Null means "no override" — the resolver
         *  falls back to thread, then workspace, then global default.
         *  See V96 for the column and {@link WorkModel} for the
         *  value shape. */
        WorkModel workModel,
        /** When the task's branch first reached the remote — set on a
         *  push approval and on the implicit push an open_pr approval
         *  performs. Null until pushed; a distinct state from "committed
         *  locally" the task UI surfaces so a parked task no longer looks
         *  stuck. Persisted on its own column (V105) outside {@code
         *  saveTask}, like accept-edits, so a full-row save can't clobber
         *  it. */
        Instant pushedAt,
        /** The dev PR-collaboration lifecycle phase (V106). Orthogonal to
         *  {@link #status}, which stays the agent runtime axis. Populated
         *  from the {@code phase} column by the store's row mapper;
         *  written only via the phase machine's {@code updatePhase}
         *  (load-set-save), never through {@code saveTask}, so a full-row
         *  save can't clobber it. Fresh in-memory constructions default to
         *  {@link TaskPhase#IMPLEMENTING}, matching the column default for
         *  a freshly inserted row. */
        TaskPhase phase,
        /** Dev-agenda checklist JSON (V106), same shape as a review pass's
         *  agenda. Null until the agent sets it. Entity-managed (not
         *  mapped by {@code saveTask}); the row mapper populates it. */
        String agendaJson,
        /** Consecutive auto-pushes for the runaway-autonomy cap (V106). */
        int consecutiveAutoPushes,
        /** The {@code owner/repo#n} this task is permanently linked to,
         *  or null. Entity-managed via {@code linkTaskToPr}. */
        String linkedPrRef,
        /** Opening-prompt accumulator for a task materialised from the
         *  queue (V110). Seeded from the queue entry's initial prompt;
         *  the composer appends to it while the task is in
         *  {@link TaskPhase#QUEUED}; the agent reads it as its first-turn
         *  input on the QUEUED → IMPLEMENTING promotion. Null on tasks
         *  not born from the queue. Entity-managed (not mapped by
         *  {@code saveTask}); the row mapper populates it. */
        String openingPrompt,
        /** Immutable creator provenance. Values are the {@code ORIGIN_*}
         *  constants below; custom internal producers may use another stable
         *  lowercase identifier. */
        String origin)
{
    public static final String ORIGIN_USER = "user";
    public static final String ORIGIN_AGENT = "agent";
    public static final String ORIGIN_AUTOMATION = "automation";
    public static final String ORIGIN_ISSUE_MONITOR = "issue-monitor";
    public static final String ORIGIN_QUALITY_SCAN = "quality-scan";
    public static final String TYPE_WORKSPACE_ISSUE_TRIAGE = "WORKSPACE_ISSUE_TRIAGE";
    public static final String TYPE_LOCAL_QUALITY_SCAN = "LOCAL_QUALITY_SCAN";

    public Task
    {
        origin = origin == null || origin.isBlank() ? ORIGIN_USER : origin.strip();
    }

    /**
     * Back-compat constructor for the 26-field shape that predates the
     * {@code pushedAt} (V105), {@code phase} + agenda/auto-push/link
     * (V106) columns. Defaults them all, which is correct for every
     * fresh-construction call site — only the store's row mapper threads
     * the persisted values through the canonical constructor, and only
     * the dedicated entity-update methods ever write them.
     */
    public Task(
            String id,
            String threadId,
            long seq,
            TaskStatus status,
            String branchName,
            String worktreePath,
            String baseBranch,
            String workingDir,
            Integer processPid,
            String logPath,
            Integer prNumber,
            String prState,
            String ciState,
            String taskType,
            Integer linkedPrNumber,
            Integer linkedIssueNumber,
            long costUsdMilli,
            long tokensIn,
            long tokensOut,
            String agentSessionId,
            Instant createdAt,
            Instant endedAt,
            String errorMessage,
            String name,
            String roleSkill,
            WorkModel workModel)
    {
        this(id, threadId, seq, status, branchName, worktreePath, baseBranch, workingDir,
                processPid, logPath, prNumber, prState, ciState, taskType, linkedPrNumber,
                linkedIssueNumber, costUsdMilli, tokensIn, tokensOut, agentSessionId, createdAt,
                endedAt, errorMessage, name, roleSkill, workModel, null, TaskPhase.IMPLEMENTING,
                null, 0, null, null, ORIGIN_USER);
    }

    /** Fresh-construction shape for an explicitly attributed task. */
    public Task(
            String id,
            String threadId,
            long seq,
            TaskStatus status,
            String branchName,
            String worktreePath,
            String baseBranch,
            String workingDir,
            Integer processPid,
            String logPath,
            Integer prNumber,
            String prState,
            String ciState,
            String taskType,
            Integer linkedPrNumber,
            Integer linkedIssueNumber,
            long costUsdMilli,
            long tokensIn,
            long tokensOut,
            String agentSessionId,
            Instant createdAt,
            Instant endedAt,
            String errorMessage,
            String name,
            String roleSkill,
            WorkModel workModel,
            String origin)
    {
        this(id, threadId, seq, status, branchName, worktreePath, baseBranch, workingDir,
                processPid, logPath, prNumber, prState, ciState, taskType, linkedPrNumber,
                linkedIssueNumber, costUsdMilli, tokensIn, tokensOut, agentSessionId, createdAt,
                endedAt, errorMessage, name, roleSkill, workModel, null, TaskPhase.IMPLEMENTING,
                null, 0, null, null, origin);
    }

    /**
     * Back-compat constructor for the 31-field shape that predates the
     * {@code openingPrompt} column (V110). Defaults it to null — correct
     * for every task not born from the queue; only the store's row mapper
     * and the queue-materialise path thread a real value through the
     * canonical constructor.
     */
    public Task(
            String id,
            String threadId,
            long seq,
            TaskStatus status,
            String branchName,
            String worktreePath,
            String baseBranch,
            String workingDir,
            Integer processPid,
            String logPath,
            Integer prNumber,
            String prState,
            String ciState,
            String taskType,
            Integer linkedPrNumber,
            Integer linkedIssueNumber,
            long costUsdMilli,
            long tokensIn,
            long tokensOut,
            String agentSessionId,
            Instant createdAt,
            Instant endedAt,
            String errorMessage,
            String name,
            String roleSkill,
            WorkModel workModel,
            Instant pushedAt,
            TaskPhase phase,
            String agendaJson,
            int consecutiveAutoPushes,
            String linkedPrRef)
    {
        this(id, threadId, seq, status, branchName, worktreePath, baseBranch, workingDir,
                processPid, logPath, prNumber, prState, ciState, taskType, linkedPrNumber,
                linkedIssueNumber, costUsdMilli, tokensIn, tokensOut, agentSessionId, createdAt,
                endedAt, errorMessage, name, roleSkill, workModel, pushedAt, phase, agendaJson,
                consecutiveAutoPushes, linkedPrRef, null, ORIGIN_USER);
    }

    /** Back-compat constructor for the pre-provenance canonical shape. */
    public Task(
            String id,
            String threadId,
            long seq,
            TaskStatus status,
            String branchName,
            String worktreePath,
            String baseBranch,
            String workingDir,
            Integer processPid,
            String logPath,
            Integer prNumber,
            String prState,
            String ciState,
            String taskType,
            Integer linkedPrNumber,
            Integer linkedIssueNumber,
            long costUsdMilli,
            long tokensIn,
            long tokensOut,
            String agentSessionId,
            Instant createdAt,
            Instant endedAt,
            String errorMessage,
            String name,
            String roleSkill,
            WorkModel workModel,
            Instant pushedAt,
            TaskPhase phase,
            String agendaJson,
            int consecutiveAutoPushes,
            String linkedPrRef,
            String openingPrompt)
    {
        this(id, threadId, seq, status, branchName, worktreePath, baseBranch, workingDir,
                processPid, logPath, prNumber, prState, ciState, taskType, linkedPrNumber,
                linkedIssueNumber, costUsdMilli, tokensIn, tokensOut, agentSessionId, createdAt,
                endedAt, errorMessage, name, roleSkill, workModel, pushedAt, phase, agendaJson,
                consecutiveAutoPushes, linkedPrRef, openingPrompt, ORIGIN_USER);
    }

    /**
     * Resolves the directory the agent process should run in for this
     * task. Prefers {@code worktreePath} (set when a worktree was cut
     * for the task) and falls back to {@code workingDir} (the parent
     * repo) once the worktree is reaped after the PR opens.
     */
    public String agentCwd()
    {
        if (worktreePath != null && !worktreePath.isBlank()) {
            return worktreePath;
        }
        return workingDir;
    }

    // ── Copy-with helpers ─────────────────────────────────────────────
    // A record has no built-in "copy with one field changed", so callers
    // that only want to flip a single field had to re-list all 32 fields
    // in a fresh constructor — verbose and easy to get wrong (and the old
    // back-compat constructor silently reset the entity-managed columns).
    // These withers copy every field through the canonical constructor and
    // change exactly one, so call sites read as the intent: task.withStatus(IDLE).

    /** Copy with a new {@code status}; all other fields unchanged. */
    public Task withStatus(TaskStatus status)
    {
        return new Task(
                id,
                threadId,
                seq,
                status,
                branchName,
                worktreePath,
                baseBranch,
                workingDir,
                processPid,
                logPath,
                prNumber,
                prState,
                ciState,
                taskType,
                linkedPrNumber,
                linkedIssueNumber,
                costUsdMilli,
                tokensIn,
                tokensOut,
                agentSessionId,
                createdAt,
                endedAt,
                errorMessage,
                name,
                roleSkill,
                workModel,
                pushedAt,
                phase,
                agendaJson,
                consecutiveAutoPushes,
                linkedPrRef,
                openingPrompt,
                origin);
    }

    /** Copy with this task's own accumulated usage; all other fields
     *  unchanged. Used by the agent to persist a task's task-scoped spend
     *  (NOT the thread's lifetime total — see SqliteThreadStore's cascade). */
    public Task withUsage(long costUsdMilli, long tokensIn, long tokensOut)
    {
        return new Task(
                id,
                threadId,
                seq,
                status,
                branchName,
                worktreePath,
                baseBranch,
                workingDir,
                processPid,
                logPath,
                prNumber,
                prState,
                ciState,
                taskType,
                linkedPrNumber,
                linkedIssueNumber,
                costUsdMilli,
                tokensIn,
                tokensOut,
                agentSessionId,
                createdAt,
                endedAt,
                errorMessage,
                name,
                roleSkill,
                workModel,
                pushedAt,
                phase,
                agendaJson,
                consecutiveAutoPushes,
                linkedPrRef,
                openingPrompt,
                origin);
    }

    /** Copy with a new user-supplied {@code name}; all other fields unchanged. */
    public Task withName(String name)
    {
        return new Task(
                id,
                threadId,
                seq,
                status,
                branchName,
                worktreePath,
                baseBranch,
                workingDir,
                processPid,
                logPath,
                prNumber,
                prState,
                ciState,
                taskType,
                linkedPrNumber,
                linkedIssueNumber,
                costUsdMilli,
                tokensIn,
                tokensOut,
                agentSessionId,
                createdAt,
                endedAt,
                errorMessage,
                name,
                roleSkill,
                workModel,
                pushedAt,
                phase,
                agendaJson,
                consecutiveAutoPushes,
                linkedPrRef,
                openingPrompt,
                origin);
    }

    /** Copy with a new {@code workModel} override; all other fields unchanged. */
    public Task withWorkModel(WorkModel workModel)
    {
        return new Task(
                id,
                threadId,
                seq,
                status,
                branchName,
                worktreePath,
                baseBranch,
                workingDir,
                processPid,
                logPath,
                prNumber,
                prState,
                ciState,
                taskType,
                linkedPrNumber,
                linkedIssueNumber,
                costUsdMilli,
                tokensIn,
                tokensOut,
                agentSessionId,
                createdAt,
                endedAt,
                errorMessage,
                name,
                roleSkill,
                workModel,
                pushedAt,
                phase,
                agendaJson,
                consecutiveAutoPushes,
                linkedPrRef,
                openingPrompt,
                origin);
    }

    /** Copy with a new {@code worktreePath}; all other fields unchanged. */
    public Task withWorktreePath(String worktreePath)
    {
        return new Task(
                id,
                threadId,
                seq,
                status,
                branchName,
                worktreePath,
                baseBranch,
                workingDir,
                processPid,
                logPath,
                prNumber,
                prState,
                ciState,
                taskType,
                linkedPrNumber,
                linkedIssueNumber,
                costUsdMilli,
                tokensIn,
                tokensOut,
                agentSessionId,
                createdAt,
                endedAt,
                errorMessage,
                name,
                roleSkill,
                workModel,
                pushedAt,
                phase,
                agendaJson,
                consecutiveAutoPushes,
                linkedPrRef,
                openingPrompt,
                origin);
    }

    /** Copy with a new {@code processPid}; all other fields unchanged. */
    public Task withProcessPid(Integer processPid)
    {
        return new Task(
                id,
                threadId,
                seq,
                status,
                branchName,
                worktreePath,
                baseBranch,
                workingDir,
                processPid,
                logPath,
                prNumber,
                prState,
                ciState,
                taskType,
                linkedPrNumber,
                linkedIssueNumber,
                costUsdMilli,
                tokensIn,
                tokensOut,
                agentSessionId,
                createdAt,
                endedAt,
                errorMessage,
                name,
                roleSkill,
                workModel,
                pushedAt,
                phase,
                agendaJson,
                consecutiveAutoPushes,
                linkedPrRef,
                openingPrompt,
                origin);
    }

    /** Copy with a new {@code prNumber}; all other fields unchanged. */
    public Task withPrNumber(Integer prNumber)
    {
        return new Task(
                id,
                threadId,
                seq,
                status,
                branchName,
                worktreePath,
                baseBranch,
                workingDir,
                processPid,
                logPath,
                prNumber,
                prState,
                ciState,
                taskType,
                linkedPrNumber,
                linkedIssueNumber,
                costUsdMilli,
                tokensIn,
                tokensOut,
                agentSessionId,
                createdAt,
                endedAt,
                errorMessage,
                name,
                roleSkill,
                workModel,
                pushedAt,
                phase,
                agendaJson,
                consecutiveAutoPushes,
                linkedPrRef,
                openingPrompt,
                origin);
    }

    /** Copy with a new {@code linkedPrNumber}; all other fields unchanged. */
    public Task withLinkedPrNumber(Integer linkedPrNumber)
    {
        return new Task(
                id,
                threadId,
                seq,
                status,
                branchName,
                worktreePath,
                baseBranch,
                workingDir,
                processPid,
                logPath,
                prNumber,
                prState,
                ciState,
                taskType,
                linkedPrNumber,
                linkedIssueNumber,
                costUsdMilli,
                tokensIn,
                tokensOut,
                agentSessionId,
                createdAt,
                endedAt,
                errorMessage,
                name,
                roleSkill,
                workModel,
                pushedAt,
                phase,
                agendaJson,
                consecutiveAutoPushes,
                linkedPrRef,
                openingPrompt,
                origin);
    }

    /** Copy with a new {@code endedAt}; all other fields unchanged. */
    public Task withEndedAt(Instant endedAt)
    {
        return new Task(
                id,
                threadId,
                seq,
                status,
                branchName,
                worktreePath,
                baseBranch,
                workingDir,
                processPid,
                logPath,
                prNumber,
                prState,
                ciState,
                taskType,
                linkedPrNumber,
                linkedIssueNumber,
                costUsdMilli,
                tokensIn,
                tokensOut,
                agentSessionId,
                createdAt,
                endedAt,
                errorMessage,
                name,
                roleSkill,
                workModel,
                pushedAt,
                phase,
                agendaJson,
                consecutiveAutoPushes,
                linkedPrRef,
                openingPrompt,
                origin);
    }

    /** Copy with a new {@code errorMessage}; all other fields unchanged. */
    public Task withErrorMessage(String errorMessage)
    {
        return new Task(
                id,
                threadId,
                seq,
                status,
                branchName,
                worktreePath,
                baseBranch,
                workingDir,
                processPid,
                logPath,
                prNumber,
                prState,
                ciState,
                taskType,
                linkedPrNumber,
                linkedIssueNumber,
                costUsdMilli,
                tokensIn,
                tokensOut,
                agentSessionId,
                createdAt,
                endedAt,
                errorMessage,
                name,
                roleSkill,
                workModel,
                pushedAt,
                phase,
                agendaJson,
                consecutiveAutoPushes,
                linkedPrRef,
                openingPrompt,
                origin);
    }

    /** Copy with a new {@code agentSessionId}; all other fields unchanged. */
    public Task withAgentSessionId(String agentSessionId)
    {
        return new Task(
                id,
                threadId,
                seq,
                status,
                branchName,
                worktreePath,
                baseBranch,
                workingDir,
                processPid,
                logPath,
                prNumber,
                prState,
                ciState,
                taskType,
                linkedPrNumber,
                linkedIssueNumber,
                costUsdMilli,
                tokensIn,
                tokensOut,
                agentSessionId,
                createdAt,
                endedAt,
                errorMessage,
                name,
                roleSkill,
                workModel,
                pushedAt,
                phase,
                agendaJson,
                consecutiveAutoPushes,
                linkedPrRef,
                openingPrompt,
                origin);
    }
}
