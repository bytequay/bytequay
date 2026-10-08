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

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Account-wide limits, independent of tokens recorded for ByteQuay tasks. */
public record ProviderQuotaSnapshot(Optional<String> plan, List<ProviderQuotaWindow> windows, Optional<String> message)
{
    public ProviderQuotaSnapshot
    {
        requireNonNull(plan, "plan is null");
        windows = ImmutableList.copyOf(windows);
        requireNonNull(message, "message is null");
    }

    public static ProviderQuotaSnapshot unavailable(String message)
    {
        return new ProviderQuotaSnapshot(Optional.empty(), ImmutableList.of(), Optional.of(message));
    }
}
