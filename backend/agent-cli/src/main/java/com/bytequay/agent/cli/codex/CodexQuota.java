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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.spi.ProviderQuotaSnapshot;
import com.bytequay.agent.spi.ProviderQuotaWindow;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.Optional;

final class CodexQuota
{
    private CodexQuota() {}

    static ProviderQuotaSnapshot parse(JsonNode response)
    {
        ImmutableList.Builder<ProviderQuotaWindow> windows = ImmutableList.builder();
        JsonNode buckets = response.path("rateLimitsByLimitId");
        JsonNode main = buckets.path("codex");
        if (buckets.isObject() && !buckets.isEmpty()) {
            buckets.properties().forEach(entry -> addBucket(windows, entry.getKey(), entry.getValue()));
            if (main.isMissingNode()) {
                main = buckets.elements().next();
            }
        }
        else {
            main = response.path("rateLimits");
            addBucket(windows, "codex", main);
        }
        return new ProviderQuotaSnapshot(Optional.ofNullable(main.get("planType")).filter(JsonNode::isTextual).map(JsonNode::textValue), windows.build(), Optional.empty());
    }

    private static void addBucket(ImmutableList.Builder<ProviderQuotaWindow> windows, String id, JsonNode bucket)
    {
        for (String key : ImmutableList.of("primary", "secondary")) {
            JsonNode window = bucket.path(key);
            JsonNode used = window.path("usedPercent");
            if (!used.isNumber() || !Double.isFinite(used.doubleValue()) || used.doubleValue() < 0) {
                continue;
            }
            long minutes = window.path("windowDurationMins").asLong(0);
            Optional<Instant> reset = window.path("resetsAt").canConvertToLong() && window.path("resetsAt").longValue() > 0
                    ? Optional.of(Instant.ofEpochSecond(window.path("resetsAt").longValue())) : Optional.empty();
            Optional<Instant> start = minutes > 0 && minutes <= 525600 ? reset.map(value -> value.minusSeconds(minutes * 60)) : Optional.empty();
            String label = minutes == 300 ? "5-hour" : minutes == 10080 ? "Weekly" : minutes > 0 ? minutes + "-minute" : "Limit";
            if (!id.equals("codex")) {
                label += " · " + bucket.path("limitName").asText(id);
            }
            windows.add(new ProviderQuotaWindow(id + ":" + key, label, used.doubleValue(), start, reset, Optional.empty()));
        }
    }
}
