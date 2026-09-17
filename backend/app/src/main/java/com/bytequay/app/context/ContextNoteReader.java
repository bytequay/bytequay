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

import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextSelectedEntry;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.review.ReviewEvidence;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

/** Reads an originally selected note version without consulting current matching or applicability. */
@Component
public class ContextNoteReader
{
    private final RepositoryContextService notebooks;

    public ContextNoteReader(RepositoryContextService notebooks)
    {
        this.notebooks = requireNonNull(notebooks, "notebooks is null");
    }

    /**
     * Uses the original captured text, or captures the still-identical notebook version and its sources.
     * The caller must bind the selection to its run and retain this result atomically before returning it.
     * A current Off preference does not revoke an enabled historical run's captured selection.
     *
     * @return empty when the deferred version has changed or been removed; never a replacement version
     */
    public Optional<Read> read(String workspaceId, ContextSelectedEntry selected)
    {
        if (selected.text().isPresent()) {
            return Optional.of(new Read(selected.text().orElseThrow(), selected.sourceEvidenceRefs(), ImmutableMap.of()));
        }
        RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
        Optional<ContextEntry> matching = notebook.entries().stream()
                .filter(entry -> entry.id().equals(selected.entryId()) && entry.version() == selected.entryVersion()).findFirst();
        if (matching.isEmpty()) {
            return Optional.empty();
        }
        ContextEntry entry = matching.orElseThrow();
        Map<String, ContextSourceEvidence> sources = notebook.sourceEvidence().stream().collect(toImmutableMap(ContextSourceEvidence::id, source -> source));
        Map<String, String> refs = entry.sourceIds().stream().collect(toImmutableMap(id -> id, ReviewContextService::evidenceRef));
        ImmutableMap.Builder<String, ReviewEvidence> captured = ImmutableMap.builder();
        refs.forEach((sourceId, ref) -> captured.put(ref, ReviewContextService.capture(sources.get(sourceId), ref)));
        return Optional.of(new Read(ReviewContextService.renderEntry(entry, refs, selected.applicability()), refs, captured.buildOrThrow()));
    }

    public record Read(String text, Map<String, String> sourceEvidenceRefs, Map<String, ReviewEvidence> evidence)
    {
        public Read
        {
            requireNonNull(text, "text is null");
            sourceEvidenceRefs = ImmutableMap.copyOf(sourceEvidenceRefs);
            evidence = ImmutableMap.copyOf(evidence);
        }
    }
}
