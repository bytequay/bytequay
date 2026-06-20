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
import java.util.Optional;

interface TaskStageJpaRepository
        extends JpaRepository<TaskStageEntity, String>
{
    /** A task's stages oldest-first. */
    List<TaskStageEntity> findByTaskIdOrderByOpenedAtMsAsc(String taskId);

    /** Latest stage in one of the given states (e.g. the OPEN/ACTIVE pair
     *  for "the currently active stage"). */
    Optional<TaskStageEntity> findFirstByTaskIdAndStateInOrderByOpenedAtMsDesc(
            String taskId, List<String> states);

    /** Latest stage of a given type in a given state. */
    Optional<TaskStageEntity> findFirstByTaskIdAndStageTypeAndStateOrderByOpenedAtMsDesc(
            String taskId, String stageType, String state);
}
