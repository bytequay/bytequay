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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextUsageEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

/** Hibernate owns the compact usage projection schema; immutable source receipts stay elsewhere. */
@Entity
@Table(name = "context_usage_fact", indexes = {
        @Index(name = "idx_context_usage_workspace_time", columnList = "workspace_id, occurred_at_ms"),
        @Index(name = "idx_context_usage_workspace_entry", columnList = "workspace_id, entry_id"),
        @Index(name = "idx_context_usage_task", columnList = "workspace_id, task_id, occurred_at_ms")})
public class ContextUsageFactEntity
{
    @Id
    @Getter
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "turn_id")
    private String turnId;

    @Column(name = "review_run_id")
    private String reviewRunId;

    @Column(name = "task_kind", nullable = false)
    @Enumerated(EnumType.STRING)
    private ContextUsageEvent.TaskKind taskKind;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ContextUsageEvent.Stage stage;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ContextUsageEvent.Delivery delivery;

    @Column(name = "receipt_ref", nullable = false, length = 500)
    private String receiptRef;

    @Column(name = "entry_id", length = 1200)
    private String entryId;

    @Column(name = "entry_version")
    private Long entryVersion;

    @Column(name = "entry_kind")
    @Enumerated(EnumType.STRING)
    private ContextEntryKind entryKind;

    @Column(name = "scope_id", length = 1100)
    private String scopeId;

    @Column(name = "entry_title", length = 200)
    private String entryTitle;

    @Column(name = "observed_preparation")
    @Enumerated(EnumType.STRING)
    private ContextUsageEvent.Preparation observedPreparation;

    @Column(name = "context_enabled")
    private Boolean contextEnabled;

    @Column(name = "occurred_at_ms", nullable = false)
    private long occurredAtMillis;

    @Column(name = "payload_digest", nullable = false)
    private String payloadDigest;

    public ContextUsageFactEntity() {}
}
