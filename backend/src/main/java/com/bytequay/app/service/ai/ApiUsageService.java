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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Month-to-date API usage observed by ByteQuay's timestamped AI ledger. */
@Service
public class ApiUsageService
{
    private final AiLedgerService ledger;
    private final Clock clock;

    @Autowired
    public ApiUsageService(AiLedgerService ledger)
    {
        this(ledger, Clock.systemDefaultZone());
    }

    ApiUsageService(AiLedgerService ledger, Clock clock)
    {
        this.ledger = requireNonNull(ledger, "ledger is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public ApiUsage current()
    {
        YearMonth month = YearMonth.now(clock);
        List<ApiProviderUsage> providers = ledger.ledger(month).apiByProvider().stream()
                .map(entry -> new ApiProviderUsage(
                        entry.provider(),
                        entry.provider().equals("anthropic") ? "Anthropic API" : "DeepSeek API",
                        entry.callsCount(),
                        entry.costCents() * 10))
                .toList();
        return new ApiUsage(month.toString(), providers);
    }

    public record ApiUsage(String month, List<ApiProviderUsage> providers)
    {
        public ApiUsage
        {
            providers = List.copyOf(providers);
        }
    }

    public record ApiProviderUsage(
            String provider,
            String label,
            long callsCount,
            long costUsdMilli) {}
}
