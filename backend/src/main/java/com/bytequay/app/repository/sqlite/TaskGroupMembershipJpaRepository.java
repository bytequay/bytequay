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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface TaskGroupMembershipJpaRepository
        extends JpaRepository<TaskGroupMembershipEntity, TaskGroupMembershipEntity.MembershipKey>
{
    List<TaskGroupMembershipEntity> findByIdGroupIdOrderByAddedAtMsAsc(String groupId);

    List<TaskGroupMembershipEntity> findByIdTaskIdOrderByAddedAtMsAsc(String taskId);

    /** Single-shot membership snapshot for the frontend — all rows in
     *  the join table, oldest-first per group. The frontend builds
     *  whatever index it needs (task→groups, group→tasks) from this. */
    List<TaskGroupMembershipEntity> findAllByOrderByIdGroupIdAscAddedAtMsAsc();

    long countByIdGroupId(String groupId);

    @Modifying
    @Query("delete from TaskGroupMembershipEntity m where m.id.groupId = :groupId")
    void deleteByGroupId(@Param("groupId") String groupId);
}
