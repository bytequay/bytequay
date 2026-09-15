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

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewRun;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

/** Shares frozen review scope while preserving publication filenames and both sides of rename matching. */
public final class ReviewScopeFiles
{
    private ReviewScopeFiles() {}

    /**
     * Returns required coverage paths using the existing full/incremental review contract.
     *
     * @param git shared bounded local Git runner
     * @param repository application-verified readable repository
     * @param run frozen review run
     * @param previous previously accepted, complete run resolved against this run's prior head
     * @return publication diff filenames for full/fallback coverage, or changed paths since the accepted prior head
     * @throws ReviewInvalidRequestException when incremental path discovery fails or is truncated
     */
    public static List<String> required(GitCommandRunner git, Path repository, ReviewRun run, Optional<ReviewRun> previous)
    {
        if (run.scope() == ReviewRun.Scope.FULL || previous.isEmpty()) {
            return run.diff().stream().map(DiffFile::filename).collect(toImmutableList());
        }
        return changed(git, repository, previous.orElseThrow().headSha(), run.headSha());
    }

    /**
     * Returns the same frozen change scope with both old and new rename paths for context matching.
     *
     * @param git shared bounded local Git runner
     * @param repository application-verified readable repository
     * @param run frozen review run
     * @param previous previously accepted, complete run resolved against this run's prior head
     * @return literal changed paths; no source or history hydration is performed
     * @throws ReviewInvalidRequestException when path discovery fails or is truncated
     *
     * Full/fallback matching compares the frozen merge base with the head. Incremental matching
     * compares the accepted prior head, including paths reverted out of the current publication diff.
     */
    public static List<String> matching(GitCommandRunner git, Path repository, ReviewRun run, Optional<ReviewRun> previous)
    {
        String base = run.scope() == ReviewRun.Scope.INCREMENTAL && previous.isPresent() ? previous.orElseThrow().headSha() : run.mergeBaseSha();
        return changed(git, repository, base, run.headSha());
    }

    private static List<String> changed(GitCommandRunner git, Path repository, String base, String head)
    {
        // Reverted paths and binary changes cannot be reconstructed from the publication patch.
        GitCommandRunner.Result changed = git.run(repository, "diff", "--name-only", "--no-renames", "-z", base, head, "--");
        if (!changed.successful() || changed.truncated()) {
            throw new ReviewInvalidRequestException("coverage: the frozen changed file list is unavailable; report incomplete coverage");
        }
        return Arrays.stream(changed.output().split("\\x00")).filter(path -> !path.isEmpty()).collect(toImmutableList());
    }
}
