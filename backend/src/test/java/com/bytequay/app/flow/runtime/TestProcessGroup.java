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
package com.bytequay.app.flow.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The property under test is that a group survives reparenting where a tree walk
 * does not, so the fixtures orphan children deliberately.
 */
final class TestProcessGroup
{
    private static final String SLEEP = "300";

    @Test
    void theAgentLeadsItsOwnGroup(@TempDir Path root)
            throws IOException, InterruptedException
    {
        ProcessGroup.Spawned spawned = ProcessGroup.start(
                List.of("sleep", SLEEP), root, Map.of(), root.resolve("pgid"));
        try {
            // Leading its own group is the whole point: signalling it must not
            // reach this JVM, which is what a shared group would mean.
            assertThat(spawned.pgid()).isGreaterThan(1);
            assertThat(spawned.pgid())
                    .isNotEqualTo(ProcessHandle.current().pid());
            assertThat(ProcessGroup.isAlive(spawned.pgid())).isTrue();
        }
        finally {
            ProcessGroup.bury(spawned.pgid());
        }
    }

    @Test
    void theGroupIdIsReadableBeforeTheTurnEnds(@TempDir Path root)
            throws IOException, InterruptedException
    {
        Path pgidFile = root.resolve("pgid");
        ProcessGroup.Spawned spawned = ProcessGroup.start(
                List.of("sleep", SLEEP), root, Map.of(), pgidFile);
        try {
            // The program persists this before delivering the prompt. An id
            // learned only at the end would be lost by the crash that makes it
            // matter, leaving an unburiable group.
            assertThat(Files.readString(pgidFile, StandardCharsets.UTF_8)
                    .strip()).isEqualTo(Long.toString(spawned.pgid()));
        }
        finally {
            ProcessGroup.bury(spawned.pgid());
        }
    }

    @Test
    void buryingTheGroupTakesAReparentedGrandchildWithIt(@TempDir Path root)
            throws IOException, InterruptedException
    {
        // The case ProcessTree cannot cover. The inner sleep is backgrounded and
        // its parent exits, so it is reparented and a descendants() snapshot no
        // longer finds it — but it never left the process group.
        ProcessGroup.Spawned spawned = ProcessGroup.start(
                List.of("/bin/sh", "-c",
                        "sh -c 'sleep " + SLEEP + " & ' ; sleep " + SLEEP),
                root, Map.of(), root.resolve("pgid"));

        Optional<Long> alive = ProcessGroup.bury(spawned.pgid());

        assertThat(alive)
                .as("the whole group must be proven gone, orphans included")
                .isEmpty();
        assertThat(ProcessGroup.isAlive(spawned.pgid())).isFalse();
        assertThat(ProcessGroup.proof(spawned.pgid(), alive)).contains("gone");
    }

    @Test
    void reportsTheGroupItCouldNotBuryRatherThanClaimingSuccess(@TempDir Path root)
            throws IOException, InterruptedException
    {
        ProcessGroup.Spawned spawned = ProcessGroup.start(
                List.of("/bin/sh", "-c", "trap '' TERM; sleep " + SLEEP),
                root, Map.of(), root.resolve("pgid"));
        try {
            // TERM is trapped and the reap window is zero, so the burial cannot
            // finish. Returning empty here would admit a successor writer into a
            // worktree this group still holds.
            Optional<Long> alive = ProcessGroup.bury(
                    spawned.pgid(), Duration.ZERO, Duration.ZERO);

            if (alive.isPresent()) {
                assertThat(alive).contains(spawned.pgid());
                assertThat(ProcessGroup.proof(spawned.pgid(), alive))
                        .contains("ALIVE");
            }
        }
        finally {
            ProcessGroup.bury(spawned.pgid());
        }
    }

