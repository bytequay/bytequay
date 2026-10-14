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

import com.bytequay.agent.core.AgentAttachmentReference;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.persistence.sqlite.converter.DevelopmentAutomationConverter;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.bytequay.persistence.sqlite.entity.JsonGuard.AGENT;

/** Accepted turns and queued inputs persist the same settings in their existing columns. */
@Embeddable
public record AgentTurnSettingsColumns(
        String requestedModel,
        String laneId,
        String effort,
        String serviceTier,
        @Column(columnDefinition = "TEXT") @Convert(converter = DevelopmentAutomationConverter.class) DevelopmentAutomation developmentAutomation,
        Boolean useRepositoryContext,
        @Enumerated(EnumType.STRING) AgentPermissionPolicy permissionPolicy,
        Long maximumRuntimeMillis,
        @Column(columnDefinition = "TEXT") String attachmentsJson)
{
    private static final TypeReference<List<AgentAttachmentReference>> ATTACHMENTS = new TypeReference<>() {};

    AgentTurnSettingsColumns(AgentTurnSettings settings)
    {
        this(settings.model().orElse(null), settings.laneId().map(AgentLaneId::value).orElse(null), settings.effort().orElse(null), settings.serviceTier().orElse(null),
                settings.developmentAutomation().orElse(null), settings.useRepositoryContext(), settings.permissionPolicy(), settings.maximumRuntime().toMillis(),
                AGENT.write(settings.attachments(), "turn attachment references"));
    }

    AgentTurnSettings toDomain()
    {
        return new AgentTurnSettings(Optional.ofNullable(requestedModel), Optional.ofNullable(effort), permissionPolicy, Duration.ofMillis(maximumRuntimeMillis),
                AGENT.read(attachmentsJson, ATTACHMENTS, "turn attachment references"), Optional.ofNullable(serviceTier), Optional.ofNullable(developmentAutomation),
                !Boolean.FALSE.equals(useRepositoryContext), Optional.ofNullable(laneId).map(AgentLaneId::new));
    }
}
