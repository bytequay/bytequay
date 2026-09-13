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
package com.bytequay.agent.spi;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;

/** The bounded explanation retained with a generated summary-refinement turn. */
public record DevelopmentLearningSummary(
        String purpose,
        List<DevelopmentLearningCommit> commits,
        Optional<String> combinedResult,
        String evidence,
        List<String> limitations)
{
    public static final int MAXIMUM_PURPOSE_LENGTH = 4_000;
    public static final int MAXIMUM_COMMITS = 200;
    public static final int MAXIMUM_RESULT_LENGTH = 4_000;
    public static final int MAXIMUM_EVIDENCE_LENGTH = 8_000;
    public static final int MAXIMUM_LIMITATIONS = 20;
    public static final int MAXIMUM_LIMITATION_LENGTH = 1_000;
    public static final int MAXIMUM_TEXT_LENGTH = 64_000;

    public DevelopmentLearningSummary
    {
        checkNotBlank(purpose, "purpose");
        checkArgument(purpose.length() <= MAXIMUM_PURPOSE_LENGTH, "purpose is too long");
        commits = ImmutableList.copyOf(commits);
        checkArgument(commits.size() <= MAXIMUM_COMMITS, "too many commits");
        checkArgument(commits.stream().map(DevelopmentLearningCommit::sha).distinct().count() == commits.size(), "duplicate commit identifier");
        checkOptionalText(combinedResult, "combinedResult");
        combinedResult.ifPresent(value -> checkArgument(value.length() <= MAXIMUM_RESULT_LENGTH, "combinedResult is too long"));
        checkNotBlank(evidence, "evidence");
        checkArgument(evidence.length() <= MAXIMUM_EVIDENCE_LENGTH, "evidence is too long");
        limitations = ImmutableList.copyOf(limitations);
        checkArgument(limitations.size() <= MAXIMUM_LIMITATIONS, "too many limitations");
        limitations.forEach(value -> {
            checkNotBlank(value, "limitation");
            checkArgument(value.length() <= MAXIMUM_LIMITATION_LENGTH, "limitation is too long");
        });
        long textLength = purpose.length() + combinedResult.map(String::length).orElse(0) + evidence.length()
                + limitations.stream().mapToLong(String::length).sum()
                + commits.stream().mapToLong(commit -> commit.sha().length() + commit.title().length() + commit.summary().length()).sum();
        checkArgument(textLength <= MAXIMUM_TEXT_LENGTH, "learningSummary text is too long");
    }
}
