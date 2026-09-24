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
package com.bytequay.persistence.sqlite.repository;

import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.persistence.sqlite.entity.ContextUsageFactEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.stream.Stream;

public interface ContextUsageFactJpaRepository
        extends JpaRepository<ContextUsageFactEntity, String>
{
    // A scalar projection keeps usage scans bounded and observes bulk delivery updates in an outer transaction.
    String FIELDS = "SELECT f.workspaceId AS workspaceId, f.taskId AS taskId, f.turnId AS turnId, f.reviewRunId AS reviewRunId, "
            + "f.taskKind AS taskKind, f.stage AS stage, f.delivery AS delivery, f.receiptRef AS receiptRef, "
            + "f.entryId AS entryId, f.entryVersion AS entryVersion, f.entryKind AS entryKind, f.scopeId AS scopeId, "
            + "f.entryTitle AS entryTitle, f.observedPreparation AS observedPreparation, f.contextEnabled AS contextEnabled, "
            + "f.occurredAtMillis AS occurredAtMillis FROM ContextUsageFactEntity f";

    // Receipt insertion must remain one SQLite statement: findById/save races with concurrent task workers.
    @Modifying
    @Query(value = """
            INSERT INTO context_usage_fact (id, workspace_id, task_id, turn_id, review_run_id, task_kind, stage, delivery, receipt_ref,
                entry_id, entry_version, entry_kind, scope_id, entry_title, observed_preparation, context_enabled, occurred_at_ms, payload_digest)
            VALUES (:#{#fact.id}, :#{#fact.workspaceId}, :#{#fact.taskId}, :#{#fact.turnId}, :#{#fact.reviewRunId},
                :#{#fact.taskKindName}, :#{#fact.stageName}, :#{#fact.deliveryName}, :#{#fact.receiptRef},
                :#{#fact.entryId}, :#{#fact.entryVersion}, :#{#fact.entryKindName}, :#{#fact.scopeId}, :#{#fact.entryTitle},
                :#{#fact.observedPreparationName}, :#{#fact.contextEnabled}, :#{#fact.occurredAtMillis}, :#{#fact.payloadDigest})
            ON CONFLICT (id) DO UPDATE SET delivery = CASE
                WHEN context_usage_fact.delivery = 'CONFIRMED' OR excluded.delivery = 'PREPARED' THEN context_usage_fact.delivery
                ELSE excluded.delivery END
            WHERE context_usage_fact.payload_digest = excluded.payload_digest
            """, nativeQuery = true)
    int insertOrPromote(@Param("fact") ContextUsageFactEntity fact);

    @Modifying
    @Query("UPDATE ContextUsageFactEntity f SET f.delivery = :value WHERE f.workspaceId = :workspaceId "
            + "AND f.receiptRef = :receiptRef AND f.stage = :stage AND f.delivery <> :confirmed")
    void updateDelivery(String workspaceId, String receiptRef, ContextUsageEvent.Stage stage,
            ContextUsageEvent.Delivery value, ContextUsageEvent.Delivery confirmed);

    @Query(FIELDS + " WHERE f.workspaceId = :workspaceId AND f.taskId = :taskId ORDER BY f.occurredAtMillis DESC, f.id DESC")
    List<Fact> listTask(String workspaceId, String taskId, Pageable page);

    @Query(FIELDS + " WHERE f.workspaceId = :workspaceId ORDER BY f.occurredAtMillis, f.id")
    Stream<Fact> streamWorkspace(String workspaceId);

    @Query(FIELDS + " WHERE f.workspaceId = :workspaceId AND f.occurredAtMillis >= :since ORDER BY f.occurredAtMillis, f.id")
    Stream<Fact> streamWorkspaceSince(String workspaceId, long since);

    @Query(FIELDS + " WHERE f.workspaceId = :workspaceId AND f.reviewRunId = :reviewRunId AND f.entryId = :entryId "
            + "AND f.entryVersion = :version AND f.stage = :stage")
    List<Fact> selectedNote(String workspaceId, String reviewRunId, String entryId, long version, ContextUsageEvent.Stage stage, Pageable page);

    @Query(FIELDS + " WHERE f.workspaceId = :workspaceId AND f.entryId = :entryId ORDER BY f.occurredAtMillis DESC, f.id DESC")
    List<Fact> activities(String workspaceId, String entryId, Pageable page);

    @Query(FIELDS + " WHERE f.workspaceId = :workspaceId AND f.entryId = :entryId AND f.occurredAtMillis >= :since "
            + "ORDER BY f.occurredAtMillis DESC, f.id DESC")
    List<Fact> activitiesSince(String workspaceId, String entryId, long since, Pageable page);

    @Modifying
    @Query("DELETE FROM ContextUsageFactEntity f WHERE f.taskId = :taskId")
    void deleteByTaskId(String taskId);

    interface Fact
    {
        String getWorkspaceId();

        String getTaskId();

        String getTurnId();

        String getReviewRunId();

        ContextUsageEvent.TaskKind getTaskKind();

        ContextUsageEvent.Stage getStage();

        ContextUsageEvent.Delivery getDelivery();

        String getReceiptRef();

        String getEntryId();

        Long getEntryVersion();

        ContextEntryKind getEntryKind();

        String getScopeId();

        String getEntryTitle();

        ContextUsageEvent.Preparation getObservedPreparation();

        Boolean getContextEnabled();

        long getOccurredAtMillis();
    }
}
