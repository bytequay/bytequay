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
package com.bytequay.app.agenda;

import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.settings.SetupSettingsService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TestAgendaSettings
{
    @Test
    void agendaRequiresAUsableGlobalAgentToEnableButAlwaysAllowsDisabling()
    {
        SetupSettingsService setup = mock(SetupSettingsService.class);
        AgendaSettings settings = new AgendaSettings(new InMemoryAppSettingsStore(), AgendaFixtures.mapper(), setup);
        AgendaSchedule defaults = AgendaSchedule.DEFAULT;
        AgendaSchedule enabled = new AgendaSchedule(true, defaults.minute(), defaults.days(), defaults.calendarAccountId());
        doThrow(new AgentUnavailableException("Sign in first")).doNothing().when(setup).requireDevelopmentAgentToEnable();

        assertThatThrownBy(() -> settings.save(enabled)).isInstanceOf(AgentUnavailableException.class);
        assertThat(settings.schedule().enabled()).isFalse();
        assertThat(settings.save(enabled).enabled()).isTrue();
        assertThat(settings.save(enabled).enabled()).isTrue();
        assertThat(settings.save(defaults).enabled()).isFalse();
        verify(setup, times(2)).requireDevelopmentAgentToEnable();
    }
}
