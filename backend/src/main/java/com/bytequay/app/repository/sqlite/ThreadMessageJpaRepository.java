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

interface ThreadMessageJpaRepository
        extends JpaRepository<ThreadMessageEntity, String>
{
    /** Conversation order — oldest-first by seq. */
    List<ThreadMessageEntity> findByThreadIdOrderBySeqAsc(String threadId);

    /** Most-recent-first window. Powers the conversation index's
     *  initial load: take the latest N messages so the user opens
     *  the page on the tail of the conversation, then derive the
     *  user-prompt index entries from that same window. */
    List<ThreadMessageEntity> findByThreadIdOrderBySeqDesc(String threadId, Pageable page);

    /** Older window: messages strictly before a known cursor seq,
     *  most-recent-first. Drives the "↑ load earlier" footer in the
     *  conversation-index panel. */
    List<ThreadMessageEntity> findByThreadIdAndSeqLessThanOrderBySeqDesc(
            String threadId, long beforeSeq, Pageable page);

    /** Most-recent-first window of user prompts only (role + type
     *  supplied as 'user' / 'text'). Powers the conversation index's
     *  prompt-based initial load so a busy turn's tool chatter can't
     *  bury earlier prompts. */
    List<ThreadMessageEntity> findByThreadIdAndRoleAndTypeOrderBySeqDesc(
            String threadId, String role, String type, Pageable page);

    /** Older user-prompt window for "↑ load earlier", most-recent-first. */
    List<ThreadMessageEntity> findByThreadIdAndRoleAndTypeAndSeqLessThanOrderBySeqDesc(
            String threadId, String role, String type, long beforeSeq, Pageable page);

    /** Count of user-role text prompts in a thread. Used by the index
     *  header's "N of M" widget so the user knows how many prompts
     *  exist beyond the loaded window. role='user' AND type='text'
     *  matches the doc's definition of a "user prompt" — not
     *  tool_result rows, which the CLI sometimes emits as role=user. */
    @Query("""
            SELECT COUNT(m)
            FROM ThreadMessageEntity m
            WHERE m.threadId = :threadId
              AND m.role = 'user'
              AND m.type = 'text'
            """)
    long countUserMessages(@Param("threadId") String threadId);

    /** Highest seq for a thread, or {@code null} when no messages exist
     *  yet. Coalesces nulls in the application layer so we don't have
     *  to embed a database-specific {@code COALESCE} here. */
    @Query("""
            SELECT MAX(m.seq) FROM ThreadMessageEntity m
            WHERE m.threadId = :threadId
            """)
    Long maxSeq(@Param("threadId") String threadId);

    /** Sum of {@code tokensIn + tokensOut} across the inclusive seq
     *  range. Null token counts are treated as zero by the {@code
     *  COALESCE}; the outer wrapper coalesces an empty result set
     *  (no rows in the range) to 0. */
    @Query("""
            SELECT COALESCE(
                     SUM(COALESCE(m.tokensIn, 0) + COALESCE(m.tokensOut, 0)),
                     0)
            FROM ThreadMessageEntity m
            WHERE m.threadId = :threadId
              AND m.seq >= :firstSeq
              AND m.seq <= :lastSeq
            """)
    long sumTokensBetween(
            @Param("threadId") String threadId,
            @Param("firstSeq") long firstSeq,
            @Param("lastSeq") long lastSeq);

    /** Inclusive-range slice in conversation order. Cheaper than the
     *  filter-then-slice the default {@link
     *  com.bytequay.app.repository.ThreadStore#listMessagesBetween}
     *  does because it only loads the rows we want. */
    @Query("""
            SELECT m FROM ThreadMessageEntity m
            WHERE m.threadId = :threadId
              AND m.seq >= :firstSeq
              AND m.seq <= :lastSeq
            ORDER BY m.seq ASC
            """)
    List<ThreadMessageEntity> findByThreadIdAndSeqBetween(
            @Param("threadId") String threadId,
            @Param("firstSeq") long firstSeq,
            @Param("lastSeq") long lastSeq);

    /** Cascade delete when the parent thread is removed. */
    void deleteByThreadId(String threadId);
}
