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
package com.bytequay.app.developmentflow.persistence;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.bytequay.app.testing.SqliteTestPools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SqliteTestPools.class)
class TestSqliteWorktreeWriterLeaseStore
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void acquiresHeartbeatsAndReleasesOnlyTheExactFence()
    {
        Fixture fixture = fixture("exact.db", NOW, "first");
        WorktreeWriterLeaseManager.Lease first = lease(
                fixture.capacity(), "/tmp/task", NOW);

        assertThat(fixture.store().tryAcquire(first, NOW)).contains(first);
        assertThat(fixture.store().findExact(first, NOW)).contains(first);
        assertThat(fixture.store().tryAcquire(
                copy(first, "/tmp/other", "first"), NOW)).isEmpty();

        CapacityManager.CapacityLease renewedCapacity = fixture.capacityStore()
                .heartbeat(
                        fixture.capacity().id(), "first",
                        NOW.plusSeconds(5), NOW.plusSeconds(35))
                .orElseThrow();
        WorktreeWriterLeaseManager.Lease renewed = fixture.store().heartbeat(
                        first, NOW.plusSeconds(5), renewedCapacity.expiresAt())
                .orElseThrow();
        assertThat(renewed.expiresAt()).isEqualTo(NOW.plusSeconds(35));

        WorktreeWriterLeaseManager.Lease stale = copy(first, "/tmp/task", "stale");
        assertThat(fixture.store().release(stale, NOW.plusSeconds(6))).isFalse();
        assertThat(fixture.store().findExact(renewed, NOW.plusSeconds(6)))
                .contains(renewed);
        assertThat(fixture.store().release(renewed, NOW.plusSeconds(7))).isTrue();
        assertThat(fixture.store().findExact(renewed, NOW.plusSeconds(7))).isEmpty();
    }

    @Test
    void reapsOnlyAWriterWhoseCapacityProofIsNoLongerLive()
    {
        Fixture first = fixture("reap.db", NOW, "first");
        WorktreeWriterLeaseManager.Lease expired = lease(
                first.capacity(), "/tmp/task", NOW);
        assertThat(first.store().tryAcquire(expired, NOW)).contains(expired);

        Instant later = NOW.plusSeconds(31);
        first.capacityStore().expire(later);
        CapacityManager replacementManager = manager(first.capacityStore(), later);
        CapacityManager.CapacityLease replacementCapacity = replacementManager
                .tryAcquireForTicket(
                        first.ticket().id(),
                        first.ticket().envelope().capacityRequest(),
                        "replacement")
                .lease().orElseThrow();
        WorktreeWriterLeaseManager.Lease replacement = lease(
                replacementCapacity, "/tmp/task", later);

        assertThat(first.store().tryAcquire(replacement, later)).contains(replacement);
        assertThat(first.store().release(expired, later.plusSeconds(1))).isFalse();
        assertThat(first.store().findExact(replacement, later.plusSeconds(1)))
                .contains(replacement);
        assertThat(replacement.fencingToken()).isEqualTo(2);
    }

    @Test
    void surfacesAnInvalidCapacityFenceInsteadOfCallingItContention()
    {
        Fixture fixture = fixture("invalid.db", NOW, "first");
        WorktreeWriterLeaseManager.Lease invalid = new WorktreeWriterLeaseManager.Lease(
                "/tmp/task", "task", "wrong-operation", 1,
                fixture.capacity().writerFencingToken(), "first",
                NOW, fixture.capacity().expiresAt());

        assertThatThrownBy(() -> fixture.store().tryAcquire(invalid, NOW))
                .isInstanceOf(DataAccessException.class);
    }

    private Fixture fixture(String file, Instant now, String owner)
    {
        SqliteExecutionTestSupport.Database database =
                SqliteExecutionTestSupport.database(tempDir.resolve(file));
        SqliteExecutionTestSupport.seedTrunk(database, "workspace", "trunk");
        SqliteExecutionTestSupport.seedTask(database, "trunk", "task", 1);
        database.jdbc().update("""
                UPDATE provision_task_operation
                SET status = 'DISPATCHED'
                WHERE task_id = 'task'
                """);
        DispatchTicket ticket = new SqliteDispatchTicketStore(database.dataSource())
                .findById("provision-ticket-task")
                .orElseThrow();
        SqliteCapacityLeaseStore capacityStore = new SqliteCapacityLeaseStore(
                database.dataSource());
        CapacityManager.CapacityLease capacity = manager(capacityStore, now)
                .tryAcquireForTicket(
                        ticket.id(), ticket.envelope().capacityRequest(), owner)
                .lease().orElseThrow();
        return new Fixture(
                database,
                capacityStore,
                new SqliteWorktreeWriterLeaseStore(database.dataSource()),
                ticket,
                capacity);
    }

    private static CapacityManager manager(
            SqliteCapacityLeaseStore store, Instant now)
    {
        return new CapacityManager(
                store,
                () -> CapacityManager.CapacityPolicy.initial(
                        4, 4, Map.of(LOCAL_GIT, 4)),
                Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofSeconds(30));
    }

    private static WorktreeWriterLeaseManager.Lease lease(
            CapacityManager.CapacityLease capacity,
            String path,
            Instant acquiredAt)
    {
        return new WorktreeWriterLeaseManager.Lease(
                path,
                capacity.scope().taskId(),
                capacity.operationId(),
                capacity.scope().taskEpoch(),
                capacity.writerFencingToken(),
                capacity.leaseOwner(),
                acquiredAt,
                capacity.expiresAt());
    }

    private static WorktreeWriterLeaseManager.Lease copy(
            WorktreeWriterLeaseManager.Lease source,
            String path,
            String owner)
    {
        return new WorktreeWriterLeaseManager.Lease(
                path, source.taskId(), source.operationId(), source.taskEpoch(),
                source.fencingToken(), owner, source.acquiredAt(), source.expiresAt());
    }

    private record Fixture(
            SqliteExecutionTestSupport.Database database,
            SqliteCapacityLeaseStore capacityStore,
            SqliteWorktreeWriterLeaseStore store,
            DispatchTicket ticket,
            CapacityManager.CapacityLease capacity) {}

}
