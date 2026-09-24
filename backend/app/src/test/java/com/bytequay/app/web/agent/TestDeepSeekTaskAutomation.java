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
package com.bytequay.app.web.agent;

import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.app.web.DevelopmentAutomationDto;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDeepSeekTaskAutomation
{
    private static final AgentLaneId DEEPSEEK = new AgentLaneId("deepseek-key-11111111-1111-1111-1111-111111111111");

    @Test
    void localOnlyDtoDoesNotInheritTheInteractiveAutoCommitDefault()
    {
        var noDelivery = new DevelopmentAutomationDto(false, false, Optional.empty());

        assertThat(AgentController.submissionAutomation(DEEPSEEK, Optional.of(noDelivery)))
                .contains(DevelopmentAutomation.LOCAL_ONLY);
        assertThat(AgentController.submissionAutomation(DEEPSEEK, Optional.empty()))
                .contains(DevelopmentAutomation.LOCAL_ONLY);
        assertThat(AgentController.submissionAutomation(AgentLaneId.CODEX_CLI, Optional.of(noDelivery)))
                .contains(noDelivery.toDomain());
    }

    @Test
    void deliveryAndCiOverridesAreRejected()
    {
        assertThatThrownBy(() -> AgentController.submissionAutomation(DEEPSEEK,
                Optional.of(new DevelopmentAutomationDto(true, false, Optional.empty()))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("automatic delivery");
        assertThatThrownBy(() -> AgentController.submissionAutomation(DEEPSEEK,
                Optional.of(new DevelopmentAutomationDto(false, false, Optional.of("codex")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("automatic delivery");
    }
}
