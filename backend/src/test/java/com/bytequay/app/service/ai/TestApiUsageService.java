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

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestApiUsageService
{
    @Test
    void exposesTheCurrentMonthsApiOnlyLedger()
    {
        YearMonth month = YearMonth.of(2026, 7);
        AiLedgerService ledger = mock(AiLedgerService.class);
        when(ledger.ledger(month)).thenReturn(new AiLedgerService.AiLedger(
                month.toString(), 8_712, 412, List.of(), List.of(), List.of(
                        new AiLedgerService.ProviderEntry("anthropic", 0, 0),
                        new AiLedgerService.ProviderEntry("deepseek", 347, 674))));
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-20T08:00:00Z"), ZoneId.of("Asia/Singapore"));

        ApiUsageService.ApiUsage usage = new ApiUsageService(ledger, clock).current();

        assertThat(usage.month()).isEqualTo("2026-07");
        assertThat(usage.providers()).containsExactly(
                new ApiUsageService.ApiProviderUsage("anthropic", "Anthropic API", 0, 0),
                new ApiUsageService.ApiProviderUsage("deepseek", "DeepSeek API", 347, 6_740));
        verify(ledger).ledger(month);
    }
}
