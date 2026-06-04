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
package com.bytequay.app.service.concepts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-function coverage for the {@code ## Glossary} markdown
 * parser. Each test pins one shape the user might write in their
 * brain memory.
 */
class TestWorkspaceGlossaryParser
{
    private final WorkspaceGlossaryParser parser = new WorkspaceGlossaryParser();

    @Test
    void emptyBodyReturnsEmpty()
    {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("   \n   ")).isEmpty();
    }

    @Test
    void bodyWithNoGlossaryReturnsEmpty()
    {
        String body = "## Decisions\n- pin the API version\n\n## Blockers\n- waiting on a key";
        assertThat(parser.parse(body)).isEmpty();
    }

    @Test
    void singleEntryParsesNameAndDefinition()
    {
        String body = """
                ## Glossary

                ### urgent
                PRs that need to be looked at first — CI failing or stale.
                """;
        List<WorkspaceGlossaryParser.Entry> entries = parser.parse(body);
        assertThat(entries).hasSize(1);
        WorkspaceGlossaryParser.Entry urgent = entries.get(0);
        assertThat(urgent.name()).isEqualTo("urgent");
        assertThat(urgent.definition()).contains("CI failing");
        assertThat(urgent.aka()).isEmpty();
    }

    @Test
    void multipleEntriesArePreservedInOrder()
    {
        String body = """
                ## Glossary

                ### urgent
                A.

                ### shippable
                B.

                ### stale
                C.
                """;
        List<WorkspaceGlossaryParser.Entry> entries = parser.parse(body);
        assertThat(entries).extracting(WorkspaceGlossaryParser.Entry::name)
                .containsExactly("urgent", "shippable", "stale");
    }

    @Test
    void definitionLinesAreJoinedWithSpaces()
    {
        String body = """
                ## Glossary

                ### urgent
                first line of the definition,
                second line carries on,
                third line ends it.
                """;
        List<WorkspaceGlossaryParser.Entry> entries = parser.parse(body);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).definition())
                .isEqualTo("first line of the definition, second line carries on, third line ends it.");
    }

    @Test
    void akaLineIsExtracted()
    {
        String body = """
                ## Glossary

                ### urgent
                Look at me first.
                *aka:* needs-attention-now, must-fix
                """;
        List<WorkspaceGlossaryParser.Entry> entries = parser.parse(body);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).aka()).containsExactly("needs-attention-now", "must-fix");
    }

    @Test
    void akaLineAcceptsUnderscoreEmphasis()
    {
        String body = """
                ## Glossary

                ### urgent
                Definition body.
                _aka:_ needs-attention
                """;
        List<WorkspaceGlossaryParser.Entry> entries = parser.parse(body);
        assertThat(entries.get(0).aka()).containsExactly("needs-attention");
    }

    @Test
    void glossaryHeadingIsCaseInsensitive()
    {
        String body = """
                ## GLOSSARY

                ### urgent
                Whatever.
                """;
        assertThat(parser.parse(body)).hasSize(1);
    }

    @Test
    void definitionLessEntryIsDropped()
    {
        String body = """
                ## Glossary

                ### urgent
                ### shippable
                Has a definition.
                """;
        // The first entry has no body, so it should be dropped; the
        // second one survives.
        List<WorkspaceGlossaryParser.Entry> entries = parser.parse(body);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).name()).isEqualTo("shippable");
    }

    @Test
    void laterH2EndsGlossarySection()
    {
        String body = """
                ## Glossary

                ### urgent
                In glossary.

                ## Decisions
                - this is not a concept

                ### not-a-term
                This shouldn't be parsed because the H2 above closed the section.
                """;
        List<WorkspaceGlossaryParser.Entry> entries = parser.parse(body);
        assertThat(entries).extracting(WorkspaceGlossaryParser.Entry::name)
                .containsExactly("urgent");
    }
}
