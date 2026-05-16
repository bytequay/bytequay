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
package com.bytequay.app.repository;

import com.bytequay.app.domain.TaskGroup;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for the small {@code task_groups} table that
 * backs the rail's Groups section.
 */
public interface TaskGroupStore
{
    /** Insert or update by primary key. */
    void saveGroup(TaskGroup group);

    Optional<TaskGroup> findGroupById(String id);

    /** All groups, sorted by {@code sortOrder} then {@code createdAt}. */
    List<TaskGroup> listGroups();

    /** Drop a group. Tasks pointing at it are NOT deleted — their
     *  {@code group_id} is cleared via the unset path in the service. */
    void deleteGroup(String id);
}
