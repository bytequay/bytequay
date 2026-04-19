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

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "team_member")
class TeamMemberEntity
{
    @EmbeddedId
    private TeamMemberId id;

    @Column(name = "added_at", nullable = false, updatable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant addedAt;

    protected TeamMemberEntity() {}

    TeamMemberEntity(long teamId, String login)
    {
        this.id = new TeamMemberId(teamId, login);
    }

    @PrePersist
    void prePersist()
    {
        if (this.addedAt == null) {
            this.addedAt = Instant.now();
        }
    }

    TeamMemberId getId() { return id; }

    long getTeamId() { return id.getTeamId(); }

    String getLogin() { return id.getLogin(); }

    Instant getAddedAt() { return addedAt; }

    @Embeddable
    static class TeamMemberId
            implements Serializable
    {
        @Column(name = "team_id", nullable = false)
        private long teamId;

        @Column(nullable = false)
        private String login;

        protected TeamMemberId() {}

        TeamMemberId(long teamId, String login)
        {
            this.teamId = teamId;
            this.login = login;
        }

        long getTeamId() { return teamId; }
        String getLogin() { return login; }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TeamMemberId other)) {
                return false;
            }
            return teamId == other.teamId && Objects.equals(login, other.login);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(teamId, login);
        }
    }
}
