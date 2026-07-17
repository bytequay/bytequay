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
package com.bytequay.app.service.threads;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TestNotificationMuteService
{
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NotificationMuteService service =
            new NotificationMuteService(jdbc);

    @Test
    void actionableTypesCanNeverBeMuted()
    {
        assertThatThrownBy(() -> service.set("ws-1", "approval-gate", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.set("ws-1", "agent-question", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.set("ws-1", "budget", true))
                .isInstanceOf(IllegalArgumentException.class);

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void protectedTypesIgnoreAnyPersistedMute()
    {
        assertThat(service.muted("ws-1", "approval-gate")).isFalse();
        assertThat(service.muted("ws-1", "agent-question")).isFalse();
        assertThat(service.muted("ws-1", "budget")).isFalse();
        verify(jdbc, never()).query(
                anyString(),
                any(ResultSetExtractor.class),
                any(Object[].class));
    }
}
