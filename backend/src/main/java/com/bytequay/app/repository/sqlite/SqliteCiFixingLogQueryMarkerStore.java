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

import com.bytequay.app.repository.CiFixingLogQueryMarkerStore;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteCiFixingLogQueryMarkerStore
        implements CiFixingLogQueryMarkerStore
{
    private final CiFixingLogQueryMarkerJpaRepository jpaRepository;

    public SqliteCiFixingLogQueryMarkerStore(CiFixingLogQueryMarkerJpaRepository jpaRepository)
    {
        this.jpaRepository = requireNonNull(jpaRepository, "jpaRepository is null");
    }

    @Override
    public Optional<Instant> find(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return jpaRepository.findById(taskId)
                .map(CiFixingLogQueryMarkerEntity::getLastQueriedAt);
    }

    @Override
    public void mark(String taskId, Instant queriedThrough)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(queriedThrough, "queriedThrough is null");
        CiFixingLogQueryMarkerEntity entity = jpaRepository.findById(taskId)
                .orElseGet(() -> {
                    CiFixingLogQueryMarkerEntity fresh = new CiFixingLogQueryMarkerEntity();
                    fresh.setTaskId(taskId);
                    return fresh;
                });
        entity.setLastQueriedAt(queriedThrough);
        jpaRepository.save(entity);
    }
}
