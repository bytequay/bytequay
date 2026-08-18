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

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.bytequay.app.ci.CiActionsChecks.checkOptionalText;
import static com.bytequay.app.ci.CiActionsChecks.checkSha;
import static com.bytequay.app.ci.CiActionsChecks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record CiGitInspection(
        Outcome outcome,
        Optional<String> localHead,
        Optional<String> fingerprint,
        List<String> changedPaths,
        String diagnostics)
{
    public CiGitInspection
    {
        requireNonNull(outcome, "outcome is null");
        requireNonNull(localHead, "localHead is null");
        localHead.ifPresent(sha -> checkSha(sha, "localHead"));
        checkOptionalText(fingerprint, "fingerprint", 200);
        requireNonNull(changedPaths, "changedPaths is null");
        changedPaths = ImmutableList.copyOf(changedPaths);
        checkArgument(changedPaths.size() <= 1000, "changedPaths exceeds 1000 values");
        changedPaths.forEach(path -> checkText(path, "changedPath", 500));
        requireNonNull(diagnostics, "diagnostics is null");
        checkArgument(diagnostics.length() <= 8 * 1024, "diagnostics exceeds 8192 characters");
        checkArgument((outcome == Outcome.READY) == (localHead.isPresent() && fingerprint.isPresent()),
                "only READY contains a local head and fingerprint");
        checkArgument(outcome == Outcome.READY || changedPaths.isEmpty(),
                "only READY contains changed paths");
    }

    public static CiGitInspection noChange()
    {
        return new CiGitInspection(Outcome.NO_CHANGE, Optional.empty(), Optional.empty(), ImmutableList.of(), "");
    }

    public static CiGitInspection ready(String localHead, String fingerprint, List<String> changedPaths)
    {
        return new CiGitInspection(
                Outcome.READY,
                Optional.of(localHead),
                Optional.of(fingerprint),
                changedPaths,
                "");
    }

    public static CiGitInspection invalid(String diagnostics)
    {
        return new CiGitInspection(Outcome.INVALID, Optional.empty(), Optional.empty(), ImmutableList.of(), diagnostics);
    }

    public enum Outcome
    {
        NO_CHANGE,
        READY,
        INVALID
    }
}
