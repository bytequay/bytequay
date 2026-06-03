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
package com.bytequay.app.service.ids;

import com.bytequay.app.repository.IdSequenceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Random;

import static java.util.Objects.requireNonNull;

/**
 * Composes the human-readable thread and task ids that replace the
 * legacy UUIDs.
 *
 * <p>Format (see also {@code docs/mockups/workspace-thread-task-design.md}):
 * <pre>
 *   Thread   {workspaceId}.t{ymd}-{seq}-{rand2}
 *               e.g.  ws-bytequay.t260603-3-a1
 *   Task     {threadId}.k{seq}
 *               e.g.  ws-bytequay.t260603-3-a1.k2
 * </pre>
 *
 * <p>Reading a task id segment by segment:
 * <pre>
 *   ws-bytequay.t260603-3-a1.k2
 *   └────┬────┘└┬┘└──┬─┘│ │ │ └┬┘
 *        │     │    │  │ │ │  │
 *        │     │    │  │ │ │  └─ TASK seq        per-thread (tasks.seq)
 *        │     │    │  │ │ └──── TASK marker     IdTier.TASK.marker()
 *        │     │    │  │ └────── random suffix   RAND_SUFFIX_LEN chars from
 *        │     │    │  │                         RAND_ALPHABET (32 base32
 *        │     │    │  │                         chars, no i/l/o/u)
 *        │     │    │  └─────── SEGMENT_SEP
 *        │     │    └────────── THREAD seq       per-(workspace, day) counter
 *        │     │                                 from IdSequenceStore
 *        │     └─────────────── ymd (YYMMDD UTC) day of thread creation
 *        └─────────────────── THREAD marker      IdTier.THREAD.marker()
 *   └──── workspaceId   immutable workspace slug, prefixed with
 *                       IdTier.WORKSPACE.marker() at workspace creation
 * </pre>
 *
 * <p>The {@code ymd} segment is YYMMDD in UTC so lexicographic sort
 * yields chronological order within a workspace. The {@code seq} after
 * the date is workspace-and-day scoped and comes from
 * {@link IdSequenceStore}; the trailing two-character random suffix is
 * belt-and-suspenders against admin restore + clock skew scenarios in
 * which the persisted counter could re-issue an already-used value.
 *
 * <p>Task ids reuse the per-thread {@code tasks.seq} the caller is
 * already incrementing in {@code ThreadService.materialiseTask}, so no
 * additional sequence allocator is needed for tasks.
 *
 * <p>Properties this layout gives the rest of the system:
 * <ul>
 *   <li>Strip a suffix and you get the parent — task → thread → workspace.</li>
 *   <li>Lexicographic sort within a workspace is chronological by day.</li>
 *   <li>{@code LIKE 'ws-bytequay.t260603-%'} enumerates one workspace's
 *       threads cut on one day.</li>
 *   <li>{@code .worktrees/<task-id>/} on disk encodes the full hierarchy
 *       in the directory name.</li>
 * </ul>
 */
@Service
public class IdGenerator
{
    /**
     * Base32 alphabet without visually-confusable characters (no
     * {@code i}, {@code l}, {@code o}, {@code u}). 32 chars; combined
     * with {@link #RAND_SUFFIX_LEN} this yields 1024 distinct suffixes.
     */
    static final char[] RAND_ALPHABET =
            "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();

    /** Number of random characters appended to a thread id for
     *  collision-insurance under DB restore + clock skew scenarios. */
    private static final int RAND_SUFFIX_LEN = 2;

    /** YY in {@code YYMMDD}. Two-digit year wraps at 100 — adequate
     *  for the app's expected lifetime; documented in the design doc. */
    private static final int YEAR_MODULO = 100;

    /** Separator between {@code ymd} / {@code seq} / {@code rand2}
     *  inside a thread id's tail. */
    private static final String SEGMENT_SEP = "-";

    private final IdSequenceStore seqStore;
    private final Random random;

    /** Production constructor — uses {@link SecureRandom}. */
    @Autowired
    public IdGenerator(IdSequenceStore seqStore)
    {
        this(seqStore, new SecureRandom());
    }

    /**
     * Test constructor — lets the test inject a seeded {@link Random}
     * for reproducible ids. Package-private so production callers
     * can't accidentally weaken the entropy.
     */
    IdGenerator(IdSequenceStore seqStore, Random random)
    {
        this.seqStore = requireNonNull(seqStore, "seqStore is null");
        this.random = requireNonNull(random, "random is null");
    }

    /**
     * Compose a new thread id of the form
     * {@code <workspaceId>.t<ymd>-<seq>-<rand2>}, where {@code seq}
     * is read from {@link IdSequenceStore#nextThreadSeq} and
     * {@code rand2} is two base32 chars from {@link #RAND_ALPHABET}.
     *
     * @param workspaceId workspace this thread belongs to — the slug
     *                    becomes the visible prefix of the id and is
     *                    immutable across the thread's lifetime
     * @param now         caller-supplied instant so tests can pin the
     *                    {@code ymd} segment without freezing the JVM
     *                    clock; production wires {@code Instant.now()}
     */
    public String newThreadId(String workspaceId, Instant now)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(now, "now is null");
        String ymd = formatYmd(now);
        int seq = seqStore.nextThreadSeq(workspaceId, ymd);
        return workspaceId
                + IdTier.THREAD.marker() + ymd
                + SEGMENT_SEP + seq
                + SEGMENT_SEP + randomSuffix();
    }

    /**
     * Compose a new task id of the form {@code <threadId>.k<seq>}.
     * No random suffix — the per-thread {@code seq} is already unique
     * within its parent thread (which is itself globally unique).
     *
     * @param threadId parent thread's id
     * @param seq      per-thread sequence the caller is already
     *                 incrementing (today via
     *                 {@code taskStore.maxSeqForThread(threadId) + 1})
     */
    public String newTaskId(String threadId, long seq)
    {
        requireNonNull(threadId, "threadId is null");
        return threadId + IdTier.TASK.marker() + seq;
    }

    private String randomSuffix()
    {
        char[] chars = new char[RAND_SUFFIX_LEN];
        for (int i = 0; i < RAND_SUFFIX_LEN; i++) {
            chars[i] = RAND_ALPHABET[random.nextInt(RAND_ALPHABET.length)];
        }
        return new String(chars);
    }

    private static String formatYmd(Instant now)
    {
        LocalDate d = now.atZone(ZoneOffset.UTC).toLocalDate();
        return String.format("%02d%02d%02d",
                d.getYear() % YEAR_MODULO, d.getMonthValue(), d.getDayOfMonth());
    }
}
