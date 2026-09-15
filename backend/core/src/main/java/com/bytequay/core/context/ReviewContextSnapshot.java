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
import com.google.common.collect.ImmutableSet;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.core.domain.Checks.checkOptionalSha;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Immutable prepared context for a review run; its owning run supplies frozen base/head and retained evidence. */
public record ReviewContextSnapshot(
        String id,
        boolean enabled,
        long notebookRevision,
        long repositoryId,
        Optional<String> baselineSha,
        Optional<ContextPacket> bootstrap,
        List<ContextSelectedEntry> selectedEntries,
        List<ContextRelatedItem> relatedItems,
        List<String> gaps)
{
    public static final int MAXIMUM_SELECTED_TEXT_BYTES = 8 * 1024;

    public ReviewContextSnapshot
    {
        ContextLimits.text(id, "context snapshot id", 200);
        checkArgument(notebookRevision >= 0, "Negative notebook revision");
        checkArgument(repositoryId > 0, "Invalid repository identity");
        checkOptionalSha(baselineSha, "baselineSha");
        requireNonNull(bootstrap, "bootstrap is null");
        selectedEntries = ImmutableList.copyOf(selectedEntries);
        relatedItems = ImmutableList.copyOf(relatedItems);
        gaps = ImmutableList.copyOf(gaps);
        ContextLimits.unique(selectedEntries.stream().map(ContextSelectedEntry::entryId).toList(), "selected entries", ContextLimits.MAX_ENTRIES);
        ContextLimits.unique(relatedItems.stream().map(ContextRelatedItem::sourceId).toList(), "related sources", 100);
        checkArgument(gaps.size() <= 100, "Too many context gaps");
        gaps.forEach(gap -> ContextLimits.text(gap, "context gap", 2000));
        checkArgument(enabled == bootstrap.isPresent(), "Only enabled context has a bootstrap packet");
        checkArgument(enabled || (selectedEntries.isEmpty() && relatedItems.isEmpty() && gaps.isEmpty()), "Disabled context cannot contain selections or gaps");
        checkArgument(!enabled || baselineSha.isPresent() || !gaps.isEmpty(), "Missing baseline must be an explicit gap");
        long textBytes = selectedEntries.stream().flatMap(entry -> entry.text().stream()).mapToLong(ContextLimits::bytes).sum();
        checkArgument(textBytes <= MAXIMUM_SELECTED_TEXT_BYTES, "Selected context text exceeds UTF-8 budget");
    }

    /** References that must already exist in the owning run's evidence map. */
    public Set<String> evidenceRefs()
    {
        ImmutableSet.Builder<String> refs = ImmutableSet.builder();
        selectedEntries.forEach(entry -> refs.addAll(entry.sourceEvidenceRefs().values()));
        relatedItems.forEach(item -> refs.add(item.sourceId()));
        return refs.build();
    }
}
