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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.discussion.ReviewFixReply;
import com.bytequay.core.discussion.ReviewFixStore;
import com.bytequay.persistence.sqlite.entity.ReviewFixReplyEntity;
import com.bytequay.persistence.sqlite.repository.ReviewFixReplyJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
@Transactional(readOnly = true)
public class SqliteReviewFixStore
        implements ReviewFixStore
{
    private final ReviewFixReplyJpaRepository replies;

    public SqliteReviewFixStore(ReviewFixReplyJpaRepository replies)
    {
        this.replies = requireNonNull(replies, "replies is null");
    }

    @Override
    @Transactional
    public void insertIfAbsent(ReviewFixReply reply)
    {
        if (!replies.existsById(reply.id())) {
            replies.saveAndFlush(new ReviewFixReplyEntity(reply));
        }
    }

    @Override
    @Transactional
    public void update(ReviewFixReply reply)
    {
        ReviewFixReplyEntity entity = replies.findById(reply.id()).orElseThrow(() -> new IllegalStateException("Unknown review fix reply: " + reply.id()));
        entity.update(reply);
        replies.saveAndFlush(entity);
    }

    @Override
    public List<ReviewFixReply> listByTask(String taskId)
    {
        return replies.findByTaskIdOrderByRequestedAtAscCommentIdAsc(taskId).stream().map(ReviewFixReplyEntity::toDomain).collect(toImmutableList());
    }
}
