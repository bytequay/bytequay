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
package com.bytequay.app.statemachine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestStateMachine
{
    private enum Light
    {
        GREEN, YELLOW, RED, PARKED, BROKEN
    }

    private final StateMachine<Light> machine = StateMachine.<Light>builder("light")
            .edge(Light.GREEN, Light.YELLOW)
            .edge(Light.YELLOW, Light.RED)
            .edge(Light.RED, Light.GREEN)
            .edge(Light.PARKED, Light.GREEN)
            .universal(Light.PARKED)
            .terminal(Light.BROKEN)
            .build();

    @Test
    void explicitEdgesAreLegal()
    {
        assertThat(machine.isLegal(Light.GREEN, Light.YELLOW)).isTrue();
        assertThat(machine.isLegal(Light.YELLOW, Light.RED)).isTrue();
        assertThat(machine.isLegal(Light.GREEN, Light.RED)).isFalse();
        assertThat(machine.isLegal(Light.YELLOW, Light.GREEN)).isFalse();
    }

    @Test
    void universalTargetReachableFromAnyNonTerminalState()
    {
        assertThat(machine.isLegal(Light.GREEN, Light.PARKED)).isTrue();
        assertThat(machine.isLegal(Light.YELLOW, Light.PARKED)).isTrue();
        assertThat(machine.isLegal(Light.RED, Light.PARKED)).isTrue();
        assertThat(machine.isLegal(Light.BROKEN, Light.PARKED)).isFalse();
    }

    @Test
    void nothingLeavesATerminalState()
    {
        for (Light target : Light.values()) {
            assertThat(machine.isLegal(Light.BROKEN, target)).isFalse();
        }
        assertThat(machine.nextStates(Light.BROKEN)).isEmpty();
    }

    @Test
    void sameStateIsNotATransition()
    {
        assertThat(machine.isLegal(Light.GREEN, Light.GREEN)).isFalse();
        assertThat(machine.checkTransition("id-1", Light.GREEN, Light.GREEN)).isFalse();
    }

    @Test
    void checkTransitionAllowsLegalEdgeAndThrowsOnIllegal()
    {
        assertThat(machine.checkTransition("id-1", Light.GREEN, Light.YELLOW)).isTrue();
        assertThatThrownBy(() -> machine.checkTransition("id-1", Light.GREEN, Light.RED))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessage("light id-1 cannot transition from GREEN to RED");
    }

    @Test
    void checkTransitionThrowsWhenLeavingTerminalState()
    {
        assertThatThrownBy(() -> machine.checkTransition("id-1", Light.BROKEN, Light.GREEN))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("cannot transition from BROKEN");
    }

    @Test
    void nextStatesListsEdgesPlusUniversalEscapes()
    {
        assertThat(machine.nextStates(Light.GREEN))
                .containsExactlyInAnyOrder(Light.YELLOW, Light.PARKED);
        assertThat(machine.nextStates(Light.PARKED))
                .containsExactlyInAnyOrder(Light.GREEN);
    }

    @Test
    void terminalStateWithOutgoingEdgesIsRejectedAtBuildTime()
    {
        assertThatThrownBy(() -> StateMachine.<Light>builder("bad")
                .edge(Light.BROKEN, Light.GREEN)
                .terminal(Light.BROKEN)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outgoing edges from terminal state");
    }
}
