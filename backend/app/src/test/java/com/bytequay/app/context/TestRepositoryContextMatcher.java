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

import static com.bytequay.app.context.RepositoryContextMatcher.Rank.EXPLICIT;
import static com.bytequay.app.context.RepositoryContextMatcher.Rank.PATH;
import static com.bytequay.app.context.RepositoryContextMatcher.Rank.REPOSITORY;
import static com.bytequay.app.context.RepositoryContextMatcher.Rank.TERM;
import static com.bytequay.core.context.ContextEntryKind.KNOWLEDGE;
import static com.bytequay.core.context.ContextEntryOrigin.USER;
import static java.util.Collections.nCopies;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRepositoryContextMatcher
{
    private final RepositoryContextMatcher matcher = new RepositoryContextMatcher();

    @Test
    void ranksExplicitRepositoryPathAndTermWhilePreservingSavedOrder()
    {
        ContextScope term = selected("term", ImmutableList.of(), ImmutableList.of("snapshot"));
        ContextScope pathZ = selected("z", ImmutableList.of("plugin/iceberg/"), ImmutableList.of("snapshot"));
        ContextScope pathA = selected("a", ImmutableList.of("plugin/iceberg/"), ImmutableList.of());
        ContextScope explicit = selected("explicit", ImmutableList.of("unrelated/"), ImmutableList.of());
        RepositoryNotebook notebook = notebook(ImmutableList.of(ContextScope.project(), term, pathA, explicit, pathZ),
                ImmutableList.of(note(term, true), note(pathZ, true), note(explicit, true), note(ContextScope.project(), true), note(pathA, true)));

        RepositoryContextMatcher.Selection result = matcher.select(notebook, ImmutableList.of("plugin/iceberg/Metadata.java"), "snapshot change",
                ImmutableList.of(ContextEntry.knowledgeId(explicit.id())));

        assertThat(result.entries()).extracting(match -> match.entry().scopeId()).containsExactly("explicit", "project", "z", "a", "term");
        assertThat(result.entries()).extracting(RepositoryContextMatcher.Match::rank).containsExactly(EXPLICIT, REPOSITORY, PATH, PATH, TERM);
        assertThat(result.entries().get(2).matchReasons()).containsExactly("Changed path matches prefix: plugin/iceberg/", "Task text contains term: snapshot");
        assertThat(result.scopes()).extracting(match -> match.scope().id()).containsExactly("explicit", "project", "a", "z", "term");
        assertThat(matcher.select(notebook, ImmutableList.of("plugin/iceberg/Metadata.java"), "snapshot change",
                ImmutableList.of(ContextEntry.knowledgeId(explicit.id())))).isEqualTo(result);
        assertThatThrownBy(() -> result.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void literalComponentPathsAndTermsAreIndependentAlternatives()
    {
        ContextScope iceberg = selected("iceberg", ImmutableList.of("plugin/iceberg/"), ImmutableList.of("snapshot isolation"));
        RepositoryNotebook notebook = notebook(ImmutableList.of(ContextScope.project(), iceberg), ImmutableList.of(note(iceberg, true)));

        assertThat(matcher.select(notebook, ImmutableList.of("plugin/iceberg/Metadata.java"), "", ImmutableList.of()).entries())
                .extracting(RepositoryContextMatcher.Match::rank).containsExactly(PATH);
        assertThat(matcher.select(notebook, ImmutableList.of("plugin/iceberg-extra/Metadata.java"), "SNAPSHOT\u00a0\n isolation", ImmutableList.of()).entries())
                .extracting(RepositoryContextMatcher.Match::rank).containsExactly(TERM);
        assertThat(matcher.select(notebook, ImmutableList.of("plugin/iceberg-extra/Metadata.java"), "snapshot isolations", ImmutableList.of()).entries()).isEmpty();
        assertThat(matcher.select(notebook, ImmutableList.of("Plugin/iceberg/Metadata.java"), "presnapshot isolation", ImmutableList.of()).entries()).isEmpty();
    }

    @Test
    void unicodeTermsUseWholeBoundariesAndLiteralPunctuation()
    {
        ContextScope cafe = selected("cafe", ImmutableList.of(), ImmutableList.of("café"));
        ContextScope cpp = selected("cpp", ImmutableList.of(), ImmutableList.of("C++"));
        RepositoryNotebook notebook = notebook(ImmutableList.of(ContextScope.project(), cafe, cpp), ImmutableList.of(note(cafe, true), note(cpp, true)));

        assertThat(matcher.select(notebook, ImmutableList.of(), "A CAFE\u0301 or C++ implementation", ImmutableList.of()).entries())
                .extracting(match -> match.entry().scopeId()).containsExactly("cafe", "cpp");
        assertThat(matcher.select(notebook, ImmutableList.of(), "caféine café_mode café2 C++17", ImmutableList.of()).entries()).isEmpty();
        assertThat(RepositoryContextMatcher.matchesTerm("Related CAFE\u0301 discussion", "café")).isTrue();
        assertThat(RepositoryContextMatcher.matchesTerm("Related C++17 discussion", "C++")).isFalse();
    }

    @Test
    void bothRenamePathsSelectTheirComponentsWithoutChangingUnicodePathSpelling()
    {
        ContextScope old = selected("old", ImmutableList.of("plugin/old/"), ImmutableList.of());
        ContextScope renamed = selected("renamed", ImmutableList.of("plugin/新/"), ImmutableList.of());
        RepositoryNotebook notebook = notebook(ImmutableList.of(ContextScope.project(), old, renamed), ImmutableList.of(note(old, true), note(renamed, true)));

        assertThat(matcher.select(notebook, ImmutableList.of("plugin/old/Table.java", "plugin/新/Table.java", "plugin/新/Table.java"), "", ImmutableList.of()).entries())
                .extracting(match -> match.entry().scopeId()).containsExactly("old", "renamed");
    }

    @Test
    void explicitSelectionCannotReviveDisabledScopesNotesOrSuppression()
    {
        ContextScope disabledScope = new ContextScope("disabled-scope", "Disabled", new ContextMatch.Selected(ImmutableList.of("plugin/"), ImmutableList.of()), false);
        ContextScope disabledNote = selected("disabled-note", ImmutableList.of("plugin/"), ImmutableList.of());
        ContextScope suppressed = selected("suppressed", ImmutableList.of("plugin/"), ImmutableList.of());
        ContextScope missing = selected("missing", ImmutableList.of("plugin/"), ImmutableList.of());
        RepositoryNotebook notebook = new RepositoryNotebook(1, 1, true, ImmutableList.of(),
                ImmutableList.of(ContextScope.project(), disabledScope, disabledNote, suppressed, missing),
                ImmutableList.of(note(disabledScope, true), note(disabledNote, false)), ImmutableList.of(ContextEntry.knowledgeId(suppressed.id())), ImmutableList.of());

        RepositoryContextMatcher.Selection result = matcher.select(notebook, ImmutableList.of("plugin/Table.java"), "",
                ImmutableList.of(ContextEntry.knowledgeId(disabledScope.id()), ContextEntry.knowledgeId(disabledNote.id()), ContextEntry.knowledgeId(suppressed.id())));

        assertThat(result.entries()).isEmpty();
        assertThat(result.scopes()).extracting(match -> match.scope().id()).containsExactly("project", "disabled-note", "suppressed", "missing");
        assertThat(result.scopes()).extracting(RepositoryContextMatcher.ScopeMatch::missingKnowledge).containsExactly(true, false, false, true);
        assertThat(result.scopes()).noneMatch(match -> match.rank() == EXPLICIT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/etc/passwd", "../Metadata.java", "plugin/../Metadata.java", "./Metadata.java", "plugin//Metadata.java",
            "-option", "plugin\\Metadata.java", "plugin/", "plugin/\u0000.java"})
    void rejectsUnsafeOrNoncanonicalPaths(String path)
    {
        assertThatThrownBy(() -> matcher.select(RepositoryNotebook.empty(), ImmutableList.of(path), "", ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedInputsAndUnknownExplicitNotesWithoutPartialSelection()
    {
        assertThatThrownBy(() -> matcher.select(RepositoryNotebook.empty(), ImmutableList.of(), "你".repeat(87_382), ImmutableList.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Task text");
        assertThatThrownBy(() -> matcher.select(RepositoryNotebook.empty(), nCopies(10_001, "file.java"), "", ImmutableList.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Too many changed paths");
        assertThatThrownBy(() -> matcher.select(RepositoryNotebook.empty(), nCopies(2_000, "x".repeat(600)), "", ImmutableList.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Changed paths");
        assertThatThrownBy(() -> matcher.select(RepositoryNotebook.empty(), ImmutableList.of(), "", ImmutableList.of("knowledge:unknown")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown explicit entry");
    }

    private static ContextScope selected(String id, List<String> paths, List<String> terms)
    {
        return new ContextScope(id, id, new ContextMatch.Selected(paths, terms), true);
    }

    private static ContextEntry note(ContextScope scope, boolean enabled)
    {
        return new ContextEntry(ContextEntry.knowledgeId(scope.id()), KNOWLEDGE, scope.id(), scope.title(), "Summary", "Body",
                ImmutableList.of(), ImmutableList.of(), USER, enabled, 1);
    }

    private static RepositoryNotebook notebook(List<ContextScope> scopes, List<ContextEntry> entries)
    {
        return new RepositoryNotebook(1, 1, true, ImmutableList.of(), scopes, entries, ImmutableList.of(), ImmutableList.of());
    }
}
