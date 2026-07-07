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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestIdGenerator
{
    /** 2026-06-03 noon UTC — the YMD segment that should appear in
     *  generated thread ids is {@code "260603"}. */
    private static final Instant JUNE_3_2026 = Instant.parse("2026-06-03T12:00:00Z");

    @Test
    void threadIdHasTheExpectedShape()
    {
        IdGenerator gen = new IdGenerator(constantSeq(3), seeded(1L));

        String id = gen.newThreadId(JUNE_3_2026);

        // t marker, ymd, -seq-, two-char base32 suffix. No workspace
        // prefix — a thread's workspace_id lives in its own column.
        assertThat(id).matches("t260603-3-[0-9a-z]{2}");
    }

    @Test
    void threadIdEmbedsTheSeqFromTheStore()
    {
        // The generator must pass through whatever the store returned —
        // not transform, not bound, not bias. Covers a range so a typo
        // like %d-only-up-to-9 wouldn't slip past.
        for (int seq : List.of(1, 5, 42, 999, 10_000)) {
            IdGenerator gen = new IdGenerator(constantSeq(seq), seeded(1L));

            String id = gen.newThreadId(JUNE_3_2026);

            assertThat(id).contains("-" + seq + "-");
        }
    }

    @Test
    void ymdFollowsUtcNotTheLocalZone()
    {
        // 23:30 UTC on Dec 31 is already the next year in many local
        // zones (UTC+1..14). The generated id must use the UTC date,
        // not the JVM's TZ, so the same wall-clock instant produces
        // the same id regardless of where it was generated.
        Instant utcEdge = Instant.parse("2026-12-31T23:30:00Z");

        IdGenerator gen = new IdGenerator(constantSeq(1), seeded(1L));
        String id = gen.newThreadId(utcEdge);

        assertThat(id).startsWith("t261231-");
    }

    @Test
    void daysRolloverChangesTheYmdSegment()
    {
        IdGenerator gen = new IdGenerator(constantSeq(1), seeded(1L));

        String day1 = gen.newThreadId(Instant.parse("2026-06-03T00:00:00Z"));
        String day2 = gen.newThreadId(Instant.parse("2026-06-04T00:00:00Z"));

        assertThat(day1).startsWith("t260603-");
        assertThat(day2).startsWith("t260604-");
    }

    @Test
    void januaryAndSingleDigitDaysAreZeroPadded()
    {
        IdGenerator gen = new IdGenerator(constantSeq(1), seeded(1L));

        String id = gen.newThreadId(Instant.parse("2026-01-09T12:00:00Z"));

        // Must be 260109, not 26019 — checkstyle on the format string
        // wouldn't catch a missing %02d, so the test does.
        assertThat(id).startsWith("t260109-");
    }

    @Test
    void randomSuffixOnlyUsesNonConfusableBase32Chars()
    {
        // Empirical sweep: across 256 different seeds the generated
        // suffix never uses any character outside RAND_ALPHABET.
        // Particularly: never i/l/o/u, never uppercase, never punct.
        for (long seed = 0; seed < 256; seed++) {
            IdGenerator gen = new IdGenerator(constantSeq(1), new Random(seed));

            String id = gen.newThreadId(JUNE_3_2026);
            String suffix = id.substring(id.lastIndexOf('-') + 1);

            assertThat(suffix).hasSize(2);
            // i / l / o / u are deliberately excluded — visually
            // confusable with 1/0 in logs and URLs.
            assertThat(suffix).doesNotContain("i", "l", "o", "u");
            for (char c : suffix.toCharArray()) {
                assertThat(new String(IdGenerator.RAND_ALPHABET)).contains(String.valueOf(c));
            }
        }
    }

    @Test
    void taskIdAppendsSeqWithTheKMarker()
    {
        IdGenerator gen = new IdGenerator(constantSeq(1), seeded(1L));

        assertThat(gen.newTaskId("t260603-3-a1", 2L))
                .isEqualTo("t260603-3-a1.k2");
    }

    @Test
    void taskIdHandlesMultiDigitSeqs()
    {
        IdGenerator gen = new IdGenerator(constantSeq(1), seeded(1L));

        assertThat(gen.newTaskId("parent", 1L)).isEqualTo("parent.k1");
        assertThat(gen.newTaskId("parent", 12L)).isEqualTo("parent.k12");
        assertThat(gen.newTaskId("parent", 9999L)).isEqualTo("parent.k9999");
    }

    @Test
    void taskIdDoesNotCallTheSeqStore()
    {
        // Task ids reuse tasks.seq from the caller — no allocation
        // through IdSequenceStore. A stub that throws on call proves
        // the generator doesn't reach into the store on this path.
        IdSequenceStore exploding = ymd -> {
            throw new AssertionError("newTaskId must not touch IdSequenceStore");
        };
        IdGenerator gen = new IdGenerator(exploding, seeded(1L));

        assertThat(gen.newTaskId("parent", 2L)).isEqualTo("parent.k2");
    }

    @Test
    void threadIdIsByteStableForFixedInputs()
    {
        // Two generators with the same inputs (instant, store-returned
        // seq, RNG seed) must produce byte-identical ids. Important
        // once the id appears anywhere cacheable — log lines, file
        // paths, branch trailers.
        IdGenerator first = new IdGenerator(constantSeq(7), seeded(42L));
        IdGenerator second = new IdGenerator(constantSeq(7), seeded(42L));

        assertThat(first.newThreadId(JUNE_3_2026))
                .isEqualTo(second.newThreadId(JUNE_3_2026));
    }

    @Test
    void seqStoreIsCalledExactlyOncePerNewThreadId()
    {
        // Counter exhaustion / double-allocation bugs would manifest
        // as N>1 calls; surface that here so a future refactor that
        // accidentally reads-then-re-reads doesn't silently leak.
        CountingStore counter = new CountingStore();
        IdGenerator gen = new IdGenerator(counter, seeded(1L));

        gen.newThreadId(JUNE_3_2026);

        assertThat(counter.calls).isEqualTo(1);
    }

    @Test
    void seqStoreReceivesTheUtcYmdAsKey()
    {
        // A wrong key would produce a wrong counter scope (e.g. all
        // days sharing one counter). Assert the exact argument handed in.
        RecordingStore recording = new RecordingStore();
        IdGenerator gen = new IdGenerator(recording, seeded(1L));

        gen.newThreadId(JUNE_3_2026);

        assertThat(recording.ymd).isEqualTo("260603");
    }

    @Test
    void rejectsNullInputs()
    {
        IdGenerator gen = new IdGenerator(constantSeq(1), seeded(1L));

        assertThatThrownBy(() -> gen.newThreadId(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> gen.newTaskId(null, 1L))
                .isInstanceOf(NullPointerException.class);
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static Random seeded(long seed)
    {
        return new Random(seed);
    }

    /** Store stub that returns a fixed seq value on every call. */
    private static IdSequenceStore constantSeq(int value)
    {
        return ymd -> value;
    }

    /** Store stub that records its last call's argument. */
    private static final class RecordingStore
            implements IdSequenceStore
    {
        String ymd;

        @Override
        public int nextThreadSeq(String ymd)
        {
            this.ymd = ymd;
            return 1;
        }
    }

    /** Store stub that counts the number of calls received. */
    private static final class CountingStore
            implements IdSequenceStore
    {
        int calls;
        final Map<String, Integer> seqByYmd = new HashMap<>();

        @Override
        public int nextThreadSeq(String ymd)
        {
            calls++;
            int next = seqByYmd.getOrDefault(ymd, 1);
            seqByYmd.put(ymd, next + 1);
            return next;
        }
    }
}
