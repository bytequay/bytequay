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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.MemoryItem;
import com.bytequay.app.domain.MemoryItemConfidence;
import com.bytequay.app.domain.MemoryItemKind;
import com.bytequay.app.domain.MemoryItemOrigin;
import com.bytequay.app.domain.MemoryItemScopeKind;
import com.bytequay.app.domain.MemoryItemSource;
import com.bytequay.app.repository.MemoryItemStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip exercise over the typed-item flow: propose → listPending
 * → applyItem → listLive → renderToMarkdown. The Spring boot here
 * gives us the real SQLite + Flyway-migrated schema so column types,
 * JSON serialisation, and the per-scope queries all run for real.
 */
@SpringBootTest
class TestMemoryItemService
{
    @Autowired
    private MemoryItemService service;

    private static String uniqueScope()
    {
        return "ws-test-" + UUID.randomUUID();
    }

    private static MemoryItemStore.NewItem decisionItem(String scopeId, String text)
    {
        return new MemoryItemStore.NewItem(
                MemoryItemScopeKind.WORKSPACE,
                scopeId,
                MemoryItemKind.DECISION,
                text,
                List.of(MemoryItemSource.thread("thread-1")),
                MemoryItemConfidence.HIGH,
                List.of(),
                MemoryItemOrigin.DISTILL);
    }

    @Test
    void proposeListsAsPending()
    {
        String scope = uniqueScope();

        MemoryItem proposed = service.propose(decisionItem(scope, "Use Java 25 across the backend."));

        assertThat(proposed.isPending()).isTrue();
        assertThat(service.listPending(MemoryItemScopeKind.WORKSPACE, scope))
                .extracting(MemoryItem::id)
                .containsExactly(proposed.id());
        assertThat(service.listLive(MemoryItemScopeKind.WORKSPACE, scope)).isEmpty();
    }

    @Test
    void applyMovesItemToLive()
    {
        String scope = uniqueScope();
        MemoryItem proposed = service.propose(decisionItem(scope, "Use SQLite for local storage."));

        MemoryItem applied = service.applyItem(proposed.id());

        assertThat(applied.isPending()).isFalse();
        assertThat(applied.isLive()).isTrue();
        assertThat(service.listLive(MemoryItemScopeKind.WORKSPACE, scope))
                .extracting(MemoryItem::id)
                .containsExactly(applied.id());
        assertThat(service.listPending(MemoryItemScopeKind.WORKSPACE, scope)).isEmpty();
    }

    @Test
    void discardRemovesPendingItem()
    {
        String scope = uniqueScope();
        MemoryItem proposed = service.propose(decisionItem(scope, "Throwaway draft."));

        service.discardItem(proposed.id());

        assertThat(service.listPending(MemoryItemScopeKind.WORKSPACE, scope)).isEmpty();
    }

    @Test
    void discardAppliedItemIsRejected()
    {
        String scope = uniqueScope();
        MemoryItem proposed = service.propose(decisionItem(scope, "Already applied."));
        service.applyItem(proposed.id());

        assertThatThrownBy(() -> service.discardItem(proposed.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already applied");
    }

    @Test
    void proposeRejectsEmptySources()
    {
        String scope = uniqueScope();
        MemoryItemStore.NewItem noSources = new MemoryItemStore.NewItem(
                MemoryItemScopeKind.WORKSPACE, scope, MemoryItemKind.DECISION,
                "Should be rejected.", List.of(), MemoryItemConfidence.HIGH,
                List.of(), MemoryItemOrigin.DISTILL);

        assertThatThrownBy(() -> service.propose(noSources))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at least one source");
    }

    @Test
    void renderProducesSectionedMarkdown()
    {
        String scope = uniqueScope();
        MemoryItem decision = service.propose(decisionItem(scope, "Pin Spring Boot 3.5."));
        MemoryItem convention = service.propose(new MemoryItemStore.NewItem(
                MemoryItemScopeKind.WORKSPACE, scope, MemoryItemKind.CONVENTION,
                "Tests use @SpringBootTest only for integration cases.",
                List.of(MemoryItemSource.thread("thread-2")),
                MemoryItemConfidence.MEDIUM, List.of(), MemoryItemOrigin.DISTILL));
        service.applyItem(decision.id());
        service.applyItem(convention.id());

        String md = service.renderToMarkdown(MemoryItemScopeKind.WORKSPACE, scope);

        assertThat(md).contains("## Decisions");
        assertThat(md).contains("- Pin Spring Boot 3.5.");
        assertThat(md).contains("## Conventions");
        assertThat(md).contains("- Tests use @SpringBootTest only for integration cases.");
        // Sectioning order: Decisions before Conventions
        assertThat(md.indexOf("## Decisions")).isLessThan(md.indexOf("## Conventions"));
    }

    @Test
    void applyAllPendingMovesEveryRow()
    {
        String scope = uniqueScope();
        service.propose(decisionItem(scope, "Decision 1."));
        service.propose(decisionItem(scope, "Decision 2."));

        int applied = service.applyAllPending(MemoryItemScopeKind.WORKSPACE, scope);

        assertThat(applied).isEqualTo(2);
        assertThat(service.listPending(MemoryItemScopeKind.WORKSPACE, scope)).isEmpty();
        assertThat(service.listLive(MemoryItemScopeKind.WORKSPACE, scope)).hasSize(2);
    }

    @Test
    void discardAllPendingClearsEveryRow()
    {
        String scope = uniqueScope();
        service.propose(decisionItem(scope, "Drop me."));
        service.propose(decisionItem(scope, "And me."));

        int dropped = service.discardAllPending(MemoryItemScopeKind.WORKSPACE, scope);

        assertThat(dropped).isEqualTo(2);
        assertThat(service.listPending(MemoryItemScopeKind.WORKSPACE, scope)).isEmpty();
    }
}
