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

import com.bytequay.core.localreview.LocalReviewThread;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import static com.bytequay.persistence.sqlite.entity.JsonGuard.AGENT;

@Entity
@Table(name = "local_review_thread", indexes = {
        @Index(name = "idx_local_review_task", columnList = "task_id"),
        @Index(name = "idx_local_review_pr", columnList = "repo, number")})
public class LocalReviewThreadEntity
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

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Version
    @Getter
    private long version;

    public LocalReviewThreadEntity() {}

    public static LocalReviewThreadEntity fromDomain(LocalReviewThread thread)
    {
        LocalReviewThreadEntity entity = new LocalReviewThreadEntity();
        entity.id = thread.id();
        entity.version = thread.version();
        entity.updateFrom(thread);
        return entity;
    }

    public void updateFrom(LocalReviewThread thread)
    {
        taskId = thread.taskId();
        repo = thread.repo();
        number = thread.number();
        payload = AGENT.write(thread, "local review");
    }

    public LocalReviewThread toDomain()
    {
        return AGENT.read(payload, LocalReviewThread.class, "local review").withVersion(version);
    }
}
