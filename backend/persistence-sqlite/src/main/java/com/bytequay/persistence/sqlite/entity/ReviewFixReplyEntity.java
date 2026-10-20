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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.core.discussion.ReviewFixReply;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "review_fix_reply", indexes = @Index(name = "idx_review_fix_reply_task", columnList = "task_id"))
public class ReviewFixReplyEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(nullable = false)
    private String repo;

    @Column(nullable = false)
    private int number;

    @Column(name = "root_comment_id", nullable = false)
    private long rootCommentId;

    @Column(name = "comment_id", nullable = false)
    private long commentId;

    @Column(name = "request_key", nullable = false)
    private String requestKey;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(columnDefinition = "TEXT")
    private String reply;

    @Column(nullable = false)
    private boolean fixed;

    @Column(name = "post_started_at")
    private Instant postStartedAt;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(columnDefinition = "TEXT")
    private String failure;

    protected ReviewFixReplyEntity() {}

    public ReviewFixReplyEntity(ReviewFixReply reply)
    {
        id = reply.id();
        taskId = reply.taskId();
        repo = reply.repo();
        number = reply.number();
        rootCommentId = reply.rootCommentId();
        commentId = reply.commentId();
        requestKey = reply.requestKey();
        requestedAt = reply.requestedAt();
        update(reply);
    }

    public void update(ReviewFixReply value)
    {
        reply = value.reply().orElse(null);
        fixed = value.fixed();
        postStartedAt = value.postStartedAt().orElse(null);
        postedAt = value.postedAt().orElse(null);
        resolvedAt = value.resolvedAt().orElse(null);
        failure = value.failure().orElse(null);
    }

    public ReviewFixReply toDomain()
    {
        return new ReviewFixReply(taskId, repo, number, rootCommentId, commentId, requestKey, requestedAt, Optional.ofNullable(reply), fixed,
                Optional.ofNullable(postStartedAt), Optional.ofNullable(postedAt), Optional.ofNullable(resolvedAt), Optional.ofNullable(failure));
    }
}
