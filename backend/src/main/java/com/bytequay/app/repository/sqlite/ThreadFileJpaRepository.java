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

interface ThreadFileJpaRepository
        extends JpaRepository<ThreadFileEntity, ThreadFileEntity.ThreadFileKey>
{
    /** Sidebar order — most-recently-touched first. */
    List<ThreadFileEntity> findByIdThreadIdOrderByLastTouchedMsDesc(String threadId);

    /** Cascade delete when the parent thread is removed. The composite
     *  key path needs the {@code IdTaskId} traversal that JPA derives
     *  from the {@code @EmbeddedId} field on the entity. */
    void deleteByIdThreadId(String threadId);
}
