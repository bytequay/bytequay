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
package com.bytequay.app.repository;

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for the Tasks surface. Keeps the JPA entities
 * package-private inside {@code repository.sqlite}; the service layer
 * talks only to this interface.
 *
 * <p>Operates on plain domain records — callers don't see entities
 * and aren't bound to JPA's lifecycle semantics. Same shape as
 * {@code EmailMutedSenderStore} / etc.
 */
public interface ThreadStore
{
    // ── threads ────────────────────────────────────────────────────────

    /** Stable source snapshot used by the trunk's current planning cycle. */
    record PlanningSnapshot(String repoRoot, String baseSha) {}

    /** Insert or update a thread by primary key. The whole row is
     *  rewritten — callers should pass the full updated state. */
    void saveThread(Thread thread);

    /** Single-row lookup by id. Empty when no such thread exists. */
    Optional<Thread> findThreadById(String id);

    /** Null-by-absence is the refresh signal: no separate stale flag. */
    default Optional<PlanningSnapshot> findPlanningSnapshot(String threadId)
    {
        return Optional.empty();
    }

    default void setPlanningSnapshot(String threadId, PlanningSnapshot snapshot)
    {
    }

    /** Consume the snapshot only if it is still the one the task used. */
    default boolean clearPlanningSnapshot(String threadId, String expectedBaseSha)
    {
        return false;
    }

    /** Distinct {@code planning_repo_root} values across threads updated
     *  since {@code since} — the clones whose base remote a background
     *  refresher keeps fetched. Empty default for test stores; the
     *  SQLite store overrides. */
    default List<String> listActivePlanningRepoRoots(Instant since)
    {
        return List.of();
    }

    /** The brain thread bound 1:1 to a task, if one has been created.
     *  Empty default for test stores; the SQLite store overrides. */
    default Optional<Thread> findBrainThreadByTask(String taskId)
    {
        return Optional.empty();
    }

    /** Permanent removal — drops the thread row plus its child messages
     *  and per-file rollups. Callers must already have stopped any
     *  live session attached to the thread; this method touches only
     *  durable state. No-op when the id doesn't exist. */
    void deleteThread(String id);

    /**
     * Tasks in the given status, newest-{@code updated_at_ms} first.
     * Drives the left-rail status sections; {@code limit} caps page
     * size (the UI shows a fixed-size grid and lazy-loads the rest
     * later).
     */
    List<Thread> listTasksByStatus(ThreadStatus status, int limit);

    /** Workspace-scoped variant of {@link #listTasksByStatus} — only
     *  rows whose {@code workspace_id} matches. The /api/threads
     *  endpoint routes here when the caller passes a workspaceId so
     *  per-workspace pages don't read the default workspace's data.
     *
     *  <p>Default implementation falls back to {@link #listTasksByStatus}
     *  and ignores the workspaceId. The real SQLite store overrides
     *  this; in-memory test stubs (which don't track workspace_id on
     *  their thread rows) inherit the unfiltered behaviour. */
    default List<Thread> listTasksByWorkspaceAndStatus(String workspaceId, ThreadStatus status, int limit)
    {
        return listTasksByStatus(status, limit);
    }

    /**
     * Every thread in a workspace, regardless of status — the enumeration
     * a workspace cascade-delete walks to tear down each thread (and its
     * tasks / stages / history) before dropping the workspace row.
     *
     * <p>Default implementation returns an empty list; the SQLite store
     * overrides it. In-memory test stubs that don't track {@code
     * workspace_id} inherit the empty result.
     */
    default List<Thread> listThreadsByWorkspace(String workspaceId)
    {
        return List.of();
    }

    /** The single public review trunk for one workspace PR. The database
     *  partial unique index enforces this relationship; this lookup lets
     *  review entry points reactivate the existing history instead of
     *  attempting a parallel trunk. */
    default Optional<Thread> findReviewTrunk(String workspaceId, String prRef)
    {
        return listThreadsByWorkspace(workspaceId).stream()
                .filter(thread -> prRef.equals(thread.prRef()))
                .findFirst();
    }

    /**
     * Fetch a batch of threads by id, newest-{@code updated_at_ms}
     * first. Used by the group membership read path
     * ({@code ThreadGroupStore#listMembers} returns ids, then this
     * resolves the rows in one round-trip).
     */
    List<Thread> listTasksByIds(Collection<String> ids);

