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
 */
@Service
public class IdGenerator
{
    /**
     * Base32 alphabet without visually-confusable characters (no
     * {@code i}, {@code l}, {@code o}, {@code u}). 32 chars; two
     * positions give 1024 distinct suffixes.
     */
    static final char[] RAND_ALPHABET =
            "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();

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
        return workspaceId + ".t" + ymd + "-" + seq + "-" + random2();
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
        return threadId + ".k" + seq;
    }

    private String random2()
    {
        char a = RAND_ALPHABET[random.nextInt(RAND_ALPHABET.length)];
        char b = RAND_ALPHABET[random.nextInt(RAND_ALPHABET.length)];
        return new String(new char[] {a, b});
    }

    private static String formatYmd(Instant now)
    {
        LocalDate d = now.atZone(ZoneOffset.UTC).toLocalDate();
        return String.format("%02d%02d%02d",
                d.getYear() % 100, d.getMonthValue(), d.getDayOfMonth());
    }
}
