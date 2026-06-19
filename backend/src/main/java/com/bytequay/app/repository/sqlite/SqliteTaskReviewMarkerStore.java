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

import com.bytequay.app.repository.TaskReviewMarkerStore;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteTaskReviewMarkerStore
        implements TaskReviewMarkerStore
{
    private final TaskReviewMarkerJpaRepository jpaRepository;

    public SqliteTaskReviewMarkerStore(TaskReviewMarkerJpaRepository jpaRepository)
    {
        this.jpaRepository = requireNonNull(jpaRepository, "jpaRepository is null");
    }

    @Override
    public Optional<Instant> find(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return jpaRepository.findById(taskId)
                .map(TaskReviewMarkerEntity::getLastAddressedReviewAt);
    }

    @Override
    public void mark(String taskId, Instant addressedThrough)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(addressedThrough, "addressedThrough is null");
        TaskReviewMarkerEntity entity = jpaRepository.findById(taskId)
                .orElseGet(() -> {
                    TaskReviewMarkerEntity fresh = new TaskReviewMarkerEntity();
                    fresh.setTaskId(taskId);
                    return fresh;
                });
        entity.setLastAddressedReviewAt(addressedThrough);
        jpaRepository.save(entity);
    }
}
