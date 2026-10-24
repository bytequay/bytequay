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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.review.LocalRun;
import com.bytequay.core.review.ReviewConflictException;
import com.google.common.collect.ImmutableList;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteLocalRunStore
        extends AbstractTestTransactionalSqliteStore
{
    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00.123Z");
    private static final String HEAD = "3333333333333333333333333333333333333333";

    @Autowired
    private SqliteLocalRunStore store;

    @Autowired
    private EntityManager entityManager;

    @Test
    void roundTripsRunningAndFinishedRunsAndListsNewestFirst()
    {
        LocalRun first = store.insert(started("run-1", "task-1", 1, Optional.empty()));
        LocalRun second = store.insert(started("run-2", "task-1", 2, Optional.of("fails")));
        store.insert(started("run-3", "task-2", 1, Optional.empty()));
        entityManager.clear();

        assertThat(store.find("run-2")).contains(second);
        assertThat(store.listByTask("task-1", 200)).containsExactly(second, first);
        assertThat(store.listByTask("task-1", 1)).containsExactly(second);
        assertThat(store.listByTask("task-2", 200)).extracting(LocalRun::id).containsExactly("run-3");

        LocalRun.TestClass results = new LocalRun.TestClass("demo.TestCode", OptionalLong.of(12), ImmutableList.of(
                new LocalRun.TestMethod("passes", LocalRun.MethodStatus.PASSED, OptionalLong.of(2), Optional.empty(), Optional.empty()),
                new LocalRun.TestMethod("fails", LocalRun.MethodStatus.FAILED, OptionalLong.empty(), Optional.of("expected 1"), Optional.of("at demo.TestCode.fails"))));
        assertThat(store.findOutput("run-2")).isEmpty();
        LocalRun finished = store.update(second.finished(LocalRun.Status.FAILED_ASSERTION, NOW.plusSeconds(5), OptionalInt.of(1),
                Optional.of(new LocalRun.Counts(2, 1, 0, 0)), ImmutableList.of(results), true, OptionalLong.of(3))
                .withTreeUnverified(true), "head\n… 3 chars of output omitted …\ntail");
        entityManager.clear();

        assertThat(finished.version()).isEqualTo(1);
        assertThat(store.find("run-2")).contains(finished);
        assertThat(store.findOutput("run-2")).contains("head\n… 3 chars of output omitted …\ntail");
        assertThatThrownBy(() -> store.update(second)).isInstanceOf(ReviewConflictException.class).hasMessageContaining("version changed");
        // A run still running and a finished one whose processes were not verified stopped are both left for startup to settle.
        assertThat(store.listUnsettled()).extracting(LocalRun::id).containsExactlyInAnyOrder("run-1", "run-2", "run-3");

        LocalRun verified = store.update(finished.withTreeUnverified(false), "replaced");
        entityManager.clear();

        assertThat(verified.version()).isEqualTo(2);
        assertThat(store.findOutput("run-2")).contains("replaced");
        assertThat(store.listUnsettled()).extracting(LocalRun::id).containsExactlyInAnyOrder("run-1", "run-3");
        assertThatThrownBy(() -> store.insert(first)).isInstanceOf(ReviewConflictException.class).hasMessageContaining("already exists");
    }

    private static LocalRun started(String id, String taskId, int ordinal, Optional<String> method)
    {
        return LocalRun.started(id, taskId, "workspace-1", ordinal, "core/src/test/java/demo/TestCode.java", "demo.TestCode", method, Optional.of("core"),
                "./mvnw -B -pl core -Dtest=demo.TestCode -Dsurefire.failIfNoSpecifiedTests=false test", HEAD, ImmutableList.of("core/src/test/java/demo/TestNew.java"),
                "fingerprint", "launch-" + id, NOW);
    }
}
