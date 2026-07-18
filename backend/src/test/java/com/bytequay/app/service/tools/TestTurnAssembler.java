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
package com.bytequay.app.service.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestTurnAssembler
{
    @Test
    void toolsListContainsOnlyTheResolvedActionTools()
    {
        TurnAssembler assembler = new TurnAssembler();

        TurnRequest req = assembler.assemble(
                List.of("{\"name\":\"read_file\"}"),
                TurnAssembler.ProviderShape.ANTHROPIC,
                "role body",
                "brain body",
                List.of(),
                "new turn");

        assertThat(req.tools()).singleElement()
                .asString()
                .contains("\"name\":\"read_file\"");
    }

    @Test
    void systemBlocksAreRoleSkillThenBrainAndNothingDynamic()
    {
        TurnAssembler assembler = new TurnAssembler();

        TurnRequest req = assembler.assemble(
                List.of(),
                TurnAssembler.ProviderShape.ANTHROPIC,
                "role body",
                "brain body",
                List.of("history-1"),
                "new turn");

        assertThat(req.systemBlocks()).containsExactly("role body", "brain body");
    }

    @Test
    void prefixIsByteStableAcrossTwoConsecutiveTurns()
    {
        // The model's prefix cache only kicks in when the bytes
        // preceding the new turn are byte-identical to the previous
        // turn — assert the assembler doesn't introduce drift even
        // when called twice with the same inputs.
        TurnAssembler assembler = new TurnAssembler();
        List<String> action = List.of("{\"name\":\"read_file\"}");

        TurnRequest turn1 = assembler.assemble(
                action,
                TurnAssembler.ProviderShape.ANTHROPIC,
                "role body",
                "brain body",
                List.of("history-1"),
                "user turn 1");
        TurnRequest turn2 = assembler.assemble(
                action,
                TurnAssembler.ProviderShape.ANTHROPIC,
                "role body",
                "brain body",
                List.of("history-1", "assistant turn 1", "history-2"),
                "user turn 2");

        // Tools and system blocks are unchanged byte-for-byte — those
        // are the prefix-cache-relevant pieces.
        assertThat(turn2.tools()).isEqualTo(turn1.tools());
        assertThat(turn2.systemBlocks()).isEqualTo(turn1.systemBlocks());
        // And the first N history messages are byte-identical too, so
        // the prefix extends through them.
        assertThat(turn2.historyMessages().subList(0, turn1.historyMessages().size()))
                .isEqualTo(turn1.historyMessages());
    }

    @Test
    void providerShapeDoesNotAddUnselectedTools()
    {
        TurnAssembler assembler = new TurnAssembler();

        TurnRequest req = assembler.assemble(
                List.of(),
                TurnAssembler.ProviderShape.OPENAI,
                "role",
                "brain",
                List.of(),
                "");

        assertThat(req.tools()).isEmpty();
    }

    @Test
    void emptyRoleAndBrainBodiesAreSkippedNotEmittedAsBlankBlocks()
    {
        TurnAssembler assembler = new TurnAssembler();

        TurnRequest req = assembler.assemble(
                List.of(),
                TurnAssembler.ProviderShape.ANTHROPIC,
                null,
                "",
                List.of(),
                "x");

        assertThat(req.systemBlocks()).isEmpty();
    }
}
