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
package com.bytequay.persistence.sqlite.converter;

import com.bytequay.agent.core.AutomationLevel;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.spi.AgentLaneId;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDevelopmentAutomationConverter
{
    private final DevelopmentAutomationConverter converter = new DevelopmentAutomationConverter();

    @Test
    void requiresEveryFieldAndRoundTripsCurrentSettings()
    {
        DevelopmentAutomation automation = new DevelopmentAutomation(true, AutomationLevel.REVIEW_FIX, Optional.of(AgentLaneId.CLAUDE_CLI));
        assertThat(converter.convertToDatabaseColumn(automation)).isEqualTo("{\"autoCommit\":true,\"level\":\"REVIEW_FIX\",\"ciLaneId\":{\"value\":\"claude\"}}");
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(automation))).isEqualTo(automation);
        assertThatThrownBy(() -> converter.convertToEntityAttribute("{\"autoCommit\":true,\"level\":\"MERGE\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convertToEntityAttribute("{\"autoCommit\":true,\"level\":null,\"ciLaneId\":null}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void savedFlagsBecomeTheFurthestLevelThatWasOn()
    {
        assertThat(converter.convertToEntityAttribute("{\"autoCommit\":true,\"autoDraftPr\":true,\"autoCiFix\":false,\"ciLaneId\":null,\"reviewSummary\":true}"))
                .isEqualTo(new DevelopmentAutomation(true, AutomationLevel.DRAFT_PR, Optional.empty()));
        assertThat(converter.convertToEntityAttribute("{\"autoCommit\":false,\"autoDraftPr\":false,\"autoCiFix\":true,\"ciLaneId\":{\"value\":\"codex\"},\"reviewReply\":true}"))
                .isEqualTo(new DevelopmentAutomation(false, AutomationLevel.CI_FIX, Optional.of(AgentLaneId.CODEX_CLI)));
        assertThat(converter.convertToEntityAttribute("{\"autoCommit\":true,\"autoDraftPr\":false,\"autoCiFix\":false,\"ciLaneId\":null}"))
                .isEqualTo(DevelopmentAutomation.DEFAULT);
    }
}
