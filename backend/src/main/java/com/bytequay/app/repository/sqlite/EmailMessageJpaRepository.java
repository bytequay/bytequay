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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

interface EmailMessageJpaRepository
        extends JpaRepository<EmailMessageEntity, EmailMessageEntity.EmailMessageKey>
{
    /** All messages in a thread, oldest first — used for the
     *  per-thread aggregate view's count and unread state. */
    List<EmailMessageEntity> findByIdAccountEmailAndGmailThreadIdOrderByReceivedAtMsAsc(
            String accountEmail, String gmailThreadId);

    /** Picks the latest message per inbox thread and returns it
     *  alongside the per-thread aggregates (count, any-unread).
     *  ROW_NUMBER() requires SQLite 3.25+; we're on 3.47.
     *
     *  Returns Object[]: [
     *    0=gmail_message_id, 1=gmail_thread_id, 2=from_addr,
     *    3=subject, 4=snippet, 5=received_at_ms, 6=is_unread,
     *    7=msg_count, 8=any_unread_in_thread
     *  ] */
    @Query(value = """
            SELECT gmail_message_id, gmail_thread_id, from_addr, subject, snippet,
                   received_at_ms, is_unread, msg_count, any_unread
            FROM (
              SELECT gmail_message_id, gmail_thread_id, from_addr, subject, snippet,
                     received_at_ms, is_unread,
                     ROW_NUMBER() OVER (PARTITION BY gmail_thread_id ORDER BY received_at_ms DESC) AS rn,
                     COUNT(*) OVER (PARTITION BY gmail_thread_id) AS msg_count,
                     MAX(is_unread) OVER (PARTITION BY gmail_thread_id) AS any_unread
              FROM email_messages
              WHERE account_email = :account AND is_in_inbox = 1
            )
            WHERE rn = 1
            ORDER BY received_at_ms DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findInboxThreadHeads(@Param("account") String accountEmail, @Param("limit") int limit);

    @Modifying
    @Transactional
    @Query("DELETE FROM EmailMessageEntity m WHERE m.id.accountEmail = :account")
    void deleteAllForAccount(@Param("account") String accountEmail);

    @Modifying
    @Transactional
    @Query("DELETE FROM EmailMessageEntity m "
            + "WHERE m.id.accountEmail = :account AND m.id.gmailMessageId = :messageId")
    void deleteOne(@Param("account") String accountEmail, @Param("messageId") String messageId);

    Optional<EmailMessageEntity> findByIdAccountEmailAndIdGmailMessageId(
            String accountEmail, String gmailMessageId);
}
