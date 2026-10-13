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
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReviewDiffAnchors
{
    private static final DiffFile ALPHA = file(
            "src/Alpha.java",
            "@@ -1,3 +1,4 @@",
            " one",
            "+two",
            " three",
            "@@ -10,4 +11,3 @@ class Alpha",
            " ten",
            "-eleven",
            " twelve");

    private static final DiffFile BRAVO = file(
            "docs/bravo.md",
            "@@ -1 +1 @@",
            "-old title",
            "+new title",
            "\\ No newline at end of file");

    private static final DiffFile BINARY = new DiffFile("assets/logo.png", "modified", 0, 0, null);

    private static final DiffFile UNCHANGED = new DiffFile("assets/empty.txt", "renamed", 0, 0, "");

    @Test
    void numbersAnchorsInDiffOrderAcrossFilesAndHunks()
    {
        ReviewDiffAnchors anchors = ReviewDiffAnchors.of(diff());

        assertThat(anchors.size()).isEqualTo(8);
        assertThat(describe(anchors)).containsExactly(
                "A1 src/Alpha.java RIGHT:1 hunk0 [ ]one",
                "A2 src/Alpha.java RIGHT:2 hunk0 [+]two",
                "A3 src/Alpha.java RIGHT:3 hunk0 [ ]three",
                "A4 src/Alpha.java RIGHT:11 hunk1 [ ]ten",
                "A5 src/Alpha.java LEFT:11 hunk1 [-]eleven",
                "A6 src/Alpha.java RIGHT:12 hunk1 [ ]twelve",
                "A7 docs/bravo.md LEFT:1 hunk2 [-]old title",
                "A8 docs/bravo.md RIGHT:1 hunk2 [+]new title");
        // The identifiers are positional, so the same patches must produce the same map every run.
        assertThat(describe(ReviewDiffAnchors.of(diff()))).isEqualTo(describe(anchors));
    }

    @Test
    void anchorsAddedRightDeletedLeftAndContextRight()
    {
        ReviewDiffAnchors anchors = ReviewDiffAnchors.of(diff());

        ReviewDiffAnchors.Line added = anchors.line("A2").orElseThrow();
        assertThat(added.kind()).isEqualTo('+');
        assertThat(added.side()).isEqualTo("RIGHT");
        assertThat(added.line()).isEqualTo(2);

        ReviewDiffAnchors.Line deleted = anchors.line("A5").orElseThrow();
        assertThat(deleted.kind()).isEqualTo('-');
        assertThat(deleted.side()).isEqualTo("LEFT");
        assertThat(deleted.line()).isEqualTo(11);

        ReviewDiffAnchors.Line context = anchors.line("A6").orElseThrow();
        assertThat(context.kind()).isEqualTo(' ');
        assertThat(context.side()).isEqualTo("RIGHT");
        assertThat(context.line()).isEqualTo(12);

        assertThat(anchors.line("A404")).isEmpty();
    }

    @Test
    void parsesHunkHeadersWithAndWithoutCountsAndIgnoresNoNewlineMarkers()
    {
        DiffFile headers = file("src/Headers.java", "@@ -1 +1 @@", "-old", "+new", "\\ No newline at end of file", "@@ -10,7 +12,9 @@ class Thing", " keep", "+added");

        ReviewDiffAnchors anchors = ReviewDiffAnchors.of(ImmutableList.of(headers));

        assertThat(anchors.size()).isEqualTo(4);
        assertThat(anchors.hunks().stream().map(ReviewDiffAnchors.Hunk::header).collect(toImmutableList()))
                .containsExactly("@@ -1 +1 @@", "@@ -10,7 +12,9 @@ class Thing");
        assertThat(describe(anchors)).containsExactly(
                "A1 src/Headers.java LEFT:1 hunk0 [-]old",
                "A2 src/Headers.java RIGHT:1 hunk0 [+]new",
                "A3 src/Headers.java RIGHT:12 hunk1 [ ]keep",
                "A4 src/Headers.java RIGHT:13 hunk1 [+]added");
    }

    @Test
    void rangeFreezesTheExactExcerptOfItsOwnSide()
    {
        ReviewDiffAnchors anchors = ReviewDiffAnchors.of(diff());

        assertThat(anchors.range("A1", Optional.of("A3")))
                .isEqualTo(new ReviewAnchor("src/Alpha.java", "RIGHT", 1, 3, ImmutableList.of("one", "two", "three")));
        assertThat(anchors.range("A5", Optional.empty()))
                .isEqualTo(new ReviewAnchor("src/Alpha.java", "LEFT", 11, 11, ImmutableList.of("eleven")));
        // The deleted line between the two context lines sits on the other side and is left out.
        assertThat(anchors.range("A4", Optional.of("A6")))
                .isEqualTo(new ReviewAnchor("src/Alpha.java", "RIGHT", 11, 12, ImmutableList.of("ten", "twelve")));
    }

    @Test
    void rangeRejectsAnchorsItCannotPublish()
    {
        ReviewDiffAnchors anchors = ReviewDiffAnchors.of(diff());

        assertThatThrownBy(() -> anchors.range("A404", Optional.empty()))
                .isInstanceOf(ReviewInvalidRequestException.class)
                .hasMessage("startAnchor: unknown anchor A404 for this run");
        assertThatThrownBy(() -> anchors.range("A1", Optional.of("A404")))
                .isInstanceOf(ReviewInvalidRequestException.class)
                .hasMessage("endAnchor: unknown anchor A404 for this run");
        assertThatThrownBy(() -> anchors.range("A1", Optional.of("A8")))
                .isInstanceOf(ReviewInvalidRequestException.class)
                .hasMessageStartingWith("endAnchor:")
                .hasMessageContaining("crosses files");
        assertThatThrownBy(() -> anchors.range("A4", Optional.of("A5")))
                .isInstanceOf(ReviewInvalidRequestException.class)
                .hasMessageStartingWith("endAnchor:")
                .hasMessageContaining("crosses diff sides");
        assertThatThrownBy(() -> anchors.range("A3", Optional.of("A4")))
                .isInstanceOf(ReviewInvalidRequestException.class)
                .hasMessageStartingWith("endAnchor:")
                .hasMessageContaining("crosses hunks");
        assertThatThrownBy(() -> anchors.range("A3", Optional.of("A1")))
                .isInstanceOf(ReviewInvalidRequestException.class)
                .hasMessageStartingWith("endAnchor:")
                .hasMessageContaining("reversed");
    }

    @Test
    void patchlessFilesContributeNoAnchors()
    {
        ReviewDiffAnchors anchors = ReviewDiffAnchors.of(ImmutableList.of(BINARY, UNCHANGED));

        assertThat(anchors.size()).isEqualTo(0);
        assertThat(anchors.hunks()).isEmpty();
        assertThat(anchors.hunks("assets/logo.png")).isEmpty();
        assertThat(anchors.line("A1")).isEmpty();
    }

    @Test
    void remapKeepsAnAnchorWhoseExcerptDidNotMove()
    {
        ReviewDiffAnchors anchors = ReviewDiffAnchors.of(diff());
        ReviewAnchor anchor = anchors.range("A1", Optional.of("A2"));

        assertThat(anchors.remap(anchor)).hasValue(anchor);
    }

    @Test
    void remapFollowsAnExcerptToItsSingleNewLocation()
    {
        ReviewAnchor anchor = new ReviewAnchor("src/Alpha.java", "RIGHT", 1, 2, ImmutableList.of("one", "two"));
        ReviewDiffAnchors moved = ReviewDiffAnchors.of(ImmutableList.of(file("src/Alpha.java", "@@ -1,4 +5,4 @@", " zero", " one", " two", " three")));

        assertThat(moved.remap(anchor))
                .hasValue(new ReviewAnchor("src/Alpha.java", "RIGHT", 6, 7, ImmutableList.of("one", "two")));
    }

    @Test
    void remapGivesUpWhenTheExcerptIsAmbiguousOrGone()
    {
        ReviewAnchor ambiguous = new ReviewAnchor("src/Alpha.java", "RIGHT", 20, 21, ImmutableList.of("one", "two"));
        ReviewDiffAnchors duplicated = ReviewDiffAnchors.of(ImmutableList.of(file("src/Alpha.java", "@@ -1,6 +1,6 @@", " one", " two", " filler", " one", " two", " tail")));

        assertThat(duplicated.remap(ambiguous)).isEmpty();

        ReviewDiffAnchors anchors = ReviewDiffAnchors.of(diff());
        assertThat(anchors.remap(new ReviewAnchor("src/Alpha.java", "RIGHT", 1, 1, ImmutableList.of("vanished")))).isEmpty();
        assertThat(anchors.remap(new ReviewAnchor("src/Gone.java", "RIGHT", 1, 1, ImmutableList.of("one")))).isEmpty();
    }

    private static List<DiffFile> diff()
    {
        return ImmutableList.of(ALPHA, BINARY, UNCHANGED, BRAVO);
    }

    private static DiffFile file(String filename, String... patchLines)
    {
        return new DiffFile(filename, "modified", 0, 0, String.join("\n", patchLines));
    }

    private static List<String> describe(ReviewDiffAnchors anchors)
    {
        return anchors.hunks().stream()
                .flatMap(hunk -> hunk.lines().stream())
                .map(line -> "%s %s %s:%d hunk%d [%c]%s".formatted(line.id(), line.path(), line.side(), line.line(), line.hunk(), line.kind(), line.text()))
                .collect(toImmutableList());
    }
}
