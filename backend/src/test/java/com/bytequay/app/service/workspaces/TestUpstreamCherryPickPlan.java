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

import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.workspaces.UpstreamCherryPickService.PlannedCommit;
import com.bytequay.app.service.workspaces.UpstreamCherryPickService.SkipFilters;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestUpstreamCherryPickPlan
{
    @Test
    void filtersMatchSubjectsCaseInsensitivelyAndReportWhy()
    {
        SkipFilters filters = SkipFilters.normalize(
                List.of("  Bump ", "revert:"), List.of("DEPENDABOT"));

        assertThat(filters.skipReason("Bump jackson to 2.19.1"))
                .isEqualTo("subject starts with \"bump\"");
        assertThat(filters.skipReason("Revert: bad change"))
                .isEqualTo("subject starts with \"revert:\"");
        assertThat(filters.skipReason("Update deps via dependabot"))
                .isEqualTo("subject contains \"dependabot\"");
        assertThat(filters.skipReason("Fix checkstyle issues")).isNull();
        // "starts with" must not match mid-subject.
        assertThat(filters.skipReason("Do not bump anything")).isNull();
        // Leading whitespace in a subject should not defeat a starts-with term.
        assertThat(filters.skipReason("   Bump guava")).isEqualTo("subject starts with \"bump\"");
    }

    @Test
    void emptyAndBlankTermsAreDroppedRatherThanMatchingEverything()
    {
        // A blank term would make String.startsWith("") true for every subject
        // and silently skip the whole range.
        SkipFilters filters = SkipFilters.normalize(
                Arrays.asList("", "   ", null), Arrays.asList("", null));

        assertThat(filters.isEmpty()).isTrue();
        assertThat(filters.skipReason("anything at all")).isNull();
    }

    @Test
    void planMarksAlreadyPickedAndFilteredCommitsWithDistinctReasons()
    {
        List<GitRunner.DecoratedCommitEntry> ordered = List.of(
                commit("aaa", "Fix checkstyle issues"),
                commit("bbb", "Bump guava to 33"),
                commit("ccc", "Add null checks"),
                commit("ddd", "Update docs"));

        List<PlannedCommit> planned = UpstreamCherryPickService.plan(
                ordered,
                Set.of("ccc"),
                SkipFilters.normalize(List.of("bump"), List.of()));

        assertThat(planned).extracting(PlannedCommit::sha, PlannedCommit::pick, PlannedCommit::skipReason)
                .containsExactly(
                        Tuple.tuple("aaa", true, null),
                        Tuple.tuple("bbb", false, "subject starts with \"bump\""),
                        Tuple.tuple("ccc", false, "already in the fork"),
                        Tuple.tuple("ddd", true, null));
    }

    @Test
    void alreadyPickedWinsOverAFilterSoTheReasonIsTheActionableOne()
    {
        List<PlannedCommit> planned = UpstreamCherryPickService.plan(
                List.of(commit("aaa", "Bump guava to 33")),
                Set.of("aaa"),
                SkipFilters.normalize(List.of("bump"), List.of()));

        assertThat(planned).singleElement()
                .satisfies(value -> assertThat(value.skipReason()).isEqualTo("already in the fork"));
    }

    @Test
    void planPreservesTheGivenOrderSoTheDryRunReadsLikeTheApplyOrder()
    {
        List<PlannedCommit> planned = UpstreamCherryPickService.plan(
                List.of(commit("aaa", "oldest"), commit("bbb", "middle"), commit("ccc", "newest")),
                Set.of(),
                SkipFilters.none());

        assertThat(planned).extracting(PlannedCommit::subject)
                .containsExactly("oldest", "middle", "newest");
    }

    @Test
    void tooManyOrOverlongTermsAreRejected()
    {
        List<String> tooMany = IntStream.range(0, 21)
                .mapToObj(index -> "term" + index)
                .toList();
        assertThatThrownBy(() -> SkipFilters.normalize(tooMany, List.of()))
                .hasMessageContaining("at most 20 filter terms");

        assertThatThrownBy(() -> SkipFilters.normalize(List.of("x".repeat(201)), List.of()))
                .hasMessageContaining("at most 200 characters");
    }

    private static GitRunner.DecoratedCommitEntry commit(String sha, String subject)
    {
        return new GitRunner.DecoratedCommitEntry(
                sha, sha.substring(0, 3), "Author", "author@example.com",
                null, subject, List.of(), List.of());
    }
}
