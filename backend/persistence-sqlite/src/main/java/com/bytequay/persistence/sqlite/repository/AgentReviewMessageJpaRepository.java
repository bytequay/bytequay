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
package com.bytequay.persistence.sqlite.repository;

import com.bytequay.persistence.sqlite.entity.AgentReviewMessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AgentReviewMessageJpaRepository
        extends JpaRepository<AgentReviewMessageEntity, String>
{
    List<AgentReviewMessageEntity> findByReviewIdOrderByCreatedAtAsc(String reviewId);

    @Query("""
            SELECT message FROM AgentReviewMessageEntity message
            WHERE message.reviewId = :reviewId AND message.runId = :runId
                AND ((:findingId IS NULL AND message.findingId IS NULL) OR message.findingId = :findingId)
                AND ((:cardId IS NULL AND message.cardId IS NULL) OR message.cardId = :cardId)
                AND message.targetRevision = :targetRevision
                AND (message.createdAt < :createdAt OR (message.createdAt = :createdAt AND message.id < :messageId))
            ORDER BY message.createdAt DESC, message.id DESC
            """)
    List<AgentReviewMessageEntity> findEarlier(
            @Param("reviewId") String reviewId,
            @Param("runId") String runId,
            @Param("findingId") String findingId,
            @Param("cardId") String cardId,
            @Param("targetRevision") int targetRevision,
            @Param("createdAt") Instant createdAt,
            @Param("messageId") String messageId,
            Pageable pageable);
}
