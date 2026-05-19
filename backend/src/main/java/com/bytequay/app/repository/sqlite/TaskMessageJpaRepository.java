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

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface TaskMessageJpaRepository
        extends JpaRepository<TaskMessageEntity, String>
{
    /** Conversation order — oldest-first by seq. */
    List<TaskMessageEntity> findByTaskIdOrderBySeqAsc(String taskId);

    /** Most-recent-first window. Powers the conversation index's
     *  initial load: take the latest N messages so the user opens
     *  the page on the tail of the conversation, then derive the
     *  user-prompt index entries from that same window. */
    List<TaskMessageEntity> findByTaskIdOrderBySeqDesc(String taskId, Pageable page);

    /** Older window: messages strictly before a known cursor seq,
     *  most-recent-first. Drives the "↑ load earlier" footer in the
     *  conversation-index panel. */
    List<TaskMessageEntity> findByTaskIdAndSeqLessThanOrderBySeqDesc(
            String taskId, long beforeSeq, Pageable page);

    /** Count of user-role text prompts in a task. Used by the index
     *  header's "N of M" widget so the user knows how many prompts
     *  exist beyond the loaded window. role='user' AND type='text'
     *  matches the doc's definition of a "user prompt" — not
     *  tool_result rows, which the CLI sometimes emits as role=user. */
    @Query("""
            SELECT COUNT(m)
            FROM TaskMessageEntity m
            WHERE m.taskId = :taskId
              AND m.role = 'user'
              AND m.type = 'text'
            """)
    long countUserMessages(@Param("taskId") String taskId);

    /** Cascade delete when the parent task is removed. */
    void deleteByTaskId(String taskId);
}
