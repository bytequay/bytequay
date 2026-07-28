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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.WorktreeLease;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSqliteWorktreeLeaseStore
{
    @Test
    void legacyListingAndReleaseNeverSelectV2Rows()
    {
        WorktreeLeaseJpaRepository repository = mock(WorktreeLeaseJpaRepository.class);
        WorktreeLeaseEntity legacy = entity("/tmp/legacy", "task-1", "LEGACY");
        when(repository.findByTaskIdAndWorkflowVersion("task-1", "LEGACY"))
                .thenReturn(List.of(legacy));
        when(repository.findByWorkflowVersion("LEGACY")).thenReturn(List.of(legacy));
        SqliteWorktreeLeaseStore store = new SqliteWorktreeLeaseStore(repository);

        assertThat(store.listForTask("task-1"))
                .extracting(WorktreeLease::worktreePath)
                .containsExactly("/tmp/legacy");
        assertThat(store.listAll())
                .extracting(WorktreeLease::worktreePath)
                .containsExactly("/tmp/legacy");

        store.releaseByWorktreePath("/tmp/v2");

        verify(repository).deleteByWorktreePathAndWorkflowVersion(
                "/tmp/v2", "LEGACY");
        verify(repository, never()).deleteById("/tmp/v2");
    }

    @Test
    void legacySaveCannotRewriteV2Lease()
    {
        WorktreeLeaseJpaRepository repository = mock(WorktreeLeaseJpaRepository.class);
        WorktreeLeaseEntity v2 = entity("/tmp/shared", "task-1", "V2");
        when(repository.findById("/tmp/shared")).thenReturn(Optional.of(v2));
        SqliteWorktreeLeaseStore store = new SqliteWorktreeLeaseStore(repository);
        WorktreeLease legacyRequest = new WorktreeLease(
                "/tmp/shared",
                "task-1",
                ThreadKind.LOGIC_LOOP,
                null,
                Instant.parse("2026-07-28T00:00:00Z"),
                null);

        assertThatThrownBy(() -> store.save(legacyRequest))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("V2 writer boundary");
        verify(repository, never()).save(v2);
    }

    private static WorktreeLeaseEntity entity(
            String path,
            String taskId,
            String workflowVersion)
    {
        WorktreeLeaseEntity entity = new WorktreeLeaseEntity();
        entity.setWorktreePath(path);
        entity.setTaskId(taskId);
        entity.setAgentKind(ThreadKind.LOGIC_LOOP.name());
        entity.setAcquiredAtMs(Instant.parse("2026-07-28T00:00:00Z").toEpochMilli());
        entity.setWorkflowVersion(workflowVersion);
        return entity;
    }
}
