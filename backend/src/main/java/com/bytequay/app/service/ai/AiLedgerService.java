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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Rolls AI spend up into a monthly usage ledger — total cents + call count,
 * broken down by provider and by the kind of work (dev / review / brain).
 * Aggregates cost-bearing {@code thread_messages} in the month; no charts,
 * just numbers. Old threads stay sparse for fields that landed mid-arc.
 */
@Service
public class AiLedgerService
{
    private final ThreadStore threadStore;
    private final InvestigationReviewStore reviewStore;

    public AiLedgerService(ThreadStore threadStore, InvestigationReviewStore reviewStore)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
    }

    public record AiLedger(
            String month,
            long totalCents,
            long totalCalls,
            List<ProviderEntry> byProvider,
            List<TaskTypeEntry> byTaskType) {}

    public record ProviderEntry(String provider, long callsCount, long costCents) {}

    public record TaskTypeEntry(String type, long callsCount, long costCents) {}

    /** The ledger for {@code month} (in the system zone). */
    public AiLedger ledger(YearMonth month)
    {
        ZoneId zone = ZoneId.systemDefault();
        Instant start = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();

        Map<String, long[]> byProvider = new LinkedHashMap<>(); // provider -> [calls, costMilli]
        Map<String, long[]> byType = new LinkedHashMap<>();
        long totalMilli = 0;
        long totalCalls = 0;
        for (AiSpendRow r : threadStore.aggregateAiSpend(start, end)) {
            accumulate(byProvider, canonicalProvider(r.provider()), r.calls(), r.costMilli());
            accumulate(byType, taskType(r.flow(), r.kind()), r.calls(), r.costMilli());
            totalMilli += r.costMilli();
            totalCalls += r.calls();
        }
        for (InvestigationReviewStore.AgentReviewSpend r
                : reviewStore.agentReviewSpend(start, end)) {
            accumulate(byProvider, canonicalProvider(r.provider()), r.calls(), r.costMilli());
            accumulate(byType, "review", r.calls(), r.costMilli());
            totalMilli += r.costMilli();
            totalCalls += r.calls();
        }
        return new AiLedger(
                month.toString(),
                totalMilli / 10,
                totalCalls,
                byProvider.entrySet().stream()
                        .map(e -> new ProviderEntry(e.getKey(), e.getValue()[0], e.getValue()[1] / 10))
                        .sorted(Comparator.comparingLong(ProviderEntry::costCents).reversed())
                        .toList(),
                byType.entrySet().stream()
                        .map(e -> new TaskTypeEntry(e.getKey(), e.getValue()[0], e.getValue()[1] / 10))
                        .sorted(Comparator.comparingLong(TaskTypeEntry::costCents).reversed())
                        .toList());
    }

    private static void accumulate(Map<String, long[]> map, String key, long calls, long costMilli)
    {
        long[] agg = map.computeIfAbsent(key, k -> new long[2]);
        agg[0] += calls;
        agg[1] += costMilli;
    }

    /** Map a stored provider string to a canonical provider label. */
    private static String canonicalProvider(String provider)
    {
        String p = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        if (p.contains("claude") || p.contains("anthropic")) {
            return "anthropic";
        }
        if (p.contains("openai") || p.contains("gpt")) {
            return "openai";
        }
        if (p.contains("deepseek")) {
            return "deepseek";
        }
        return p.isBlank() ? "other" : p;
    }

    /** Classify a thread's work from its flow (dbValue) + kind (enum name). */
    private static String taskType(String flow, String kind)
    {
        if ("BRAIN_AGENT".equals(kind)) {
            return "brain";
        }
        if ("review".equalsIgnoreCase(flow)) {
            return "review";
        }
        return "dev";
    }
}
