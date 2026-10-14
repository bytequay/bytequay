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

import com.bytequay.core.domain.TeamActivityEvent;
import com.bytequay.persistence.sqlite.converter.TeamActivityEventConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "team_activity", indexes = @Index(name = "idx_team_activity_account_time", columnList = "account_id,occurred_at"))
public class TeamActivityEntity
{
    @Id
    private String id;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "fact", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = TeamActivityEventConverter.class)
    private TeamActivityEvent fact;

    public TeamActivityEntity() {}

    public static String key(long accountId, String eventId)
    {
        return accountId + ":" + eventId;
    }

    public static TeamActivityEntity of(long accountId, TeamActivityEvent event)
    {
        TeamActivityEntity entity = new TeamActivityEntity();
        entity.id = key(accountId, event.id());
        entity.accountId = accountId;
        entity.update(event);
        return entity;
    }

    public void update(TeamActivityEvent event)
    {
        occurredAt = event.occurredAt();
        fact = event;
    }

    public TeamActivityEvent toDomain()
    {
        return fact;
    }
}
