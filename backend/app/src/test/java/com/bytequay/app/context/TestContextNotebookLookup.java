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

import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RepositoryNotebook;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.bytequay.core.context.ContextEntryKind.MEMORY;
import static com.bytequay.core.context.ContextEntryOrigin.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestContextNotebookLookup
{
    private final RepositoryContextService notebooks = mock(RepositoryContextService.class);
    private final ContextNotebookLookup lookup = new ContextNotebookLookup(notebooks, new RepositoryContextMatcher(), new ContextNoteReader(notebooks),
            new ObjectMapper().findAndRegisterModules());

    @Test
    void literalSearchCoversBodyAndReferenceButExcludesDisabledNotesAndScopes()
    {
        ContextScope iceberg = scope("iceberg", true);
        ContextScope disabled = scope("disabled", false);
        ContextEntry lesson = lesson("lesson:snapshot", iceberg, true, 2);
        ContextEntry hidden = lesson("lesson:hidden", iceberg, false, 1);
        ContextEntry outside = lesson("lesson:outside", disabled, true, 1);
        when(notebooks.getNotebook("workspace")).thenReturn(notebook(2, ImmutableList.of(iceberg, disabled), ImmutableList.of(lesson, hidden, outside)));

        assertThat(lookup.search("workspace", new ContextNotebookLookup.SearchQuery("CONSEQUENCE", ImmutableList.of())).hits())
                .extracting(ContextNotebookLookup.Hit::entryId).containsExactly("lesson:snapshot");
        ContextNotebookLookup.SearchResult found = lookup.search("workspace", new ContextNotebookLookup.SearchQuery("example.com/docs", ImmutableList.of()));
        assertThat(found.hits()).extracting(ContextNotebookLookup.Hit::entryId).containsExactly("lesson:snapshot");
        assertThat(found.hits().getFirst().paths()).containsExactly("plugin/iceberg/");
        assertThat(found.contextState()).isEqualTo("MATCHES");
    }

    @Test
    void listPagesInNotebookOrderAndRejectsStaleRevision()
    {
        ContextScope iceberg = scope("iceberg", true);
        List<ContextEntry> entries = IntStream.range(0, 23).mapToObj(index -> lesson("lesson:" + index, iceberg, true, 2)).toList();
        when(notebooks.getNotebook("workspace")).thenReturn(notebook(2, ImmutableList.of(iceberg), entries),
                notebook(2, ImmutableList.of(iceberg), entries), notebook(3, ImmutableList.of(iceberg), entries));

        ContextNotebookLookup.SearchResult first = lookup.list("workspace", 1, Optional.empty());
        assertThat(first.hits()).extracting(ContextNotebookLookup.Hit::entryId)
                .containsExactlyElementsOf(IntStream.range(0, 10).mapToObj(index -> "lesson:" + index).toList());
        assertThat(first.totalMatches()).isEqualTo(23);
        assertThat(first.nextPage()).isEqualTo(2);
        ContextNotebookLookup.SearchResult second = lookup.list("workspace", 2, Optional.of(first.notebookRevision()));
        assertThat(second.hits()).extracting(ContextNotebookLookup.Hit::entryId)
                .containsExactlyElementsOf(IntStream.range(10, 20).mapToObj(index -> "lesson:" + index).toList());
        assertThat(second.nextPage()).isEqualTo(3);
        assertThatThrownBy(() -> lookup.list("workspace", 3, Optional.of(first.notebookRevision())))
                .hasMessageContaining("restart at page 1");
    }

    @Test
    void byteLimitedPagesReachEveryEntryWithoutSkipping()
    {
        String longPath = "plugin/" + "a".repeat(850) + "/";
        ContextScope scope = new ContextScope("wide", "Wide", new ContextMatch.Selected(
                ImmutableList.of(longPath, "plugin/" + "b".repeat(850) + "/"), ImmutableList.of()),
                true, Optional.empty(), Optional.empty());
        List<ContextEntry> entries = IntStream.range(0, 18).mapToObj(index -> lesson("lesson:" + index, scope, true, 2)).toList();
        when(notebooks.getNotebook("workspace")).thenReturn(notebook(2, ImmutableList.of(scope), entries));
        List<String> seen = new ArrayList<>();
        Integer page = 1;
        while (page != null) {
            ContextNotebookLookup.SearchResult result = lookup.list("workspace", page, page == 1 ? Optional.empty() : Optional.of(2L));
            assertThat(new ObjectMapper().valueToTree(result).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(12 * 1024);
            seen.addAll(result.hits().stream().map(ContextNotebookLookup.Hit::entryId).toList());
            if (page == 1) {
                assertThat(result.hits().size()).isLessThan(10);
            }
            page = result.nextPage();
        }
        assertThat(seen).containsExactlyElementsOf(IntStream.range(0, 18).mapToObj(index -> "lesson:" + index).toList());
    }

    @Test
    void noHitPageStillRespectsByteLimit()
    {
        List<ContextScope> scopes = IntStream.range(0, 12).mapToObj(index -> new ContextScope(
                "scope-" + index + "x".repeat(1000), "Area", new ContextMatch.Selected(ImmutableList.of("plugin/area/"), ImmutableList.of()),
                true, Optional.empty(), Optional.empty())).toList();
        when(notebooks.getNotebook("workspace")).thenReturn(notebook(2, scopes, ImmutableList.of()));
        ContextNotebookLookup.SearchResult result = lookup.search("workspace", new ContextNotebookLookup.SearchQuery("", ImmutableList.of("plugin/area/")));
        assertThat(result.hits()).isEmpty();
        assertThat(new ObjectMapper().valueToTree(result).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(12 * 1024);
    }

    @Test
    void exactReadRejectsChangedOrDisabledVersionAndKeepsSavedSources()
    {
        ContextScope iceberg = scope("iceberg", true);
        ContextEntry enabled = lesson("lesson:snapshot", iceberg, true, 2);
        ContextEntry changed = lesson("lesson:snapshot", iceberg, true, 3);
        ContextEntry disabled = lesson("lesson:snapshot", iceberg, false, 2);
        when(notebooks.getNotebook("workspace")).thenReturn(notebook(2, ImmutableList.of(iceberg), ImmutableList.of(enabled)),
                notebook(3, ImmutableList.of(iceberg), ImmutableList.of(changed)), notebook(4, ImmutableList.of(iceberg), ImmutableList.of(disabled)));

        ContextNotebookLookup.Read opened = lookup.readEnabled("workspace", enabled.id(), 2).orElseThrow();
        assertThat(opened.entry()).isEqualTo(enabled);
        assertThat(opened.capture().text()).contains("UNVERIFIED", "Snapshot isolation");
        assertThat(opened.capture().evidence()).containsKey(opened.capture().sourceEvidenceRefs().get("source"));
        assertThat(lookup.readEnabled("workspace", enabled.id(), 2)).isEmpty();
        assertThat(lookup.readEnabled("workspace", enabled.id(), 2)).isEmpty();
    }

    @Test
    void pathFilterUsesComponentBoundaryAndAllowsUnfilteredList()
    {
        ContextScope iceberg = scope("iceberg", true);
        ContextScope delta = scope("delta", true);
        when(notebooks.getNotebook("workspace")).thenReturn(notebook(2, ImmutableList.of(iceberg, delta),
                ImmutableList.of(lesson("lesson:iceberg", iceberg, true, 2), lesson("lesson:delta", delta, true, 2))));

        ContextNotebookLookup.SearchResult found = lookup.search("workspace", new ContextNotebookLookup.SearchQuery("", ImmutableList.of("plugin/iceberg/")));
        assertThat(found.hits()).extracting(ContextNotebookLookup.Hit::entryId).containsExactly("lesson:iceberg");
        assertThatThrownBy(() -> new ContextNotebookLookup.SearchQuery("", ImmutableList.of("../outside/"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ContextNotebookLookup.SearchQuery("", ImmutableList.of()).page()).isEqualTo(1);
        assertThatThrownBy(() -> new ContextNotebookLookup.SearchQuery("", ImmutableList.of(), 2, Optional.empty()))
                .hasMessageContaining("notebookRevision");
    }

    private static ContextScope scope(String id, boolean enabled)
    {
        return new ContextScope(id, id, new ContextMatch.Selected(ImmutableList.of("plugin/" + id + "/"), ImmutableList.of()),
                enabled, Optional.empty(), Optional.empty());
    }

    private static ContextEntry lesson(String id, ContextScope scope, boolean enabled, long version)
    {
        return new ContextEntry(id, MEMORY, scope.id(), "Snapshot isolation", "Avoid a stale snapshot", "Snapshot isolation consequence",
                ImmutableList.of(), ImmutableList.of("source"), USER, enabled, version);
    }

    private static RepositoryNotebook notebook(long revision, List<ContextScope> scopes, List<ContextEntry> entries)
    {
        ContextSourceEvidence source = new ContextSourceEvidence("source", new ContextReference.Document("docs", "https://example.com/docs"),
                "Saved example", "Original snapshot passage", ContextSourceEvidence.Capture.BYTEQUAY_READ,
                Optional.of(Instant.parse("2026-01-01T00:00:00Z")), Optional.of("hash"), Optional.of("v1"), false);
        return new RepositoryNotebook(1, revision, true, ImmutableList.of(), ImmutableList.<ContextScope>builder().add(ContextScope.project()).addAll(scopes).build(),
                entries, ImmutableList.of(source), Optional.empty());
    }
}
