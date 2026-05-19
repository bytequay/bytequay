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

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskMessage;
import com.bytequay.app.domain.TaskStatus;

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
public interface TaskStore
{
    // ── tasks ────────────────────────────────────────────────────────

    /** Insert or update a task by primary key. The whole row is
     *  rewritten — callers should pass the full updated state. */
    void saveTask(Task task);

    /** Single-row lookup by id. Empty when no such task exists. */
    Optional<Task> findTaskById(String id);

    /** Permanent removal — drops the task row plus its child messages
     *  and per-file rollups. Callers must already have stopped any
     *  live session attached to the task; this method touches only
     *  durable state. No-op when the id doesn't exist. */
    void deleteTask(String id);

    /**
     * Tasks in the given status, newest-{@code updated_at_ms} first.
     * Drives the left-rail status sections; {@code limit} caps page
     * size (the UI shows a fixed-size grid and lazy-loads the rest
     * later).
     */
    List<Task> listTasksByStatus(TaskStatus status, int limit);

    /**
     * Fetch a batch of tasks by id, newest-{@code updated_at_ms}
     * first. Used by the group membership read path
     * ({@code TaskGroupStore#listMembers} returns ids, then this
     * resolves the rows in one round-trip).
     */
    List<Task> listTasksByIds(Collection<String> ids);

    // ── messages ─────────────────────────────────────────────────────

    /**
     * Append one message to a task's conversation log. The pair
     * {@code (taskId, seq)} must be unique — duplicates indicate a
     * StreamProcessor bug and surface as a JPA constraint violation.
     */
    void appendMessage(TaskMessage message);

    /** All messages for a task, oldest-first by seq. */
    List<TaskMessage> listMessages(String taskId);

    /** Tail window for the conversation index's initial load: the
     *  most-recent {@code limit} messages, returned <b>oldest-first</b>
     *  for direct render. The index panel walks the same set to derive
     *  per-prompt rows, so the two views never drift. Default
     *  implementation is correct but linear — production uses the
     *  SQLite override for an indexed query. */
    default List<TaskMessage> listRecentMessages(String taskId, int limit)
    {
        List<TaskMessage> all = listMessages(taskId);
        int from = Math.max(0, all.size() - limit);
        return List.copyOf(all.subList(from, all.size()));
    }

    /** Older window for "↑ load earlier" — the {@code limit} messages
     *  whose seq is strictly less than {@code beforeSeq}, oldest-first.
     *  Pair with {@link #listRecentMessages} when the user pages back.
     *  Default is filter-then-slice over the full list; SQLite override
     *  uses a {@code WHERE seq < ?} indexed query. */
    default List<TaskMessage> listMessagesBefore(String taskId, long beforeSeq, int limit)
    {
        List<TaskMessage> filtered = new ArrayList<>();
        for (TaskMessage m : listMessages(taskId)) {
            if (m.seq() < beforeSeq) {
                filtered.add(m);
            }
        }
        int from = Math.max(0, filtered.size() - limit);
        return List.copyOf(filtered.subList(from, filtered.size()));
    }

    /** Count of user-role text prompts in a task. Powers the "N of M"
     *  header in the conversation-index panel. Default scans
     *  {@link #listMessages}; SQLite override uses an aggregate query. */
    default long countUserMessages(String taskId)
    {
        long n = 0;
        for (TaskMessage m : listMessages(taskId)) {
            if ("user".equals(m.role()) && "text".equals(m.type())) {
                n++;
            }
        }
        return n;
    }

    /** Highest {@code seq} currently assigned in the task, or empty
     *  when no messages exist yet. The checkpoint scheduler uses this
     *  as the upper bound of the range it considers summarising. */
    default Optional<Long> maxMessageSeq(String taskId)
    {
        long max = -1;
        for (TaskMessage m : listMessages(taskId)) {
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
    default long sumTokensBetween(String taskId, long firstSeq, long lastSeq)
    {
        long total = 0;
        for (TaskMessage m : listMessages(taskId)) {
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
    default List<TaskMessage> listMessagesBetween(String taskId, long firstSeq, long lastSeq)
    {
        List<TaskMessage> out = new ArrayList<>();
        for (TaskMessage m : listMessages(taskId)) {
            if (m.seq() >= firstSeq && m.seq() <= lastSeq) {
                out.add(m);
            }
        }
        return List.copyOf(out);
    }

    // ── files ────────────────────────────────────────────────────────

    /**
     * Insert or update the per-(task, path) rollup. Callers pass the
     * cumulative state (count, lines added/removed, last operation /
     * timestamp); the store overwrites the row.
     */
    void recordFile(TaskFile file);

    /** All files touched by a task, most-recently-touched first. */
    List<TaskFile> listFiles(String taskId);
}
