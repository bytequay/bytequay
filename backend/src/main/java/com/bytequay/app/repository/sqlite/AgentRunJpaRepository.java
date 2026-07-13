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

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface AgentRunJpaRepository
        extends JpaRepository<AgentRunEntity, String>
{
    /** Every run for a task, newest-first. {@code kind} / {@code
     *  parentStageId} / {@code status} narrowing happens in the store —
     *  the row count per task is small enough that filtering client-side
     *  beats a derived-query permutation for every optional-filter
     *  combination. */
    List<AgentRunEntity> findByTaskIdOrderByStartedAtMsDesc(String taskId);

    List<AgentRunEntity> findByReviewRoundIdOrderByStartedAtMsAsc(String reviewRoundId);
}