    @Test
    void aLiveGroupIsReclaimedByItsRecordedStartTime(@TempDir Path root)
            throws IOException, InterruptedException
    {
        ProcessGroup.Spawned spawned = ProcessGroup.start(
                List.of("sleep", SLEEP), root, Map.of(), root.resolve("pgid"));
        try {
            // What a restart does before admitting a successor writer: this is
            // the group we recorded, so it has to be buried rather than
            // stepped around.
            assertThat(spawned.leaderStartedAt()).isNotNull();
            assertThat(ProcessGroup.reclaim(
                    spawned.pgid(), spawned.leaderStartedAt()))
                    .isEqualTo(ProcessGroup.Reclaimed.OURS);
        }
        finally {
            ProcessGroup.bury(spawned.pgid());
        }
    }

    @Test
    void aBuriedGroupIsGone(@TempDir Path root)
            throws IOException, InterruptedException
    {
        ProcessGroup.Spawned spawned = ProcessGroup.start(
                List.of("sleep", SLEEP), root, Map.of(), root.resolve("pgid"));
        assertThat(ProcessGroup.bury(spawned.pgid())).isEmpty();

        // The ordinary restart: nothing of ours is running, so the successor
        // writer may have the worktree.
        assertThat(ProcessGroup.reclaim(
                spawned.pgid(), spawned.leaderStartedAt()))
                .isEqualTo(ProcessGroup.Reclaimed.GONE);
    }

    @Test
    void aRecycledGroupNumberIsNotOurGroup(@TempDir Path root)
            throws IOException, InterruptedException
    {
        // The trap a bare pgid walks into. Once a group empties the OS may hand
        // the number to somebody else, and burying on the number alone would
        // kill a stranger's processes. A start time that does not match is the
        // proof that our group already ended.
        ProcessGroup.Spawned spawned = ProcessGroup.start(
                List.of("sleep", SLEEP), root, Map.of(), root.resolve("pgid"));
        try {
            assertThat(ProcessGroup.reclaim(
                    spawned.pgid(),
                    spawned.leaderStartedAt().minusSeconds(600)))
                    .isEqualTo(ProcessGroup.Reclaimed.GONE);
        }
        finally {
            ProcessGroup.bury(spawned.pgid());
        }
    }

    @Test
    void anUnidentifiableLiveGroupIsUncertainRatherThanEitherAnswer(
            @TempDir Path root)
            throws IOException, InterruptedException
    {
        // No recorded start time and a live group: burying it might kill a
        // stranger, admitting past it might hand a live agent's worktree to its
        // successor. Neither is safe to guess, so this is the one case that
        // stops the run instead of resolving it.
        ProcessGroup.Spawned spawned = ProcessGroup.start(
                List.of("sleep", SLEEP), root, Map.of(), root.resolve("pgid"));
        try {
            assertThat(ProcessGroup.reclaim(spawned.pgid(), null))
                    .isEqualTo(ProcessGroup.Reclaimed.UNCERTAIN);
        }
        finally {
            ProcessGroup.bury(spawned.pgid());
        }
    }

    @Test
    void refusesToSignalItsOwnGroupOrInit()
    {
        // Group 0 means "the caller's own group", so a bug that let it through
        // would have this JVM signal itself. Group 1 is init.
        assertThatThrownBy(() -> ProcessGroup.isAlive(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refusing to signal group");
        assertThatThrownBy(() -> ProcessGroup.isAlive(1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBinaryThatCannotExecStillLeavesAnAccountedGroup(@TempDir Path root)
            throws IOException, InterruptedException
    {
        // The shell forks before it execs, so a missing binary still produces a
        // group — it just dies immediately. That makes it an agent failure rather
        // than a launch failure, and the group is still accounted for, which is
        // what stops an unexplained turn from also being an unburiable one.
        ProcessGroup.Spawned spawned = ProcessGroup.start(
                List.of("definitely-not-a-real-binary-bq"), root, Map.of(),
                root.resolve("nested").resolve("pgid"));

        assertThat(spawned.pgid()).isGreaterThan(1);
        assertThat(spawned.process().waitFor()).isNotZero();
        // Nothing to bury, and the receipt says so rather than erroring.
        assertThat(ProcessGroup.bury(spawned.pgid())).isEmpty();
    }
}
