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
package com.bytequay.app.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReviewRoundState
{
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void databaseAndJsonBoundariesStayLowerCase()
            throws Exception
    {
        assertThat(ReviewRoundState.fromDbValue("awaiting_gate"))
                .isEqualTo(ReviewRoundState.AWAITING_GATE);
        assertThat(mapper.writeValueAsString(ReviewRoundState.AWAITING_GATE))
                .isEqualTo("\"awaiting_gate\"");
        assertThat(mapper.readValue("\"addressing\"", ReviewRoundState.class))
                .isEqualTo(ReviewRoundState.ADDRESSING);
    }

    @Test
    void onlyDriveableWorkStatesAreLive()
    {
        assertThat(ReviewRoundState.TRIAGING.isLive()).isTrue();
        assertThat(ReviewRoundState.ADDRESSING.isLive()).isTrue();
        assertThat(ReviewRoundState.AWAITING_GATE.isLive()).isTrue();
        assertThat(ReviewRoundState.PAUSED.isLive()).isFalse();
        assertThat(ReviewRoundState.POSTED.isLive()).isFalse();
        assertThat(ReviewRoundState.CLOSED.isLive()).isFalse();
    }

    @Test
    void corruptPersistedStatesFailClosed()
    {
        assertThatThrownBy(() -> ReviewRoundState.fromDbValue(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReviewRoundState.fromDbValue("running"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReviewRoundState.fromDbValue(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
