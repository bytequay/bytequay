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

import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.review.ReviewDraftItem;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

import static com.bytequay.persistence.sqlite.entity.JsonGuard.REVIEW;

/** The user's local pending review for one pull request; keyed by repository and number so it outlives the cached row. */
@Entity
@Table(name = "pr_review_draft")
public class PrReviewDraftEntity
        extends AssignedIdEntity<String>
{
    private static final TypeReference<List<ReviewDraftItem>> ITEMS = new TypeReference<>() {};

    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(nullable = false)
    private String repo;

    @Column(nullable = false)
    private int number;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    private String event;

    @Column(name = "items_json", nullable = false, columnDefinition = "TEXT")
    private String itemsJson;

    @Version
    @Getter
    private long version;

    @Column(nullable = false)
    private Instant updatedAt;

    public PrReviewDraftEntity() {}

    public static PrReviewDraftEntity fromDomain(ReviewDraft draft)
    {
        PrReviewDraftEntity entity = new PrReviewDraftEntity();
        entity.id = draft.id();
        entity.version = draft.version();
        entity.updateFrom(draft);
        return entity;
    }

    public void updateFrom(ReviewDraft draft)
    {
        repo = draft.repo();
        number = draft.number();
        body = draft.body();
        event = draft.event();
        itemsJson = REVIEW.write(draft.items(), "review draft items");
        updatedAt = draft.updatedAt();
    }

    public ReviewDraft toDomain()
    {
        return new ReviewDraft(id, repo, number, body, event, REVIEW.read(itemsJson, ITEMS, "review draft items"), version, updatedAt);
    }
}
