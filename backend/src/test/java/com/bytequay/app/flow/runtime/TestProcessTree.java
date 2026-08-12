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

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failure this guards against — a leftover grandchild still writing to a
 * worktree the next agent has been handed — does not reproduce without actually
 * forking one, so these tests fork.
 */
final class TestProcessTree
{
    /** Long enough that nothing exits on its own and passes the test by luck. */
    private static final String SLEEP = "300";

    @Test
    void buriesAGrandchildThatItsDirectChildOrphaned()
            throws IOException, InterruptedException
    {
        // sh -> sh -> sleep, with the middle shell backgrounding the sleep so it
        // is reparented the moment the middle dies. destroyForcibly() on the
        // root leaves that sleep running; this is the case it exists for.
        Process root = new ProcessBuilder(
                "/bin/sh", "-c", "sh -c 'sleep " + SLEEP + "' & sleep " + SLEEP)
                .start();
        awaitDescendants(root, 2);

        ProcessTree.Snapshot snapshot = ProcessTree.capture(root);
        assertThat(snapshot.size()).isGreaterThanOrEqualTo(3);
        List<Long> pids = snapshot.members().stream()
                .map(ProcessTree.Member::pid).toList();
        assertThat(pids).contains(root.pid());

        Optional<ProcessTree.Member> alive = ProcessTree.burySync(snapshot);

        assertThat(alive).as("every recorded process must be proven gone").isEmpty();
        for (long pid : pids) {
            assertThat(ProcessHandle.of(pid).filter(ProcessHandle::isAlive))
                    .as("pid %s survived the burial", pid)
                    .isEmpty();
        }
        assertThat(ProcessTree.proof(snapshot, alive)).contains("all-dead");
    }

    @Test
    void destroyForciblyAloneLeavesTheGrandchildRunning()
            throws IOException, InterruptedException
    {
        // The premise, pinned: if this ever stops being true the class above is
        // unnecessary, and we should find that out from a red test rather than
        // by carrying it forever.
        Process root = new ProcessBuilder(
                "/bin/sh", "-c", "sh -c 'sleep " + SLEEP + "' & sleep " + SLEEP)
                .start();
        awaitDescendants(root, 2);
        ProcessTree.Snapshot snapshot = ProcessTree.capture(root);

        root.destroyForcibly();
        assertThat(root.waitFor(10, TimeUnit.SECONDS)).isTrue();

        Optional<ProcessTree.Member> survivor = ProcessTree.firstAlive(snapshot);
        assertThat(survivor)
                .as("killing only the direct child should orphan its grandchild")
                .isPresent();

        // Clean up what the premise proved is still running.
        assertThat(ProcessTree.burySync(snapshot)).isEmpty();
    }

    @Test
    void reportsWhatIsStillAliveRatherThanClaimingSuccess()
            throws IOException
    {
        Process survivor = new ProcessBuilder("sleep", SLEEP).start();
        try {
            ProcessTree.Snapshot snapshot = new ProcessTree.Snapshot(
                    survivor.pid(),
                    List.of(new ProcessTree.Member(
                            survivor.pid(),
                            survivor.info().startInstant().orElse(null))));

            // A grace and reap window too short to kill anything: the point is
            // that a failed burial reports the survivor instead of returning
            // empty, because empty is what admits the next writer.
            Optional<ProcessTree.Member> alive = ProcessTree.burySync(
                    snapshot, Duration.ZERO, Duration.ZERO);

            if (alive.isPresent()) {
                assertThat(alive.get().pid()).isEqualTo(survivor.pid());
                assertThat(ProcessTree.proof(snapshot, alive))
                        .contains("ALIVE=" + survivor.pid());
            }
            else {
                // A zero-window kill can still land. Then the receipt must be
                // honest in the other direction.
                assertThat(ProcessHandle.of(survivor.pid())
                        .filter(ProcessHandle::isAlive)).isEmpty();
            }
        }
        finally {
            survivor.destroyForcibly();
        }
    }

    @Test
    void treatsARecycledPidAsGoneRatherThanAsStillAlive()
    {
        // This JVM is certainly alive, but recorded under a start time that is
        // not its own — so it is a different incarnation of that pid, and the
        // process we recorded is gone. Without this check a recycled pid would
        // report the dead agent as alive forever and wedge the Task.
        ProcessTree.Snapshot recycled = new ProcessTree.Snapshot(
                ProcessHandle.current().pid(),
                List.of(new ProcessTree.Member(
                        ProcessHandle.current().pid(),
                        Instant.ofEpochMilli(1))));

        assertThat(ProcessTree.firstAlive(recycled)).isEmpty();
    }

    @Test
    void countsTheSameProcessAsAliveWhenItsStartTimeMatches()
    {
        ProcessTree.Snapshot self = ProcessTree.capture(ProcessHandle.current());

        // The mirror of the test above: an exact match must not be dismissed as
        // a recycled pid, or a burial would "succeed" against a live agent.
        assertThat(ProcessTree.firstAlive(self))
                .map(ProcessTree.Member::pid)
                .contains(ProcessHandle.current().pid());
    }

    private static void awaitDescendants(Process root, int expected)
            throws InterruptedException
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (root.descendants().count() < expected
                && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(root.descendants().count())
                .as("the fixture never started its own children")
                .isGreaterThanOrEqualTo(expected);
    }
}
