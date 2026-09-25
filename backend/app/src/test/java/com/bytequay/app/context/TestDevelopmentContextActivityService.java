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
import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.core.context.DevelopmentContextReceipt;
import com.bytequay.core.context.DevelopmentContextReceiptStore;
import com.bytequay.core.store.ContextUsageStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestDevelopmentContextActivityService
{
    private final DevelopmentContextReceiptStore receipts = mock(DevelopmentContextReceiptStore.class);
    private final ContextUsageStore usage = mock(ContextUsageStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final DevelopmentContextActivityService service = new DevelopmentContextActivityService(receipts, usage, turns, new ObjectMapper());

    @Test
    void showsExactMetadataAndTruthfulDeliveryWithoutLeakingNoteBody()
    {
        String turnId = TurnId.random().toString();
        String body = "PRIVATE_NOTE_BODY";
        DevelopmentContextReceipt offer = receipt(turnId, "context-offer:one", DevelopmentContextReceipt.Kind.OFFER,
                "{\"notebookRevision\":7,\"packet\":\"" + body + "\",\"entries\":[{\"entryId\":\"lesson:one\",\"entryVersion\":3,"
                        + "\"kind\":\"MEMORY\",\"scopeId\":\"project\",\"title\":\"Cache checks\",\"included\":true,"
                        + "\"matchReasons\":[\"Changed path\"]}]} ");
        DevelopmentContextReceipt search = receipt(turnId, "context-search:one", DevelopmentContextReceipt.Kind.SEARCH,
                "{\"query\":\"cache\",\"paths\":[\"src/cache/\"],\"result\":{\"notebookRevision\":7,"
                        + "\"hits\":[{\"entryId\":\"lesson:one\",\"entryVersion\":3,\"title\":\"Cache checks\","
                        + "\"kind\":\"MEMORY\",\"scopeId\":\"project\",\"matchReasons\":[\"Topic\"],\"text\":\""
                        + body + "\"}]}}");
        when(receipts.listTask("task", 101)).thenReturn(List.of(search, offer));
        when(turns.find(TurnId.valueOf(turnId))).thenReturn(Optional.empty());
        ContextUsageEvent found = new ContextUsageEvent("workspace", "task", Optional.of(turnId), Optional.empty(),
                ContextUsageEvent.TaskKind.DEVELOPMENT, ContextUsageEvent.Stage.FOUND, ContextUsageEvent.Delivery.CONFIRMED,
                search.reference(), Optional.of(new ContextUsageEvent.Note("lesson:one", 3, ContextEntryKind.MEMORY, "project", "Cache checks")),
                Optional.empty(), Optional.empty(), Instant.EPOCH);
        when(usage.listTask("workspace", "task", 1001)).thenReturn(List.of(found));

        DevelopmentContextActivityService.Activity activity = service.activity("workspace", "task");

        assertThat(activity.cards()).hasSize(2);
        assertThat(activity.cards().get(0).delivery()).isEqualTo("CONFIRMED");
        assertThat(activity.cards().get(0).query()).isEqualTo("cache");
        assertThat(activity.cards().get(0).paths()).containsExactly("src/cache/");
        assertThat(activity.cards().get(1).delivery()).isEqualTo("PREPARED");
        assertThat(activity.cards().get(1).entries().get(0).version()).isEqualTo(3);
        assertThat(activity.cards().get(1).entries().get(0).matchReasons()).containsExactly("Changed path");
        assertThat(activity.toString()).doesNotContain(body);
    }

    @Test
    void exactReadShowsVersionButNeverSavedTextOrSourceExcerpt()
    {
        String turnId = TurnId.random().toString();
        DevelopmentContextReceipt read = receipt(turnId, "context-note:one", DevelopmentContextReceipt.Kind.READ,
                "{\"notebookRevision\":8,\"note\":{\"id\":\"memory:one\",\"version\":4,\"kind\":\"MEMORY\","
                        + "\"scopeId\":\"project\",\"title\":\"Cache checks\"},\"text\":\"PRIVATE_NOTE_BODY\","
                        + "\"sources\":[{\"excerpt\":\"PRIVATE_SOURCE\"}],\"limitations\":[\"Check source versions\"]}");
        when(receipts.listTask("task", 101)).thenReturn(List.of(read));
        when(usage.listTask("workspace", "task", 1001)).thenReturn(List.of());

        DevelopmentContextActivityService.Activity activity = service.activity("workspace", "task");

        assertThat(activity.cards().getFirst().entries().getFirst().version()).isEqualTo(4);
        assertThat(activity.cards().getFirst().delivery()).isEqualTo("PREPARED");
        assertThat(activity.toString()).doesNotContain("PRIVATE_NOTE_BODY", "PRIVATE_SOURCE");
    }

    private static DevelopmentContextReceipt receipt(String turnId, String reference, DevelopmentContextReceipt.Kind kind, String payload)
    {
        return new DevelopmentContextReceipt("task", turnId, reference, reference, kind, "request", payload, "digest", Instant.EPOCH);
    }
}
