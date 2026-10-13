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
package com.bytequay.agent.spi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record ProviderQuotaWindow(String id, String label, double usedPercent, Optional<Instant> startsAt, Optional<Instant> resetsAt, Optional<Amount> amount)
{
    public ProviderQuotaWindow
    {
        checkNotBlank(id, "id");
        checkNotBlank(label, "label");
        checkArgument(Double.isFinite(usedPercent) && usedPercent >= 0, "invalid usage percentage");
        requireNonNull(startsAt, "startsAt is null");
        requireNonNull(resetsAt, "resetsAt is null");
        requireNonNull(amount, "amount is null");
        checkArgument(startsAt.isEmpty() || resetsAt.filter(reset -> reset.isAfter(startsAt.orElseThrow())).isPresent(), "invalid quota period");
    }

    /** Monetary values in major currency units; never a locally estimated task cost. */
    public record Amount(BigDecimal used, BigDecimal total, String currency)
    {
        public Amount
        {
            requireNonNull(used, "used is null");
            requireNonNull(total, "total is null");
            checkNotBlank(currency, "currency");
            checkArgument(used.signum() >= 0 && total.signum() > 0, "invalid quota amounts");
            checkArgument(currency.matches("[A-Z]{3}"), "invalid quota currency");
        }
    }
}