    /**
     * Threads with an {@code updatedAt} at or after {@code since},
     * newest-first. Workspace Insights uses this to roll up spend +
     * counts over a 24h / 7d / 30d window without paging through the
     * whole table.
     */
    List<Thread> listThreadsUpdatedSince(Instant since);

    default List<Thread> listThreadsByWorkspaceUpdatedSince(
            String workspaceId, Instant since)
    {
        return listThreadsUpdatedSince(since).stream()
                .filter(thread -> workspaceId.equals(thread.workspaceId()))
                .toList();
    }

    /** One aggregation bucket of AI spend, grouped by the owning thread's
     *  provider / flow / kind — backs the AI usage ledger. */
    record AiSpendRow(String provider, String flow, String kind, long costMilli, long calls) {}

    /** AI spend over {@code [start, end)}, grouped by provider / flow / kind.
     *  Empty default for in-memory test stores; the SQLite store aggregates. */
    default List<AiSpendRow> aggregateAiSpend(Instant start, Instant end)
    {
        return List.of();
    }

    // ── messages ─────────────────────────────────────────────────────

    /**
     * Append one message to a thread's conversation log. The pair
     * {@code (threadId, seq)} must be unique — duplicates indicate a
     * StreamProcessor bug and surface as a JPA constraint violation.
     */
    void appendMessage(ThreadMessage message);

    /** All messages for a thread, oldest-first by seq. */
    List<ThreadMessage> listMessages(String threadId);

    // ── Stage transcripts (the decoupled stage_messages store) ────────────
    // A STAGE-scoped message has its own per-stage seq space, so concurrent
    // per-stage agents can't collide on the thread-global (thread_id, seq).
    // These delegate to StageMessageStore; defaults are no-ops for test
    // stores that don't opt in.

    /** Append a STAGE-scoped message to its stage's transcript.
     *  {@code message.stageId()} must be set; {@code message.seq()} is the
     *  per-stage sequence the caller allocated. */
    default void appendStageMessage(ThreadMessage message)
    {
    }

    /** Highest per-stage seq for a stage, or empty when it has none — the
     *  seed for a stage agent's per-stage next-seq counter. */
    default Optional<Long> maxStageMessageSeq(String stageId)
    {
        return Optional.empty();
    }

    /** A stage's transcript, oldest-first by per-stage seq. */
    default List<ThreadMessage> listStageMessages(String stageId)
    {
        return List.of();
    }

    /** Every stage message across all of a task's stages, oldest-first by
     *  seq — for per-task aggregation and merging into the stage-detail feed
     *  during the transition off the shared log. */
    default List<ThreadMessage> listStageMessagesByTask(String taskId)
    {
        return List.of();
    }

    /** Tail window for the conversation index's initial load: the
     *  most-recent {@code limit} messages, returned <b>oldest-first</b>
     *  for direct render. The index panel walks the same set to derive
     *  per-prompt rows, so the two views never drift. Default
     *  implementation is correct but linear — production uses the
     *  SQLite override for an indexed query. */
    default List<ThreadMessage> listRecentMessages(String threadId, int limit)
    {
        List<ThreadMessage> all = listMessages(threadId);
        int from = Math.max(0, all.size() - limit);
        return List.copyOf(all.subList(from, all.size()));
    }

    /** Older window for "↑ load earlier" — the {@code limit} messages
     *  whose seq is strictly less than {@code beforeSeq}, oldest-first.
     *  Pair with {@link #listRecentMessages} when the user pages back.
     *  Default is filter-then-slice over the full list; SQLite override
     *  uses a {@code WHERE seq < ?} indexed query. */
    default List<ThreadMessage> listMessagesBefore(String threadId, long beforeSeq, int limit)
    {
        List<ThreadMessage> filtered = new ArrayList<>();
        for (ThreadMessage m : listMessages(threadId)) {
            if (m.seq() < beforeSeq) {
                filtered.add(m);
            }
        }
        int from = Math.max(0, filtered.size() - limit);
        return List.copyOf(filtered.subList(from, filtered.size()));
    }

