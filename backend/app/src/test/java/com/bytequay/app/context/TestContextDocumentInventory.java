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

import com.bytequay.core.context.RegisteredContextSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.context.ContextEntryOrigin.USER;
import static com.bytequay.core.context.RegisteredContextSource.Kind.DOCUMENTATION_PAGE;
import static com.bytequay.core.context.RegisteredContextSource.Kind.DOCUMENTATION_ROOT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestContextDocumentInventory
{
    private static final RegisteredContextSource ROOT = new RegisteredContextSource("docs", DOCUMENTATION_ROOT,
            "https://docs.example.com/manual/", USER, Optional.empty());
    private static final RegisteredContextSource PAGE = new RegisteredContextSource("page", DOCUMENTATION_PAGE,
            "https://docs.example.com/snapshot.html", USER, Optional.empty());

    @Test
    void onlyObservedLinksCanBeChosenAndDepthNeverResets()
    {
        ContextDocumentInventory inventory = ContextDocumentInventory.initial(List.of(ROOT));
        ContextDocumentInventory.Item root = inventory.choose(ROOT.id(), Optional.empty());
        ContextDocumentInventory initial = inventory;
        assertThatThrownBy(() -> initial.choose(ROOT.id(), Optional.of("child.html"))).isInstanceOf(IllegalArgumentException.class);

        inventory = inventory.read(root, "read:root", "hash:root").offer(ROOT.id(), root.resolvedUrl(), List.of(link("child.html")), false);
        ContextDocumentInventory.Item child = inventory.choose(ROOT.id(), Optional.of("child.html"));
        assertThat(child.depth()).isEqualTo(1);
        assertThat(child.parentUrl()).contains(root.resolvedUrl());

        inventory = inventory.read(child, "read:child", "hash:child").offer(ROOT.id(), child.resolvedUrl(), List.of(link("grand.html")), false);
        ContextDocumentInventory.Item grand = inventory.choose(ROOT.id(), Optional.of("grand.html"));
        assertThat(grand.depth()).isEqualTo(2);
        inventory = inventory.read(grand, "read:grand", "hash:grand")
                .offer(ROOT.id(), grand.resolvedUrl(), List.of(link("too-deep.html")), false)
                .offer(ROOT.id(), root.resolvedUrl(), List.of(link("grand.html")), false);

        ContextDocumentInventory completed = inventory;
        assertThatThrownBy(() -> completed.choose(ROOT.id(), Optional.of("too-deep.html"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(inventory.choose(ROOT.id(), Optional.of("grand.html")).depth()).isEqualTo(2);
        assertThat(inventory.choose(ROOT.id(), Optional.of("grand.html")).parentUrl()).contains(child.resolvedUrl());
    }

    @Test
    void validatesSourceAndParentBeforeOfferingLinks()
    {
        ContextDocumentInventory inventory = ContextDocumentInventory.initial(List.of(ROOT, PAGE));
        ContextDocumentInventory.Item root = inventory.choose(ROOT.id(), Optional.empty());
        ContextDocumentInventory initial = inventory;
        assertThatThrownBy(() -> initial.offer(ROOT.id(), root.resolvedUrl(), List.of(link("child.html")), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("recorded parent read");
        inventory = inventory.read(root, "read:root", "hash:root");
        ContextDocumentInventory captured = inventory;
        assertThatThrownBy(() -> captured.offer(ROOT.id(), root.resolvedUrl(),
                List.of(new DocumentationHttpReader.Link("https://other.example.com/page.html", Optional.of("page.html"))), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> captured.choose(PAGE.id(), Optional.of("sibling.html"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> captured.offer("unknown", root.resolvedUrl(), List.of(), false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failuresCanBeRetriedButExcludedPagesCannotBeChosen()
    {
        ContextDocumentInventory inventory = ContextDocumentInventory.initial(List.of(ROOT));
        ContextDocumentInventory.Item root = inventory.choose(ROOT.id(), Optional.empty());
        inventory = inventory.failed(root, "Timeout");
        assertThat(inventory.choose(ROOT.id(), Optional.empty()).status()).isEqualTo(ContextDocumentInventory.Status.FAILED);
        inventory = inventory.exclude(inventory.choose(ROOT.id(), Optional.empty()), "No longer relevant");
        ContextDocumentInventory excluded = inventory;
        assertThatThrownBy(() -> excluded.choose(ROOT.id(), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundedInventoryReportsOmittedLinks()
    {
        ContextDocumentInventory inventory = ContextDocumentInventory.initial(List.of(ROOT));
        ContextDocumentInventory.Item root = inventory.choose(ROOT.id(), Optional.empty());
        inventory = inventory.read(root, "read:root", "hash:root");
        List<DocumentationHttpReader.Link> links = new ArrayList<>();
        for (int index = 0; index < 220; index++) {
            links.add(link("page-" + index + ".html"));
        }

        inventory = inventory.offer(ROOT.id(), root.resolvedUrl(), links, false);

        assertThat(inventory.items()).hasSize(ContextDocumentInventory.MAX_ITEMS);
        assertThat(inventory.truncated()).isTrue();
        ContextDocumentInventory bounded = inventory;
        assertThatThrownBy(() -> bounded.choose(ROOT.id(), Optional.of("page-219.html"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retainsOnlyBoundedDistinctSuggestionsAcrossPageUpdates()
    {
        ContextDocumentInventory inventory = ContextDocumentInventory.initial(List.of(ROOT));
        ContextDocumentInventory.Item root = inventory.choose(ROOT.id(), Optional.empty());
        inventory = inventory.suggest(List.of("https://other.example.com/docs/", "https://other.example.com/docs/", ROOT.url()))
                .read(root, "read:root", "hash:root")
                .offer(ROOT.id(), root.resolvedUrl(), List.of(link("child.html")), false);
        assertThat(inventory.suggestedExpansionUrls()).containsExactly("https://other.example.com/docs/");
        assertThat(inventory.withoutSuggestion("https://other.example.com/docs/").suggestedExpansionUrls()).isEmpty();
        ContextDocumentInventory recorded = inventory;
        assertThatThrownBy(() -> recorded.withoutSuggestion("https://unobserved.example.com/"))
                .isInstanceOf(IllegalArgumentException.class);
        List<String> many = new ArrayList<>();
        for (int index = 0; index < ContextDocumentInventory.MAX_SUGGESTIONS + 1; index++) {
            many.add("https://other.example.com/page-" + index);
        }
        ContextDocumentInventory bounded = inventory.suggest(many);
        assertThat(bounded.suggestedExpansionUrls()).hasSize(ContextDocumentInventory.MAX_SUGGESTIONS);
        assertThat(bounded.truncated()).isTrue();
    }

    private static DocumentationHttpReader.Link link(String relativePage)
    {
        return new DocumentationHttpReader.Link(ROOT.url() + relativePage, Optional.of(relativePage));
    }
}
