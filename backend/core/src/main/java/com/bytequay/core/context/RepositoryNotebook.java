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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

/** Workspace-owned content and bounded extraction receipts; execution metadata does not change the content revision. */
public record RepositoryNotebook(
        int schemaVersion,
        long revision,
        boolean useInReviews,
        boolean useInDevelopment,
        List<RegisteredContextSource> sources,
        List<ContextScope> scopes,
        List<ContextEntry> entries,
        List<ContextKnowledgeDraft> pendingDrafts,
        List<ContextSourceEvidence> sourceEvidence,
        Optional<ActiveContextPreparation> activePreparation)
{
    public RepositoryNotebook(int schemaVersion, long revision, boolean useInReviews, List<RegisteredContextSource> sources,
            List<ContextScope> scopes, List<ContextEntry> entries, List<ContextSourceEvidence> sourceEvidence, Optional<ActiveContextPreparation> activePreparation)
    {
        this(schemaVersion, revision, useInReviews, true, sources, scopes, entries, sourceEvidence, activePreparation);
    }

    public RepositoryNotebook(int schemaVersion, long revision, boolean useInReviews, boolean useInDevelopment,
            List<RegisteredContextSource> sources, List<ContextScope> scopes, List<ContextEntry> entries,
            List<ContextSourceEvidence> sourceEvidence, Optional<ActiveContextPreparation> activePreparation)
    {
        this(schemaVersion, revision, useInReviews, useInDevelopment, sources, scopes, entries, ImmutableList.of(), sourceEvidence, activePreparation);
    }

    public RepositoryNotebook
    {
        checkArgument(schemaVersion == 1, "Unsupported notebook schema");
        checkArgument(revision >= 0, "Negative notebook revision");
        sources = ImmutableList.copyOf(sources);
        scopes = ImmutableList.copyOf(scopes);
        entries = ImmutableList.copyOf(entries);
        pendingDrafts = ImmutableList.copyOf(pendingDrafts);
        sourceEvidence = ImmutableList.copyOf(sourceEvidence);
        requireNonNull(activePreparation, "activePreparation is null");
        ContextLimits.unique(sources.stream().map(RegisteredContextSource::id).toList(), "sources", 100);
        ContextLimits.unique(scopes.stream().map(ContextScope::id).toList(), "scopes", 100);
        ContextLimits.unique(entries.stream().map(ContextEntry::id).toList(), "entries", ContextLimits.MAX_ENTRIES);
        ContextLimits.unique(pendingDrafts.stream().map(ContextKnowledgeDraft::id).toList(), "drafts", ContextLimits.MAX_ENTRIES);
        ContextLimits.unique(pendingDrafts.stream().map(draft -> draft.entry().id()).toList(), "draft entry ids", ContextLimits.MAX_ENTRIES);
        checkArgument(Stream.concat(entries.stream().map(ContextEntry::id), pendingDrafts.stream().map(draft -> draft.entry().id())).distinct().count()
                <= ContextLimits.MAX_ENTRIES, "Notebook has too many distinct note identities");
        ContextLimits.unique(sourceEvidence.stream().map(ContextSourceEvidence::id).toList(), "source evidence", 800);
        Set<String> scopeIds = scopes.stream().map(ContextScope::id).collect(toSet());
        Set<String> evidenceIds = sourceEvidence.stream().map(ContextSourceEvidence::id).collect(toSet());
        checkArgument(scopeIds.contains("project"), "Project scope is missing");
        checkArgument(entries.stream().allMatch(entry -> scopeIds.contains(entry.scopeId())), "Entry scope is missing");
        checkArgument(entries.stream().allMatch(entry -> evidenceIds.containsAll(entry.sourceIds())), "Entry evidence is missing");
        checkArgument(pendingDrafts.stream().allMatch(draft -> scopeIds.contains(draft.entry().scopeId())), "Draft scope is missing");
        checkArgument(pendingDrafts.stream().allMatch(draft -> evidenceIds.containsAll(draft.entry().sourceIds())), "Draft evidence is missing");
        activePreparation.ifPresent(active -> {
            checkArgument(active.baseContentRevision() <= revision, "Preparation revision is newer than notebook content");
            checkArgument(active.inputs().stream().allMatch(input -> scopeIds.contains(input.scopeId())), "Active preparation scope is missing");
        });
        List<ContextPreparationResult> results = scopes.stream().flatMap(scope -> scope.preparation().stream()).toList();
        checkArgument(results.stream().mapToLong(result -> result.inputFingerprint().inputBytes()).sum() <= ReviewContextSnapshot.MAXIMUM_INPUT_BYTES,
                "Retained preparation inputs exceed UTF-8 budget");
        Map<String, ContextSourceEvidence> sourcesById = sourceEvidence.stream().collect(toImmutableMap(ContextSourceEvidence::id, source -> source));
        Stream.concat(scopes.stream().flatMap(scope -> scope.discoveryEvidenceRef().stream()),
                sources.stream().flatMap(source -> source.discoveryEvidenceRef().stream())).forEach(ref -> {
                    ContextSourceEvidence evidence = Optional.ofNullable(sourcesById.get(ref))
                            .orElseThrow(() -> new IllegalArgumentException("Discovery evidence is missing"));
                    checkArgument(evidence.capture() == ContextSourceEvidence.Capture.BYTEQUAY_READ
                            && evidence.reference() instanceof ContextReference.RepositoryFile, "Discovery evidence must be a captured repository file");
                });
        results.stream().flatMap(result -> result.checkedReads().stream()).forEach(read -> read.evidenceRef().ifPresent(ref -> {
            checkArgument(ref.equals(read.id()), "Checked source evidence must use its globally unique read identity");
            ContextSourceEvidence source = Optional.ofNullable(sourcesById.get(ref))
                    .orElseThrow(() -> new IllegalArgumentException("Checked source evidence is missing"));
            checkArgument(source.reference().equals(switch (read) {
                case ContextRepositoryRead repository -> repository.reference();
                case ContextDocumentRead document -> document.reference();
                case ContextHistoryRead history -> history.reference();
            }), "Checked source evidence identity differs");
            checkArgument(source.capture() == ContextSourceEvidence.Capture.BYTEQUAY_READ, "Checked sources must be application captures");
            checkArgument(ContextLimits.bytes(source.excerpt()) == read.utf8Bytes(), "Checked source evidence byte count differs");
            if (read instanceof ContextHistoryRead history) {
                history.checkExcerpt(source.excerpt());
                checkArgument(source.contentHash().equals(Optional.of(history.contentHash())) && source.version().equals(history.sourceRevision()),
                        "History evidence content identity differs");
            }
        }));
        long proseBytes = entries.stream().mapToLong(ContextEntry::proseBytes).sum()
                + pendingDrafts.stream().mapToLong(draft -> draft.entry().proseBytes() + ContextLimits.bytes(draft.explanation())).sum()
                + results.stream().mapToLong(result -> ContextLimits.bytes(result.explanation())).sum()
                + sourceEvidence.stream().mapToLong(source -> ContextLimits.bytes(source.excerpt()) + ContextLimits.bytes(source.provenance())).sum();
        checkArgument(proseBytes <= ContextLimits.MAX_PROSE_BYTES, "Notebook prose exceeds UTF-8 limit");
    }

    public static RepositoryNotebook empty()
    {
        return new RepositoryNotebook(1, 0, true, true, ImmutableList.of(), ImmutableList.of(ContextScope.project()),
                ImmutableList.of(), ImmutableList.of(), Optional.empty());
    }
}
