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
package com.bytequay.app.review;

import com.bytequay.core.domain.DiffFile;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestReviewFacts
{
    private static final String TEMPLATE = """
            <!-- Thank you for the pull request -->
            ## Description

            ## Release notes

            ( ) This is not user-visible or is docs only, and no release notes are required.
            ( ) Release notes are required. Please propose a release note for me.
            ( ) Release notes are required, with the following suggested text:

            ```markdown
            ## Section
            * Fix some things. ({issue}`issuenumber`)
            ```
            """;

    @Test
    void measuresCommitMessagesWithoutJudgingThem()
    {
        List<String> facts = ReviewFacts.describe(
                ImmutableList.of(
                        new ReviewFacts.Commit("a".repeat(40), "Add the loader", "Why it exists.\n\nTwo more words", ImmutableList.of()),
                        new ReviewFacts.Commit("b".repeat(40), "fixup! Add the loader", "", ImmutableList.of()),
                        new ReviewFacts.Commit("c".repeat(40), "WIP: try the pool.", "", ImmutableList.of()),
                        new ReviewFacts.Commit("d".repeat(40), "Wipe the cache", "", ImmutableList.of()),
                        new ReviewFacts.Commit("e".repeat(40), "Merge branch 'master' into feature", "", ImmutableList.of())),
                Optional.empty(), Optional.empty(), ImmutableList.of());

        assertThat(facts).containsExactly(
                "commit aaaaaaaaaaa \"Add the loader\": subject 14 characters, body 6 words",
                "commit bbbbbbbbbbb \"fixup! Add the loader\": subject 21 characters, body 0 words; a fixup commit",
                "commit ccccccccccc \"WIP: try the pool.\": subject 18 characters, body 0 words; a work-in-progress subject; the subject ends with a period",
                "commit ddddddddddd \"Wipe the cache\": subject 14 characters, body 0 words",
                "commit eeeeeeeeeee \"Merge branch 'master' into feature\": subject 34 characters, body 0 words; a merge commit");
    }

    @Test
    void reportsTheOptionsTheDescriptionMarksUnderATemplateSection()
    {
        String asksForANote = """
                ## Description
                Parallel loading.

                ## Release notes

                () This is not user-visible or is docs only, and no release notes are required.
                (x ) Release notes are required. Please propose a release note for me.
                ( ) Release notes are required, with the following suggested text:

                ```markdown
                ## Section
                * Parallelize loading ({issue}`issuenumber`)
                ```
                """;

        assertThat(facts(asksForANote))
                .containsExactly("description section \"Release notes\": marked \"Release notes are required. Please propose a release note for me.\"");
        assertThat(facts(asksForANote.replace("(x )", "( )"))).containsExactly("description section \"Release notes\": no option is marked");
        assertThat(facts(asksForANote.replace("() This", "(X) This")).getFirst()).startsWith("description section \"Release notes\": 2 options are marked: marked \"This is not");
        assertThat(facts("## Description\nNo other section.")).containsExactly("the description has no \"Release notes\" section, which the pull request template offers with options");
    }

    @Test
    void aTemplateWithoutOptionsOrAnAbsentDescriptionAddsNothing()
    {
        assertThat(ReviewFacts.describe(ImmutableList.of(), Optional.of("## Release notes\n(x) Yes"), Optional.of("## Description\nText only"), ImmutableList.of())).isEmpty();
        assertThat(ReviewFacts.describe(ImmutableList.of(), Optional.empty(), Optional.of(TEMPLATE), ImmutableList.of())).isEmpty();
        assertThat(ReviewFacts.describe(ImmutableList.of(), Optional.of("- [x] I read the guide"), Optional.of("- [ ] I read the guide"), ImmutableList.of())).isEmpty();
    }

    @Test
    void findsAddedTestsThatAreSwitchedOffAtTheirNewLineNumbers()
    {
        String patch = """
                @@ -10,3 +40,5 @@ class TestLoader
                 import org.junit.jupiter.api.Test;
                -@Disabled("old")
                +@Disabled("Measures wall time, do not enable in CI")
                 class TestLoader
                +    @Test(enabled = false)
                @@ -90,2 +200,3 @@
                 context
                +    it.skip('loads', () => {});
                """;

        assertThat(ReviewFacts.describe(ImmutableList.of(), Optional.empty(), Optional.empty(), ImmutableList.of(
                new DiffFile("src/test/TestLoader.java", "modified", 3, 1, patch),
                new DiffFile("assets/logo.png", "added", 0, 0, null))))
                .containsExactly(
                        "src/test/TestLoader.java:41 adds a test that is switched off: @Disabled('Measures wall time, do not enable in CI')",
                        "src/test/TestLoader.java:43 adds a test that is switched off: @Test(enabled = false)",
                        "src/test/TestLoader.java:201 adds a test that is switched off: it.skip('loads', () => {});");
    }

    @Test
    void pairsTestsWithCodeThatAnEarlierCommitOfTheChangeAdded()
    {
        List<String> facts = ReviewFacts.describe(
                ImmutableList.of(
                        commit("a", "Add the storage", "src/main/java/io/Storage.java", "src/main/java/io/StorageConfig.java", "web/loader.ts", "docs/storage.md"),
                        commit("b", "Add its tests", "src/test/java/io/TestStorage.java", "src/test/java/io/StorageConfigTest.java", "web/loader.test.ts",
                                "src/test/java/io/TestingStorageServer.java", "src/test/java/io/TestOther.java"),
                        commit("c", "Add the reader with its test", "src/main/java/io/Reader.java", "src/test/java/io/TestReader.java")),
                Optional.empty(), Optional.empty(), ImmutableList.of());

        // A helper named Testing…, a test of code the change did not add, and a test that comes with its code pair nothing.
        assertThat(facts).filteredOn(fact -> fact.contains(" adds ")).containsExactly(
                "commit bbbbbbbbbbb adds TestStorage.java, the test of Storage.java, which commit aaaaaaaaaaa added",
                "commit bbbbbbbbbbb adds StorageConfigTest.java, the test of StorageConfig.java, which commit aaaaaaaaaaa added",
                "commit bbbbbbbbbbb adds loader.test.ts, the test of loader.ts, which commit aaaaaaaaaaa added");

        List<ReviewFacts.Commit> many = ImmutableList.of(
                commit("a", "Add six", "A.java", "B.java", "C.java", "D.java", "E.java", "F.java"),
                commit("b", "Test six", "ATest.java", "BTest.java", "CTest.java", "DTest.java", "ETest.java", "FTest.java"));
        assertThat(ReviewFacts.describe(many, Optional.empty(), Optional.empty(), ImmutableList.of())).filteredOn(fact -> !fact.contains("subject"))
                .hasSize(6).last().isEqualTo("more tests added after their code are not listed");
    }

    @Test
    void findsStringsThatHoldSeveralLinesAsEscapes()
    {
        String patch = """
                @@ -0,0 +40,7 @@
                +    private static final String DESCRIPTION = "openapi: 3.0.3\\ninfo:\\n  title: test\\n";
                +    private static final String SEPARATOR = "\\n\\n";
                +    private static final String ONE = "first line\\n";
                +    // String old = "a\\nb\\nc";
                +    private static final String BACKSLASHES = "a\\\\nb\\\\nc";
                +    String text = String.format("%s\\n%s\\n", first, second);
                +    String tabs = "a\\tb\\tc";
                """;

        // A separator, one line, a comment, escaped backslashes and tabs are not several lines of text.
        assertThat(ReviewFacts.describe(ImmutableList.of(), Optional.empty(), Optional.empty(),
                ImmutableList.of(new DiffFile("src/test/java/TestLoader.java", "added", 7, 0, patch))))
                .containsExactly(
                        "src/test/java/TestLoader.java:40 writes a string of several lines with 3 \\n escapes on one line: "
                                + "private static final String DESCRIPTION = 'openapi: 3.0.3\\ninfo:\\n title: test\\n';",
                        "src/test/java/TestLoader.java:45 writes a string of several lines with 2 \\n escapes on one line: "
                                + "String text = String.format('%s\\n%s\\n', first, second);");
        // A language without a multi-line literal has no other way to write it.
        assertThat(ReviewFacts.describe(ImmutableList.of(), Optional.empty(), Optional.empty(), ImmutableList.of(new DiffFile("web/loader.c", "added", 7, 0, patch)))).isEmpty();
    }

    private static ReviewFacts.Commit commit(String letter, String subject, String... added)
    {
        return new ReviewFacts.Commit(letter.repeat(40), subject, "", ImmutableList.copyOf(added));
    }

    @Test
    void aChangeTouchesTestsAndCodeWhenItsPathsNameBoth()
    {
        assertThat(ReviewFacts.touchesTestsAndCode(ImmutableList.of("plugin/trino-iceberg/src/main/java/io/trino/Split.java",
                "plugin/trino-iceberg/src/test/java/io/trino/TestSplit.java"))).isTrue();
        assertThat(ReviewFacts.touchesTestsAndCode(ImmutableList.of("frontend/src/pulls/model.ts", "frontend/src/pulls/model.test.ts"))).isTrue();
        assertThat(ReviewFacts.touchesTestsAndCode(ImmutableList.of("pkg/server.go", "pkg/server_test.go"))).isTrue();
        assertThat(ReviewFacts.touchesTestsAndCode(ImmutableList.of("app/models.py", "tests/test_models.py"))).isTrue();
        assertThat(ReviewFacts.touchesTestsAndCode(ImmutableList.of("pom.xml", "src/test/java/TestSplit.java"))).isTrue();

        // Tests alone, code alone, and tests beside prose only.
        assertThat(ReviewFacts.touchesTestsAndCode(ImmutableList.of("src/test/java/TestSplit.java", "src/test/resources/split.json"))).isFalse();
        assertThat(ReviewFacts.touchesTestsAndCode(ImmutableList.of("src/main/java/Split.java", "pom.xml"))).isFalse();
        assertThat(ReviewFacts.touchesTestsAndCode(ImmutableList.of("src/test/java/TestSplit.java", "docs/src/main/sphinx/split.md", "README.md"))).isFalse();

        assertThat(ImmutableList.of("src/SplitTest.java", "src/SplitTests.kt", "src/SplitIT.java", "src/TestSplit.java", "web/split.spec.tsx", "lib/split_spec.rb", "e2e/split.ts"))
                .allMatch(ReviewFacts::test);
        // A name that only contains the word, or a production type named for a specification, is not a test.
        assertThat(ImmutableList.of("src/main/java/Attestation.java", "src/main/java/Audit.java", "src/main/java/PartitionSpec.java", "src/main/java/Testing.java",
                "src/latest/Split.java", "src/contest.ts"))
                .noneMatch(ReviewFacts::test);
    }

    private static List<String> facts(String description)
    {
        return ReviewFacts.describe(ImmutableList.of(), Optional.of(description), Optional.of(TEMPLATE), ImmutableList.of());
    }
}
