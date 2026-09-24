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
package com.bytequay.core.context;

import java.util.List;
import java.util.Optional;

/** Task-owned bounded context responses; an operation key is unique within a turn. */
public interface DevelopmentContextReceiptStore
{
    Optional<DevelopmentContextReceipt> find(String taskId, String turnId, String operationKey);

    Optional<DevelopmentContextReceipt> findReference(String taskId, String turnId, String reference);

    List<DevelopmentContextReceipt> listTurn(String taskId, String turnId);

    /** Latest task receipts across turns, with a caller-supplied display bound. */
    List<DevelopmentContextReceipt> listTask(String taskId, int limit);

    /** Most recently active receipt-owning tasks for bounded delivery replay after a restart. */
    List<String> listTaskIds(int limit);

    long taskBytes(String taskId);

    void insert(DevelopmentContextReceipt receipt);

    void deleteTask(String taskId);
}
