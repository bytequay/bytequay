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
package com.bytequay.app.context;

import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextInputFingerprint;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSelectedEntry.Applicability;
import com.bytequay.core.context.ContextSourceEvidence;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.core.context.ContextPreparationResult.Outcome.READY;
import static com.bytequay.core.context.ContextSelectedEntry.Applicability.MATCHED;
import static com.bytequay.core.context.ContextSelectedEntry.Applicability.UNVERIFIED;
import static com.bytequay.core.context.ContextSelectedEntry.Applicability.VERSION_MISMATCH;
import static com.bytequay.core.context.ContextSourceEvidence.Capture.BYTEQUAY_READ;

/** Compares observed local identities, never source meaning, remote freshness or resolved dependency versions. */
final class ContextSourceApplicability
{
    private ContextSourceApplicability() {}

    static Result compare(ContextEntry entry, Optional<ContextScope> scope, Map<String, ContextSourceEvidence> sources,
            Optional<ContextInputFingerprint> reviewed, WorkspaceRepositoryContext workspace)
    {
        if (reviewed.isEmpty()) {
            return new Result(UNVERIFIED, "The frozen reviewed-head input inventory is unavailable.");
        }
        ContextInputFingerprint current = reviewed.orElseThrow();
        String repository = RepositoryContextReader.repositoryId(workspace) + ":" + workspace.monitoredRepository().fullName().toLowerCase(Locale.ROOT);
        Optional<String> unknown = current.gaps().stream().findFirst().map(gap -> "The reviewed-head inventory is incomplete: " + gap);
        Optional<ContextInputFingerprint> recorded = scope.flatMap(ContextScope::preparation)
                .filter(result -> result.outcome() == READY && result.noteIds().contains(entry.id())).map(result -> result.inputFingerprint());
        int matchedInputs = 0;
        if (recorded.isPresent()) {
            ContextInputFingerprint original = recorded.orElseThrow();
            if (!repository.equals(original.inputs().get("repository"))) {
                unknown = Optional.of("The recorded preparation does not establish the same monitored repository identity.");
            }
            else {
                for (Map.Entry<String, String> input : original.inputs().entrySet()) {
                    if (!input.getKey().startsWith("git:") && !input.getKey().startsWith("git-manifests:") && !input.getKey().equals("repository-root-layout")) {
                        continue;
                    }
                    Optional<String> observed = Optional.ofNullable(current.inputs().get(input.getKey()));
                    if (observed.isEmpty()) {
                        unknown = Optional.of("A recorded source or dependency input was not observed at the reviewed head: " + input.getKey());
                    }
                    else if (!observed.orElseThrow().equals(input.getValue())) {
                        return new Result(VERSION_MISMATCH, "Recorded source or dependency input differs at the frozen reviewed head: " + input.getKey()
                                + "; recorded=" + input.getValue() + "; reviewed=" + observed.orElseThrow() + ". Effective dependency versions remain UNVERIFIED.");
                    }
                    else {
                        matchedInputs++;
                    }
                }
            }
        }
        int matchedSources = 0;
        for (String id : entry.sourceIds()) {
            Optional<ContextSourceEvidence> retained = Optional.ofNullable(sources.get(id));
            if (retained.isEmpty()) {
                unknown = Optional.of("Retained source evidence is unavailable: " + id);
                continue;
            }
            ContextSourceEvidence source = retained.orElseThrow();
            if (source.capture() != BYTEQUAY_READ) {
                unknown = Optional.of("Source " + id + " is AGENT_REPORTED; its original content identity was not captured by ByteQuay.");
                continue;
            }
            if (!(source.reference() instanceof ContextReference.RepositoryFile file)) {
                unknown = Optional.of("Source " + id + " is " + source.reference().getKind()
                        + "; its captured excerpt does not establish a version mapping to the reviewed code or effective dependencies.");
                continue;
            }
            if (file.repositoryId() != RepositoryContextReader.repositoryId(workspace)
                    || !file.repository().fullName().equalsIgnoreCase(workspace.monitoredRepository().fullName())) {
                unknown = Optional.of("Source " + id + " belongs to another repository; no cross-repository identity match is established.");
                continue;
            }
            Optional<String> capturedBlob = source.contentHash().filter(hash -> hash.matches("[0-9a-f]{40}|[0-9a-f]{64}"));
            Optional<String> observed = Optional.ofNullable(current.inputs().get("git:" + file.path()));
            if (capturedBlob.isEmpty() || observed.isEmpty()) {
                unknown = Optional.of("A captured or reviewed regular-file identity is unavailable for " + file.path());
                continue;
            }
            String identity = observed.orElseThrow();
            if (!identity.matches("100(644|755) blob " + capturedBlob.orElseThrow())) {
                return new Result(VERSION_MISMATCH, "Captured source differs at the frozen reviewed head: " + file.path()
                        + "; captured blob=" + capturedBlob.orElseThrow() + "; reviewed=" + identity + ". This is an identity mismatch, not a defect finding.");
            }
            matchedSources++;
            if (source.truncated()) {
                unknown = Optional.of("The source blob matches, but the captured excerpt was truncated: " + file.path());
            }
        }
        if (unknown.isPresent() || matchedSources == 0) {
            return new Result(UNVERIFIED, unknown.orElse("The note has no application-captured repository-file identity to compare."));
        }
        return new Result(MATCHED, matchedSources + " captured repository-file identities and " + matchedInputs
                + " recorded source/dependency inputs match the frozen reviewed head. Claims and effective dependency applicability remain UNVERIFIED.");
    }

    record Result(Applicability applicability, String reason)
    {
        Result
        {
            reason = ContextText.excerpt("Source identity " + applicability + ": " + reason, 2000);
        }
    }
}
