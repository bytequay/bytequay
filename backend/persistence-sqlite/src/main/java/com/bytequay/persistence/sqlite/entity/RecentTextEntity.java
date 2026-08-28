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
import com.bytequay.core.domain.RecentText;
import com.bytequay.core.domain.RecentTextKind;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** A body the user wrote. The FTS5 index beside it is kept in step by {@code SqliteRecentStore}. */
@Entity
@Table(name = "recent_text", indexes = {
        @Index(name = "idx_recent_text_item", columnList = "item_key"),
        @Index(name = "idx_recent_text_written", columnList = "written_at")})
public class RecentTextEntity
{
    /** Matches the cap the recorder trims bodies to before they are stored. */
    public static final int MAX_BODY = 4000;

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "item_type", nullable = false)
    private String itemType;

    @Column(name = "item_key", nullable = false)
    private String itemKey;

    @Column(name = "kind", nullable = false)
    private String kind;

    // Bounded where it is written, so this is ordinary text rather than a CLOB.
    // The distinction matters: Hibernate refuses LOWER() on a CLOB, which is
    // exactly what the word search needs.
    @Column(name = "body", nullable = false, length = MAX_BODY)
    private String body;

    @Column(name = "written_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant writtenAt;

    @Column(name = "item_number")
    private Integer itemNumber;

    @Column(name = "repo")
    private String repo;

    @Column(name = "title")
    private String title;

    @Column(name = "html_url")
    private String htmlUrl;

    public RecentTextEntity() {}

    public static RecentTextEntity of(RecentText text)
    {
        RecentTextEntity entity = new RecentTextEntity();
        entity.id = text.id();
        entity.itemType = text.type().name();
        entity.itemKey = text.itemKey();
        entity.kind = text.kind().name();
        entity.body = text.body();
        entity.writtenAt = text.writtenAt();
        entity.itemNumber = text.number();
        entity.repo = text.repo();
        entity.title = text.title();
        entity.htmlUrl = text.htmlUrl();
        return entity;
    }

    public String getId() { return id; }

    public String getBody() { return body; }

    public RecentText toDomain()
    {
        return new RecentText(
                id,
                RecentItemType.valueOf(itemType),
                itemKey,
                RecentTextKind.valueOf(kind),
                body,
                writtenAt,
                itemNumber,
                repo,
                title,
                htmlUrl);
    }
}
