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
package com.bytequay.app.service.ai;

import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadStore.AiSpendRow;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestAiLedgerService
{
    @Test
    void rollsUpSpendByProviderAndTaskType()
    {
        ThreadStore threadStore = mock(ThreadStore.class);
        InvestigationReviewStore reviewStore = mock(InvestigationReviewStore.class);
        when(threadStore.aggregateAiSpend(any(), any())).thenReturn(List.of(
                new AiSpendRow("claude-code", "build", "CLI_AGENT", 3000, 12),
                new AiSpendRow("claude-sonnet-4-6", "review", "LOGIC_LOOP", 2000, 8),
                new AiSpendRow("deepseek-v4-flash", "review", "LOGIC_LOOP", 900, 3),
                new AiSpendRow("openai/gpt-5", "build", "BRAIN_AGENT", 1000, 4)));
        when(reviewStore.agentReviewSpend(any(), any())).thenReturn(List.of(
                new InvestigationReviewStore.AgentReviewSpend("deepseek", "api", 500, 2),
                new InvestigationReviewStore.AgentReviewSpend("anthropic", "cli", 700, 3)));
        AiLedgerService service = new AiLedgerService(threadStore, reviewStore);

        AiLedgerService.AiLedger ledger = service.ledger(YearMonth.of(2026, 6));

        assertThat(ledger.month()).isEqualTo("2026-06");
        assertThat(ledger.totalCents()).isEqualTo(810);  // 8100 milli / 10
        assertThat(ledger.totalCalls()).isEqualTo(32);
        // CLI, API, and historical Anthropic rows still contribute to the full ledger.
        assertThat(ledger.byProvider()).anySatisfy(p -> {
            assertThat(p.provider()).isEqualTo("anthropic");
            assertThat(p.costCents()).isEqualTo(570);
            assertThat(p.callsCount()).isEqualTo(23);
        });
        assertThat(ledger.byProvider()).anySatisfy(p -> {
            assertThat(p.provider()).isEqualTo("openai");
            assertThat(p.costCents()).isEqualTo(100);
        });
        // Task type: build→dev, review→review, BRAIN_AGENT kind→brain.
        assertThat(ledger.byTaskType()).extracting(AiLedgerService.TaskTypeEntry::type)
                .containsExactlyInAnyOrder("dev", "review", "brain");
        assertThat(ledger.apiByProvider()).containsExactly(
                new AiLedgerService.ProviderEntry("anthropic", 8, 200),
                new AiLedgerService.ProviderEntry("deepseek", 2, 50));
    }
}
