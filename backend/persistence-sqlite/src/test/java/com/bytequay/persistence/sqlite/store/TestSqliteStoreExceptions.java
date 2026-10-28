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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.core.cherrypick.CherryPickConflictException;
import com.bytequay.core.rebase.BranchRebaseException;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.store.StorageException;
import com.bytequay.persistence.sqlite.entity.PrReviewDraftEntity;
import com.bytequay.persistence.sqlite.entity.TaskDiscussionEntity;
import com.bytequay.persistence.sqlite.repository.PrReviewDraftJpaRepository;
import com.bytequay.persistence.sqlite.repository.TaskDiscussionJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import static com.bytequay.persistence.sqlite.store.SqliteStoreExceptions.mapConstraint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSqliteStoreExceptions
{
    @Test
    void reportsADuplicateKeyAsTheCallersConflict()
    {
        assertThat(mapConstraint("Task already exists: t1", rejected("[SQLITE_CONSTRAINT_PRIMARYKEY] A PRIMARY KEY constraint failed (UNIQUE constraint failed: agent_task.id)")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessage("Task already exists: t1");
    }

    @Test
    void reportsARowTheSchemaRejectsWithSqlitesReasonNotAConflict()
    {
        assertThat(mapConstraint("Task already exists: t1", rejected("[SQLITE_CONSTRAINT_CHECK] A CHECK constraint failed (CHECK constraint failed: state in ('CREATED'))")))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("CHECK constraint failed: state in ('CREATED')")
                .extracting(Throwable::getMessage).asString().doesNotContain("already exists");
    }

    @Test
    void reviewSchemaFailureDoesNotClaimThatTheReviewAlreadyExists()
    {
        assertThat(StoreWrites.REVIEW.rejectedInsert().apply("Review already exists: r1",
                rejected("[SQLITE_CONSTRAINT_NOTNULL] A NOT NULL constraint failed (NOT NULL constraint failed: agent_review.number)")))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("NOT NULL constraint failed: agent_review.number")
                .extracting(Throwable::getMessage).asString().doesNotContain("already exists");
    }

    @Test
    void reviewDuplicateStillReportsAConflict()
    {
        assertThat(StoreWrites.REVIEW.rejectedInsert().apply("Review already exists: r1",
                rejected("[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed (UNIQUE constraint failed: agent_review.task_id)")))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessage("Review already exists: r1");
    }

    @Test
    void reviewDatabaseFailureDoesNotClaimThatTheReviewAlreadyExists()
    {
        assertThat(StoreWrites.REVIEW.rejectedInsert().apply("Review already exists: r1", rejected("[SQLITE_BUSY] database is locked")))
                .isInstanceOf(StorageException.class)
                .hasMessage("Database write failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[SQLITE_BUSY] database is locked", "[SQLITE_CONSTRAINT_NOTNULL] missing value"})
    void draftStorageFailuresDoNotBecomeConflicts(String reason)
    {
        PrReviewDraftJpaRepository repository = mock(PrReviewDraftJpaRepository.class);
        DataIntegrityViolationException failure = rejected(reason);
        when(repository.saveAndFlush(any(PrReviewDraftEntity.class))).thenThrow(failure);

        assertThatThrownBy(() -> new SqliteReviewDraftStore(repository, mock(AgentReviewStore.class)).save(ReviewDraft.empty("owner/repo#1", "owner/repo", 1, Instant.EPOCH)))
                .isInstanceOf(StorageException.class)
                .hasCause(failure);
    }

    @Test
    void draftDuplicateRemainsAConflict()
    {
        PrReviewDraftJpaRepository repository = mock(PrReviewDraftJpaRepository.class);
        when(repository.saveAndFlush(any(PrReviewDraftEntity.class))).thenThrow(rejected("[SQLITE_CONSTRAINT_PRIMARYKEY] duplicate draft"));

        assertThatThrownBy(() -> new SqliteReviewDraftStore(repository, mock(AgentReviewStore.class)).save(ReviewDraft.empty("owner/repo#1", "owner/repo", 1, Instant.EPOCH)))
                .isInstanceOf(ReviewConflictException.class);
    }

    @ParameterizedTest
    @MethodSource("workflowFailures")
    void workflowWritesDistinguishStorageFailuresAndConflicts(StoreWrites.Failures failures, Class<? extends RuntimeException> conflictType)
    {
        TaskDiscussionJpaRepository repository = mock(TaskDiscussionJpaRepository.class);
        TaskDiscussionEntity entity = new TaskDiscussionEntity();
        doThrow(rejected("[SQLITE_BUSY] database is locked")).when(repository).saveAndFlush(entity);
        assertThatThrownBy(() -> StoreWrites.insert(repository, "id", "Workflow", () -> entity, failures))
                .isInstanceOf(StorageException.class).hasMessage("Database write failed");
        assertThatThrownBy(() -> StoreWrites.createOrGet(repository, "id", "Workflow", Optional::empty, () -> entity, failures))
                .isInstanceOf(StorageException.class).hasMessage("Database write failed");

        doThrow(rejected("[SQLITE_CONSTRAINT_CHECK] invalid row")).when(repository).saveAndFlush(entity);
        assertThatThrownBy(() -> StoreWrites.createOrGet(repository, "id", "Workflow", Optional::empty, () -> entity, failures))
                .isInstanceOf(StorageException.class).hasMessageContaining("invalid row");

        doThrow(rejected("[SQLITE_CONSTRAINT_UNIQUE] duplicate key")).when(repository).saveAndFlush(entity);
        assertThatThrownBy(() -> StoreWrites.createOrGet(repository, "id", "Workflow", Optional::empty, () -> entity, failures))
                .isInstanceOf(conflictType).hasMessageContaining("conflicts with durable state");
    }

    @Test
    void createOrGetReturnsTheConcurrentWinner()
    {
        TaskDiscussionJpaRepository repository = mock(TaskDiscussionJpaRepository.class);
        TaskDiscussionEntity winner = new TaskDiscussionEntity();
        when(repository.findById("id")).thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.saveAndFlush(any(TaskDiscussionEntity.class))).thenThrow(rejected("[SQLITE_CONSTRAINT_UNIQUE] duplicate key"));

        assertThat(StoreWrites.createOrGet(repository, "id", "Discussion", () -> repository.findById("id"), TaskDiscussionEntity::new, StoreWrites.AGENT))
                .isSameAs(winner);
    }

    @Test
    void createOrGetKeepsAgentStorageFailuresInTheAgentExceptionFamily()
    {
        TaskDiscussionJpaRepository repository = mock(TaskDiscussionJpaRepository.class);
        when(repository.saveAndFlush(any(TaskDiscussionEntity.class))).thenThrow(rejected("[SQLITE_BUSY] database is locked"));

        assertThatThrownBy(() -> StoreWrites.createOrGet(repository, "id", "Discussion", Optional::empty, TaskDiscussionEntity::new, StoreWrites.AGENT))
                .isInstanceOf(AgentStorageException.class).hasMessage("Database write failed");
    }

    private static Stream<Arguments> workflowFailures()
    {
        return Stream.of(arguments(StoreWrites.BRANCH_REBASE, BranchRebaseException.class), arguments(StoreWrites.CHERRY_PICK, CherryPickConflictException.class));
    }

    private static DataIntegrityViolationException rejected(String sqliteMessage)
    {
        return new DataIntegrityViolationException("could not execute statement", new SQLException(sqliteMessage));
    }
}
