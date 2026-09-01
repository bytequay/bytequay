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

import com.bytequay.agent.core.AgentProcessLaunch;
import com.bytequay.agent.core.LaunchIntentState;
import com.bytequay.agent.core.LaunchOwnerKind;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "agent_process_launch")
public class AgentProcessLaunchEntity
        implements Persistable<String>
{
    @Id
    private String id;

    @Column(name = "owner_kind", nullable = false)
    @Enumerated(EnumType.STRING)
    private LaunchOwnerKind ownerKind;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "state", nullable = false)
    @Enumerated(EnumType.STRING)
    private LaunchIntentState state;

    @Column(name = "executable", nullable = false)
    private String executable;

    @Column(name = "argv_digest", nullable = false)
    private String argvDigest;

    @Column(name = "verified_stopped")
    private Boolean verifiedStopped;

    @Column(name = "updated_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    @Transient
    private boolean newEntity = true;

    public AgentProcessLaunchEntity() {}

    public static AgentProcessLaunchEntity fromDomain(AgentProcessLaunch launch)
    {
        AgentProcessLaunchEntity entity = new AgentProcessLaunchEntity();
        entity.id = launch.launchId();
        entity.ownerKind = launch.ownerKind();
        entity.ownerId = launch.ownerId();
        entity.state = launch.state();
        entity.executable = launch.executable();
        entity.argvDigest = launch.argvDigest();
        entity.verifiedStopped = launch.verifiedStopped().orElse(null);
        entity.updatedAt = launch.updatedAt();
        return entity;
    }

    public AgentProcessLaunch toDomain()
    {
        return new AgentProcessLaunch(
                id,
                ownerKind,
                ownerId,
                state,
                executable,
                argvDigest,
                Optional.ofNullable(verifiedStopped),
                updatedAt);
    }

    public void advance(LaunchIntentState next, Instant at)
    {
        state = next;
        updatedAt = at;
    }

    public void terminal(boolean stopped, Instant at)
    {
        state = LaunchIntentState.TERMINAL;
        verifiedStopped = stopped;
        updatedAt = at;
    }

    public LaunchIntentState state()
    {
        return state;
    }

    @Override
    public String getId()
    {
        return id;
    }

    @Override
    public boolean isNew()
    {
        return newEntity;
    }

    @PostPersist
    @PostLoad
    void markPersisted()
    {
        newEntity = false;
    }
}
