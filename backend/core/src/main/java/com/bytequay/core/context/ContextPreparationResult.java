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
package com.bytequay.core.context;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Last completed extraction for recorded inputs; neither success nor equal inputs proves a claim's applicability. */
public record ContextPreparationResult(
        String attemptId,
        ContextInputFingerprint inputFingerprint,
        Outcome outcome,
        List<String> noteIds,
        List<ContextSourceRead> checkedReads,
        String explanation)
{
    public ContextPreparationResult
    {
        ContextLimits.text(attemptId, "preparation attempt id", 200);
        requireNonNull(inputFingerprint, "inputFingerprint is null");
        requireNonNull(outcome, "outcome is null");
        noteIds = ImmutableList.copyOf(noteIds);
        checkedReads = ImmutableList.copyOf(checkedReads);
        ContextLimits.unique(noteIds, "preparation note ids", 1);
        ContextLimits.unique(checkedReads.stream().map(ContextSourceRead::id).toList(), "checked source reads", ContextLimits.MAX_SOURCES);
        ContextLimits.text(explanation, "preparation explanation", 2000);
        checkArgument(outcome == Outcome.FAILED || inputFingerprint.digest().isPresent(), "Successful preparation needs complete input identities");
        checkArgument(outcome == Outcome.READY
                ? noteIds.equals(ImmutableList.of(ContextEntry.knowledgeId(inputFingerprint.scopeId())))
                : noteIds.isEmpty(), "Only ready preparation names its canonical knowledge note");
        checkArgument(outcome == Outcome.FAILED || !checkedReads.isEmpty(), "Successful preparation needs checked source reads");
        checkArgument(outcome != Outcome.READY || checkedReads.stream().anyMatch(read -> read.evidenceRef().isPresent()), "Ready preparation needs retained source text");
        if (outcome == Outcome.EMPTY) {
            checkArgument(checkedReads.stream().allMatch(read -> switch (read) {
                case ContextRepositoryRead repository -> !repository.truncated();
                case ContextDocumentRead document -> !document.truncated() && !document.refreshFailed();
                case ContextHistoryRead ignored -> false;
            }), "Empty preparation cannot use incomplete or failed source reads");
        }
    }

    public enum Outcome
    {
        READY,
        EMPTY,
        FAILED
    }
}
