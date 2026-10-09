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
import com.bytequay.core.review.ReviewRun;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Whether a new head carries the change an accepted review already read: the same base branch, the same patch over
 * each head's own merge base, and the same commit messages. A rebase or a clean merge of the base branch changes
 * none of these. Whatever cannot be read counts as a different change, so the caller reviews it.
 */
final class ReviewHeadCheck
{
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_BYTES = 32 * 1024 * 1024;

    private ReviewHeadCheck() {}

    static boolean sameChange(GitCommandRunner git, Path repository, ReviewRun reviewed, String baseRef, String mergeBaseSha, String headSha)
    {
        if (!reviewed.baseRef().equals(baseRef)) {
            return false;
        }
        Optional<String> read = change(git, repository, reviewed.mergeBaseSha(), reviewed.headSha());
        return read.isPresent() && read.equals(change(git, repository, mergeBaseSha, headSha));
    }

    /**
     * The messages of the range's own commits, then its patch. Blob ids and hunk positions are left out because a moved
     * base changes them without changing the patch; context lines stay, so a base that changed beside a hunk still counts.
     */
    private static Optional<String> change(GitCommandRunner git, Path repository, String mergeBaseSha, String headSha)
    {
        GitCommandRunner.Result messages = git.runLocalBoundedStdout(repository, TIMEOUT, MAX_BYTES,
                ImmutableList.of("log", "--no-merges", "--reverse", "--format=%B%x1e", mergeBaseSha + ".." + headSha));
        GitCommandRunner.Result patch = git.runLocalBoundedStdout(repository, TIMEOUT, MAX_BYTES,
                ImmutableList.of("diff", "--binary", "--no-color", "--no-ext-diff", "--no-textconv", mergeBaseSha, headSha));
        if (!messages.successful() || messages.truncated() || !patch.successful() || patch.truncated()) {
            return Optional.empty();
        }
        return Optional.of(messages.output() + patch.output().lines()
                .filter(line -> !line.startsWith("index ") && !line.startsWith("@@")).collect(Collectors.joining("\n")));
    }
}
