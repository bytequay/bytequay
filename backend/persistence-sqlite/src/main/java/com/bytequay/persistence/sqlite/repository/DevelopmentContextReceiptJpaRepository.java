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

import com.bytequay.persistence.sqlite.entity.DevelopmentContextReceiptEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DevelopmentContextReceiptJpaRepository
        extends JpaRepository<DevelopmentContextReceiptEntity, String>
{
    Optional<DevelopmentContextReceiptEntity> findByTaskIdAndTurnIdAndOperationKey(String taskId, String turnId, String operationKey);

    Optional<DevelopmentContextReceiptEntity> findByTaskIdAndTurnIdAndReference(String taskId, String turnId, String reference);

    List<DevelopmentContextReceiptEntity> findByTaskIdAndTurnIdOrderByCreatedAtAsc(String taskId, String turnId);

    @Query("select receipt from DevelopmentContextReceiptEntity receipt where receipt.taskId = :taskId order by receipt.createdAt desc, receipt.id desc")
    List<DevelopmentContextReceiptEntity> listTask(String taskId, Pageable pageable);

    @Query("select receipt.taskId from DevelopmentContextReceiptEntity receipt group by receipt.taskId order by max(receipt.createdAt) desc")
    List<String> listTaskIds(Pageable pageable);

    @Query("select coalesce(sum(receipt.payloadBytes), 0) from DevelopmentContextReceiptEntity receipt where receipt.taskId = :taskId")
    long taskBytes(String taskId);

    @Modifying
    @Query("DELETE FROM DevelopmentContextReceiptEntity receipt WHERE receipt.taskId = :taskId")
    void deleteByTaskId(String taskId);
}
