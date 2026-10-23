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
package com.bytequay.app.ci;

import com.bytequay.core.ci.CiAutofixAttempt.GitResult;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record CiGitInspection(Outcome outcome, Optional<GitResult> result, String diagnostics)
{
    public CiGitInspection
    {
        requireNonNull(outcome, "outcome is null");
        requireNonNull(result, "result is null");
        requireNonNull(diagnostics, "diagnostics is null");
        checkArgument(diagnostics.length() <= 8 * 1024, "diagnostics exceeds 8192 characters");
        checkArgument((outcome == Outcome.READY) == result.isPresent(), "only READY contains a Git result");
    }

    public static CiGitInspection noChange()
    {
        return new CiGitInspection(Outcome.NO_CHANGE, Optional.empty(), "");
    }

    public static CiGitInspection ready(String localHead, String fingerprint, List<String> changedPaths, List<String> commitSubjects)
    {
        return new CiGitInspection(Outcome.READY, Optional.of(new GitResult(localHead, fingerprint, changedPaths, commitSubjects)), "");
    }

    public static CiGitInspection invalid(String diagnostics)
    {
        return new CiGitInspection(Outcome.INVALID, Optional.empty(), diagnostics);
    }

    public enum Outcome
    {
        NO_CHANGE,
        READY,
        INVALID
    }
}
