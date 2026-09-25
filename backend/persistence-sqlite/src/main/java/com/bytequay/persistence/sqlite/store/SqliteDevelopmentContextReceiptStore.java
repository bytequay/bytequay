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

import com.bytequay.core.context.DevelopmentContextReceipt;
import com.bytequay.core.context.DevelopmentContextReceiptStore;
import com.bytequay.persistence.sqlite.entity.DevelopmentContextReceiptEntity;
import com.bytequay.persistence.sqlite.repository.DevelopmentContextReceiptJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteDevelopmentContextReceiptStore
        implements DevelopmentContextReceiptStore
{
    private final DevelopmentContextReceiptJpaRepository receipts;

    public SqliteDevelopmentContextReceiptStore(DevelopmentContextReceiptJpaRepository receipts)
    {
        this.receipts = requireNonNull(receipts, "receipts is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DevelopmentContextReceipt> find(String taskId, String turnId, String operationKey)
    {
        return receipts.findByTaskIdAndTurnIdAndOperationKey(taskId, turnId, operationKey).map(DevelopmentContextReceiptEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DevelopmentContextReceipt> findReference(String taskId, String turnId, String reference)
    {
        return receipts.findByTaskIdAndTurnIdAndReference(taskId, turnId, reference).map(DevelopmentContextReceiptEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DevelopmentContextReceipt> listTurn(String taskId, String turnId)
    {
        return receipts.findByTaskIdAndTurnIdOrderByCreatedAtAsc(taskId, turnId).stream()
                .map(DevelopmentContextReceiptEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DevelopmentContextReceipt> listTask(String taskId, int limit)
    {
        checkArgument(limit >= 1 && limit <= 101, "Receipt display limit is out of range");
        return receipts.listTask(taskId, PageRequest.of(0, limit)).stream()
                .map(DevelopmentContextReceiptEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> listTaskIds(int limit)
    {
        return receipts.listTaskIds(PageRequest.of(0, limit));
    }

    @Override
    @Transactional(readOnly = true)
    public long taskBytes(String taskId)
    {
        return receipts.taskBytes(taskId);
    }

    @Override
    @Transactional
    public void insert(DevelopmentContextReceipt receipt)
    {
        receipts.saveAndFlush(new DevelopmentContextReceiptEntity(receipt));
    }

    @Override
    @Transactional
    public void deleteTask(String taskId)
    {
        receipts.deleteByTaskId(taskId);
    }
}
