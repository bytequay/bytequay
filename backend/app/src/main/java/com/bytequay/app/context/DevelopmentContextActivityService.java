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
package com.bytequay.app.context;

import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.core.context.DevelopmentContextReceipt;
import com.bytequay.core.context.DevelopmentContextReceiptStore;
import com.bytequay.core.store.ContextUsageStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.groupingBy;

/** Task-scoped, bounded activity cards derived from exact saved receipts; note bodies never leave this projection. */
@Service
public class DevelopmentContextActivityService
{
    private static final int CARD_LIMIT = 100;
    private static final int FACT_LIMIT = 1000;
    private static final Set<ContextUsageEvent.Stage> SEARCH_STAGES = Set.of(
            ContextUsageEvent.Stage.FOUND, ContextUsageEvent.Stage.NO_MATCH, ContextUsageEvent.Stage.SCOPE_DEMAND);

    private final DevelopmentContextReceiptStore receipts;
    private final ContextUsageStore usage;
    private final AgentTurnStore turns;
    private final ObjectMapper mapper;

    public DevelopmentContextActivityService(DevelopmentContextReceiptStore receipts, ContextUsageStore usage, AgentTurnStore turns, ObjectMapper mapper)
    {
        this.receipts = requireNonNull(receipts, "receipts is null");
        this.usage = requireNonNull(usage, "usage is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** Returns the latest persisted lookup cards; notes the task saved are listed from the notebook instead. */
    public Activity activity(String workspaceId, String taskId)
    {
        List<DevelopmentContextReceipt> saved = receipts.listTask(taskId, CARD_LIMIT + 1);
        List<ContextUsageEvent> facts = usage.listTask(workspaceId, taskId, FACT_LIMIT + 1);
        Map<String, List<ContextUsageEvent>> byRef = facts.stream().limit(FACT_LIMIT).collect(groupingBy(ContextUsageEvent::receiptRef));
        boolean usageTruncated = facts.size() > FACT_LIMIT;
        List<Card> cards = saved.stream().filter(receipt -> receipt.kind() != DevelopmentContextReceipt.Kind.PROPOSE)
                .limit(CARD_LIMIT).map(receipt -> card(receipt,
                        byRef.getOrDefault(receipt.reference(), ImmutableList.of()), usageTruncated))
                .collect(toImmutableList());
        return new Activity(cards, saved.size() > CARD_LIMIT, usageTruncated);
    }

    private Card card(DevelopmentContextReceipt receipt, List<ContextUsageEvent> facts, boolean usageTruncated)
    {
        JsonNode payload;
        try {
            payload = mapper.readTree(receipt.payloadJson());
        }
        catch (JsonProcessingException failure) {
            return new Card(receipt.reference(), receipt.turnId(), receipt.kind(), receipt.createdAt(), 0, "UNKNOWN", 0,
                    "", ImmutableList.of(), ImmutableList.of(), 0, ImmutableList.of(), ImmutableList.of("Saved context details are unavailable."));
        }
        JsonNode data = receipt.kind() == DevelopmentContextReceipt.Kind.SEARCH ? payload.path("result") : payload;
        List<Entry> entries = switch (receipt.kind()) {
            case OFFER -> entries(payload.path("entries"), true);
            case SEARCH -> entries(data.path("hits"), false);
            case READ -> payload.path("note").isObject() ? ImmutableList.of(entry(payload.path("note"), true)) : ImmutableList.of();
            case PROPOSE -> ImmutableList.of();
        };
        List<ScopeState> scopes = scopes(data.path("scopeStates"));
        String delivery = switch (receipt.kind()) {
            case OFFER -> turns.find(TurnId.valueOf(receipt.turnId())).filter(turn -> turn.providerTurnReference().isPresent())
                    .map(ignored -> "CONFIRMED").orElse("PREPARED");
            case SEARCH -> delivery(facts, SEARCH_STAGES, usageTruncated);
            case READ -> delivery(facts, Set.of(ContextUsageEvent.Stage.OPENED), usageTruncated);
            case PROPOSE -> "PREPARED";
        };
        List<String> warnings = receipt.kind() == DevelopmentContextReceipt.Kind.READ
                ? strings(payload.path("limitations")) : ImmutableList.of();
        String query = receipt.kind() == DevelopmentContextReceipt.Kind.SEARCH ? payload.path("query").asText("") : "";
        List<String> paths = receipt.kind() == DevelopmentContextReceipt.Kind.SEARCH ? strings(payload.path("paths")) : ImmutableList.of();
        int bytes = receipt.kind() == DevelopmentContextReceipt.Kind.OFFER
                ? com.bytequay.core.context.ContextLimits.bytes(payload.path("packet").asText(""))
                : com.bytequay.core.context.ContextLimits.bytes(receipt.payloadJson());
        return new Card(receipt.reference(), receipt.turnId(), receipt.kind(), receipt.createdAt(), data.path("notebookRevision").asLong(),
                delivery, bytes, query, paths, entries, data.path("omittedCount").asInt(), scopes, warnings);
    }

    private static String delivery(List<ContextUsageEvent> facts, Set<ContextUsageEvent.Stage> stages, boolean usageTruncated)
    {
        if (facts.stream().anyMatch(fact -> stages.contains(fact.stage()) && fact.delivery() == ContextUsageEvent.Delivery.CONFIRMED)) {
            return "CONFIRMED";
        }
        if (facts.stream().anyMatch(fact -> stages.contains(fact.stage()) && fact.delivery() == ContextUsageEvent.Delivery.UNCERTAIN)) {
            return "UNCERTAIN";
        }
        if (facts.stream().anyMatch(fact -> stages.contains(fact.stage()) && fact.delivery() == ContextUsageEvent.Delivery.PREPARED)) {
            return "PREPARED";
        }
        return usageTruncated ? "UNKNOWN" : "PREPARED";
    }

    private static List<Entry> entries(JsonNode values, boolean includesOffered)
    {
        if (!values.isArray()) {
            return ImmutableList.of();
        }
        return ImmutableList.copyOf(values).stream().limit(100).map(value -> entry(value, includesOffered)).collect(toImmutableList());
    }

    private static Entry entry(JsonNode value, boolean includesOffered)
    {
        return new Entry(value.path("entryId").asText(value.path("id").asText()), value.path("entryVersion").asLong(value.path("version").asLong()),
                value.path("kind").asText(), value.path("scopeId").asText(), value.path("title").asText(),
                strings(value.path("matchReasons")), !includesOffered || value.path("included").asBoolean(true));
    }

    private static List<ScopeState> scopes(JsonNode values)
    {
        if (!values.isArray()) {
            return ImmutableList.of();
        }
        return ImmutableList.copyOf(values).stream().limit(10).map(value -> new ScopeState(value.path("scopeId").asText(),
                value.path("preparation").asText(), value.path("missingKnowledge").asBoolean())).collect(toImmutableList());
    }

    private static List<String> strings(JsonNode values)
    {
        if (!values.isArray()) {
            return ImmutableList.of();
        }
        return ImmutableList.copyOf(values).stream().filter(JsonNode::isTextual).limit(20).map(JsonNode::asText).collect(toImmutableList());
    }

    public record Activity(List<Card> cards, boolean hasMore, boolean usageTruncated) {}

    public record Card(String reference, String turnId, DevelopmentContextReceipt.Kind kind, Instant createdAt, long notebookRevision,
            String delivery, int bytes, String query, List<String> paths, List<Entry> entries, int omittedCount, List<ScopeState> scopeStates,
            List<String> warnings) {}

    public record Entry(String id, long version, String kind, String scopeId, String title, List<String> matchReasons, boolean included) {}

    public record ScopeState(String scopeId, String preparation, boolean missingKnowledge) {}
}