    /** Count of user-role text prompts in a thread. Powers the "N of M"
     *  header in the conversation-index panel. Default scans
     *  {@link #listMessages}; SQLite override uses an aggregate query. */
    default long countUserMessages(String threadId)
    {
        long n = 0;
        for (ThreadMessage m : listMessages(threadId)) {
            if ("user".equals(m.role()) && "text".equals(m.type())) {
                n++;
            }
        }
        return n;
    }

    /** Most-recent {@code limit} <b>user prompts</b> (role=user, type=text),
     *  oldest-first. Unlike {@link #listRecentMessages}, this skips the
     *  tool / assistant / thinking chatter, so the conversation index
     *  opens on the last N prompts the human actually typed — a single
     *  busy turn can otherwise bury every earlier prompt past the
     *  message window. Default filters {@link #listMessages}; SQLite
     *  overrides with an indexed query. */
    default List<ThreadMessage> listRecentUserMessages(String threadId, int limit)
    {
        List<ThreadMessage> prompts = new ArrayList<>();
        for (ThreadMessage m : listMessages(threadId)) {
            if ("user".equals(m.role()) && "text".equals(m.type())) {
                prompts.add(m);
            }
        }
        int from = Math.max(0, prompts.size() - limit);
        return List.copyOf(prompts.subList(from, prompts.size()));
    }

    /** Older user-prompt window for "↑ load earlier": the {@code limit}
     *  user prompts whose seq is strictly less than {@code beforeSeq},
     *  oldest-first. Prompt-based sibling of {@link #listMessagesBefore}. */
    default List<ThreadMessage> listUserMessagesBefore(String threadId, long beforeSeq, int limit)
    {
        List<ThreadMessage> prompts = new ArrayList<>();
        for (ThreadMessage m : listMessages(threadId)) {
            if (m.seq() < beforeSeq && "user".equals(m.role()) && "text".equals(m.type())) {
                prompts.add(m);
            }
        }
        int from = Math.max(0, prompts.size() - limit);
        return List.copyOf(prompts.subList(from, prompts.size()));
    }

    /** Highest {@code seq} currently assigned in the thread, or empty
     *  when no messages exist yet. The checkpoint scheduler uses this
     *  as the upper bound of the range it considers summarising. */
    default Optional<Long> maxMessageSeq(String threadId)
    {
        long max = -1;
        for (ThreadMessage m : listMessages(threadId)) {
            if (m.seq() > max) {
                max = m.seq();
            }
        }
        return max < 0 ? Optional.empty() : Optional.of(max);
    }

    /** Sum of {@code tokens_in + tokens_out} across messages whose
     *  {@code seq} is in {@code [firstSeq, lastSeq]} (inclusive).
     *  Null token counts are treated as zero. Drives the auto-segment
     *  threshold check — keeping it as a single aggregate query avoids
     *  pulling the message bodies into memory just to add numbers. */
    default long sumTokensBetween(String threadId, long firstSeq, long lastSeq)
    {
        long total = 0;
        for (ThreadMessage m : listMessages(threadId)) {
            if (m.seq() < firstSeq || m.seq() > lastSeq) {
                continue;
            }
            if (m.tokensIn() != null) {
                total += m.tokensIn();
            }
            if (m.tokensOut() != null) {
                total += m.tokensOut();
            }
        }
        return total;
    }

    /** Messages whose {@code seq} is in {@code [firstSeq, lastSeq]}
     *  (inclusive), oldest-first. The summariser walks this range to
     *  build the dense rendered conversation it hands to Haiku. */
    default List<ThreadMessage> listMessagesBetween(String threadId, long firstSeq, long lastSeq)
    {
        List<ThreadMessage> out = new ArrayList<>();
        for (ThreadMessage m : listMessages(threadId)) {
            if (m.seq() >= firstSeq && m.seq() <= lastSeq) {
                out.add(m);
            }
        }
        return List.copyOf(out);
    }

    // ── files ────────────────────────────────────────────────────────

    /**
     * Insert or update the per-(thread, path) rollup. Callers pass the
     * cumulative state (count, lines added/removed, last operation /
     * timestamp); the store overwrites the row.
     */
    void recordFile(ThreadFile file);

    /** All files touched by a thread, most-recently-touched first. */
    List<ThreadFile> listFiles(String threadId);
}
