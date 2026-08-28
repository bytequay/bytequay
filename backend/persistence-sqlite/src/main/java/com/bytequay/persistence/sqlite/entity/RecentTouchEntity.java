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

import com.bytequay.core.domain.RecentItemType;
import com.bytequay.core.domain.RecentReason;
import com.bytequay.core.domain.RecentTouch;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row per item per day. The primary key is {@code <itemKey>@<day>}, which
 * is what makes same-day dedupe an upsert rather than a scan, and the display
 * columns are denormalized so the stream needs no joins and no network.
 */
@Entity
@Table(name = "recent_touch", indexes = @Index(name = "idx_recent_touch_day", columnList = "touch_day"))
public class RecentTouchEntity
{
    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "item_type", nullable = false)
    private String itemType;

    @Column(name = "item_key", nullable = false)
    private String itemKey;

    /** ISO {@code yyyy-MM-dd} in the user's own zone; "day" is a local idea. */
    @Column(name = "touch_day", nullable = false)
    private String touchDay;

    @Column(name = "touched_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant touchedAt;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "reason_count", nullable = false)
    private int reasonCount;

    @Column(name = "item_number")
    private Integer itemNumber;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "repo")
    private String repo;

    @Column(name = "workspace_id")
    private String workspaceId;

    @Column(name = "html_url")
    private String htmlUrl;

    @Column(name = "mine", nullable = false)
    private boolean mine;

    @Column(name = "pr_id")
    private Long prId;

    @Column(name = "subtitle")
    private String subtitle;

    public RecentTouchEntity() {}

    public static RecentTouchEntity of(RecentTouch touch)
    {
        RecentTouchEntity entity = new RecentTouchEntity();
        entity.apply(touch);
        return entity;
    }

    public void apply(RecentTouch touch)
    {
        this.id = touch.id();
        this.itemType = touch.type().name();
        this.itemKey = touch.itemKey();
        this.touchDay = touch.day().toString();
        this.touchedAt = touch.touchedAt();
        this.reason = touch.reason().name();
        this.reasonCount = touch.reasonCount();
        this.itemNumber = touch.number();
        this.title = touch.title();
        this.repo = touch.repo();
        this.workspaceId = touch.workspaceId();
        this.htmlUrl = touch.htmlUrl();
        this.mine = touch.mine();
        this.prId = touch.prId();
        this.subtitle = touch.subtitle();
    }

    public RecentTouch toDomain()
    {
        return new RecentTouch(
                id,
                RecentItemType.valueOf(itemType),
                itemKey,
                LocalDate.parse(touchDay),
                touchedAt,
                RecentReason.valueOf(reason),
                reasonCount,
                itemNumber,
                title,
                repo,
                workspaceId,
                htmlUrl,
                mine,
                prId,
                subtitle);
    }
}
