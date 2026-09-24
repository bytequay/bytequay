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

import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.DevelopmentContextReceipt;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** One immutable response keyed by accepted task turn and operation. */
@Entity
@Table(name = "development_context_receipt", indexes = @Index(name = "idx_development_context_task", columnList = "task_id"),
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_development_context_operation", columnNames = {"task_id", "turn_id", "operation_key"}),
                @UniqueConstraint(name = "uq_development_context_reference", columnNames = {"task_id", "turn_id", "reference"})})
public class DevelopmentContextReceiptEntity
{
    @Id
    private String id;

    @Column(nullable = false)
    private String taskId;

    @Column(nullable = false)
    private String turnId;

    @Column(nullable = false)
    private String operationKey;

    @Column(nullable = false)
    private String reference;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private DevelopmentContextReceipt.Kind kind;

    @Column(nullable = false)
    private String requestDigest;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(nullable = false)
    private int payloadBytes;

    @Column(nullable = false)
    private String payloadDigest;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    protected DevelopmentContextReceiptEntity() {}

    public DevelopmentContextReceiptEntity(DevelopmentContextReceipt receipt)
    {
        id = receipt.taskId() + ":" + receipt.turnId() + ":" + receipt.operationKey();
        taskId = receipt.taskId();
        turnId = receipt.turnId();
        operationKey = receipt.operationKey();
        reference = receipt.reference();
        kind = receipt.kind();
        requestDigest = receipt.requestDigest();
        payloadJson = receipt.payloadJson();
        payloadBytes = ContextLimits.bytes(payloadJson);
        payloadDigest = receipt.payloadDigest();
        createdAt = receipt.createdAt();
    }

    public DevelopmentContextReceipt toDomain()
    {
        return new DevelopmentContextReceipt(taskId, turnId, operationKey, reference, kind, requestDigest, payloadJson, payloadDigest, createdAt);
    }
}
