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
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.RepositoryNotebook;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static com.bytequay.core.context.ContextEntryKind.KNOWLEDGE;
import static com.bytequay.core.context.ContextEntryOrigin.USER;
import static java.util.Collections.nCopies;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRepositoryContextMatcher
{
    private final RepositoryContextMatcher matcher = new RepositoryContextMatcher();

    @Test
    void pathAndTermAlternativesSelectScopesInSavedOrder()
    {
        ContextScope term = selected("term", ImmutableList.of(), ImmutableList.of("snapshot"));
        ContextScope path = selected("path", ImmutableList.of("plugin/iceberg/"), ImmutableList.of("snapshot"));
        ContextScope unrelated = selected("unrelated", ImmutableList.of("plugin/hive/"), ImmutableList.of());
        RepositoryNotebook notebook = notebook(ImmutableList.of(ContextScope.project(), term, path, unrelated), ImmutableList.of());

        RepositoryContextMatcher.Selection selected = matcher.select(notebook, ImmutableList.of("plugin/iceberg/Metadata.java"), "snapshot change");
        assertThat(selected.scopes()).extracting(match -> match.scope().id()).containsExactly("project", "term", "path");
        assertThat(selected.scopes().get(2).matchReasons()).containsExactly("Changed path matches prefix: plugin/iceberg/", "Task text contains term: snapshot");
        assertThatThrownBy(() -> selected.scopes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(matcher.select(notebook, ImmutableList.of("plugin/iceberg/Metadata.java"), "snapshot change")).isEqualTo(selected);
    }

    @Test
    void pathsAreLiteralAndTermsUseUnicodeWholePhraseBoundaries()
    {
        ContextScope iceberg = selected("iceberg", ImmutableList.of("plugin/iceberg/"), ImmutableList.of("snapshot isolation"));
        ContextScope cafe = selected("cafe", ImmutableList.of(), ImmutableList.of("café"));
        ContextScope cpp = selected("cpp", ImmutableList.of(), ImmutableList.of("C++"));
        RepositoryNotebook notebook = notebook(ImmutableList.of(ContextScope.project(), iceberg, cafe, cpp), ImmutableList.of());

        assertThat(matcher.select(notebook, ImmutableList.of("plugin/iceberg/Metadata.java"), "").scopes())
                .extracting(match -> match.scope().id()).containsExactly("project", "iceberg");
        assertThat(matcher.select(notebook, ImmutableList.of("plugin/iceberg-extra/Metadata.java"), "SNAPSHOT\u00a0\n isolation").scopes())
                .extracting(match -> match.scope().id()).containsExactly("project", "iceberg");
        assertThat(matcher.select(notebook, ImmutableList.of("Plugin/iceberg/Metadata.java"), "snapshot isolations").scopes())
                .extracting(match -> match.scope().id()).containsExactly("project");
        assertThat(matcher.select(notebook, ImmutableList.of(), "A CAFE\u0301 or C++ implementation").scopes())
                .extracting(match -> match.scope().id()).containsExactly("project", "cafe", "cpp");
        assertThat(RepositoryContextMatcher.matchesTerm("Related C++17 discussion", "C++")).isFalse();
        assertThat(RepositoryContextMatcher.matchesTerm("Related CAFE\u0301 discussion", "café")).isTrue();
    }

    @Test
    void focusedEnabledKnowledgeSatisfiesScopePresenceButDisabledNotesDoNot()
    {
        ContextScope iceberg = selected("iceberg", ImmutableList.of("plugin/iceberg/"), ImmutableList.of());
        ContextScope delta = selected("delta", ImmutableList.of("plugin/delta/"), ImmutableList.of());
        ContextScope disabled = new ContextScope("disabled", "Disabled", new ContextMatch.Selected(ImmutableList.of("plugin/"), ImmutableList.of()),
                false, Optional.empty(), Optional.empty());
        ContextEntry focused = new ContextEntry("knowledge:iceberg:snapshot", KNOWLEDGE, iceberg.id(), "Snapshot", "Summary", "Body",
                ImmutableList.of(), ImmutableList.of(), USER, true, 1);
        ContextEntry hidden = new ContextEntry(ContextEntry.knowledgeId(delta.id()), KNOWLEDGE, delta.id(), "Delta", "Summary", "Body",
                ImmutableList.of(), ImmutableList.of(), USER, false, 1);
        RepositoryNotebook notebook = notebook(ImmutableList.of(ContextScope.project(), iceberg, delta, disabled), ImmutableList.of(focused, hidden));

        RepositoryContextMatcher.Selection selected = matcher.select(notebook,
                ImmutableList.of("plugin/iceberg/Metadata.java", "plugin/delta/Table.java"), "");
        assertThat(selected.scopes()).extracting(match -> match.scope().id()).containsExactly("project", "iceberg", "delta");
        assertThat(selected.scopes()).extracting(RepositoryContextMatcher.ScopeMatch::missingKnowledge).containsExactly(true, false, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/etc/passwd", "../Metadata.java", "plugin/../Metadata.java", "./Metadata.java", "plugin//Metadata.java",
            "-option", "plugin\\Metadata.java", "plugin/\u0000.java"})
    void rejectsUnsafeOrNoncanonicalPaths(String path)
    {
        assertThatThrownBy(() -> matcher.select(RepositoryNotebook.empty(), ImmutableList.of(path), "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedInputsWithoutPartialSelection()
    {
        assertThatThrownBy(() -> matcher.select(RepositoryNotebook.empty(), ImmutableList.of(), "你".repeat(87_382)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Task text");
        assertThatThrownBy(() -> matcher.select(RepositoryNotebook.empty(), nCopies(10_001, "file.java"), ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Too many changed paths");
        assertThatThrownBy(() -> matcher.select(RepositoryNotebook.empty(), nCopies(2_000, "x".repeat(600)), ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Changed paths");
    }

    private static ContextScope selected(String id, List<String> paths, List<String> terms)
    {
        return new ContextScope(id, id, new ContextMatch.Selected(paths, terms), true, Optional.empty(), Optional.empty());
    }

    private static RepositoryNotebook notebook(List<ContextScope> scopes, List<ContextEntry> entries)
    {
        return new RepositoryNotebook(1, 1, true, ImmutableList.of(), scopes, entries, ImmutableList.of(), Optional.empty());
    }
}
