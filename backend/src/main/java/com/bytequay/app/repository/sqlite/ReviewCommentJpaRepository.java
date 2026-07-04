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

interface ReviewCommentJpaRepository
        extends JpaRepository<ReviewCommentEntity, String>
{
    List<ReviewCommentEntity> findByTaskIdAndResolvedFalse(String taskId);

    List<ReviewCommentEntity> findByTaskIdAndSource(String taskId, String source);

    List<ReviewCommentEntity> findByTaskIdOrderByCreatedAtMsAsc(String taskId);

    /** Dedup guard for remote-comment ingestion — remote_link is unique
     *  per github.com discussion comment. */
    boolean existsByRemoteLink(String remoteLink);

    /** Remote comments not yet grouped into a round — what ReviewRoundService
     *  batches on each reconcile sweep. */
    List<ReviewCommentEntity> findByTaskIdAndSourceAndRoundIdIsNull(String taskId, String source);

    List<ReviewCommentEntity> findByRoundIdOrderByCreatedAtMsAsc(String roundId);
}
