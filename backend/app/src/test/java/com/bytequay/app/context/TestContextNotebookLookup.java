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
import java.util.List;
import java.util.Optional;

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
    void topicSearchFindsUnmatchedLessonButDoesNotReviveDisabledNotesOrScopes()
    {
        ContextScope iceberg = scope("iceberg", true);
        ContextScope disabled = scope("disabled", false);
        ContextEntry lesson = lesson("lesson:snapshot", iceberg, true, 2);
        ContextEntry hidden = lesson("lesson:hidden", iceberg, false, 1);
        ContextEntry outside = lesson("lesson:outside", disabled, true, 1);
        when(notebooks.getNotebook("workspace")).thenReturn(notebook(ImmutableList.of(iceberg, disabled), ImmutableList.of(lesson, hidden, outside)));

        ContextNotebookLookup.SearchResult found = lookup.search("workspace", new ContextNotebookLookup.SearchQuery("snapshot isolation", ImmutableList.of()));

        assertThat(found.hits()).extracting(ContextNotebookLookup.Hit::entryId).containsExactly("lesson:snapshot");
        assertThat(found.hits().getFirst().matchReasons()).anySatisfy(reason -> assertThat(reason).contains("Topic match only; component not matched"));
        assertThat(found.scopeStates()).extracting(ContextNotebookLookup.ScopeState::scopeId).containsExactly("project");
        assertThat(found.contextState()).isEqualTo("MATCHES");
    }

    @Test
    void exactReadRejectsChangedOrDisabledVersionAndKeepsSavedSources()
    {
        ContextScope iceberg = scope("iceberg", true);
        ContextEntry enabled = lesson("lesson:snapshot", iceberg, true, 2);
        ContextEntry changed = lesson("lesson:snapshot", iceberg, true, 3);
        ContextEntry disabled = lesson("lesson:snapshot", iceberg, false, 2);
        when(notebooks.getNotebook("workspace")).thenReturn(notebook(ImmutableList.of(iceberg), ImmutableList.of(enabled)),
                notebook(ImmutableList.of(iceberg), ImmutableList.of(changed)), notebook(ImmutableList.of(iceberg), ImmutableList.of(disabled)));

        ContextNotebookLookup.Read opened = lookup.readEnabled("workspace", enabled.id(), 2).orElseThrow();
        assertThat(opened.entry()).isEqualTo(enabled);
        assertThat(opened.capture().text()).contains("UNVERIFIED", "Snapshot isolation");
        assertThat(opened.capture().evidence()).containsKey(opened.capture().sourceEvidenceRefs().get("source"));
        assertThat(lookup.readEnabled("workspace", enabled.id(), 2)).isEmpty();
        assertThat(lookup.readEnabled("workspace", enabled.id(), 2)).isEmpty();
    }

    @Test
    void directoryHintsMatchComponentBoundariesAndUnsafeHintsFail()
    {
        ContextScope iceberg = scope("iceberg", true);
        when(notebooks.getNotebook("workspace")).thenReturn(notebook(ImmutableList.of(iceberg), ImmutableList.of(lesson("lesson:snapshot", iceberg, true, 2))));

        ContextNotebookLookup.SearchResult found = lookup.search("workspace", new ContextNotebookLookup.SearchQuery("", ImmutableList.of("plugin/iceberg/")));
        assertThat(found.hits()).extracting(ContextNotebookLookup.Hit::entryId).containsExactly("lesson:snapshot");
        assertThat(found.hits().getFirst().matchReasons()).anySatisfy(reason -> assertThat(reason).contains("matches prefix"));
        assertThatThrownBy(() -> new ContextNotebookLookup.SearchQuery("", ImmutableList.of("../outside/"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextNotebookLookup.SearchQuery("", ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
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

    private static RepositoryNotebook notebook(List<ContextScope> scopes, List<ContextEntry> entries)
    {
        ContextSourceEvidence source = new ContextSourceEvidence("source", new ContextReference.Document("docs", "https://example.com/docs"),
                "Saved example", "Original snapshot passage", ContextSourceEvidence.Capture.BYTEQUAY_READ,
                Optional.of(Instant.parse("2026-01-01T00:00:00Z")), Optional.of("hash"), Optional.of("v1"), false);
        return new RepositoryNotebook(1, 2, true, ImmutableList.of(), ImmutableList.<ContextScope>builder().add(ContextScope.project()).addAll(scopes).build(),
                entries, ImmutableList.of(), ImmutableList.of(source), Optional.empty());
    }
}
