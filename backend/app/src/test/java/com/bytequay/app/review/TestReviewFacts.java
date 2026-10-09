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
                        new ReviewFacts.Commit("a".repeat(40), "Add the loader", "Why it exists.\n\nTwo more words"),
                        new ReviewFacts.Commit("b".repeat(40), "fixup! Add the loader", ""),
                        new ReviewFacts.Commit("c".repeat(40), "WIP: try the pool.", ""),
                        new ReviewFacts.Commit("d".repeat(40), "Wipe the cache", ""),
                        new ReviewFacts.Commit("e".repeat(40), "Merge branch 'master' into feature", "")),
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
