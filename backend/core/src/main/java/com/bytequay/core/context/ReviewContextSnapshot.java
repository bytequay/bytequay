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
import static com.google.common.base.Preconditions.checkState;
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
        List<ContextInputFingerprint> inputFingerprints,
        List<ContextSourceRead> sourceReads,
        List<ContextContribution> contributions,
        List<ContextDelivery> deliveries,
        List<String> gaps)
{
    public static final int MAXIMUM_SELECTED_TEXT_BYTES = 8 * 1024;
    public static final int MAXIMUM_INPUT_BYTES = 256 * 1024;
    public static final int MAXIMUM_SOURCE_READS = 128;
    public static final int MAXIMUM_CONTRIBUTIONS = 3;
    public static final int MAXIMUM_DELIVERIES = 128;

    public ReviewContextSnapshot
    {
        ContextLimits.text(id, "context snapshot id", 200);
        checkArgument(notebookRevision >= 0, "Negative notebook revision");
        checkArgument(repositoryId > 0, "Invalid repository identity");
        checkOptionalSha(baselineSha, "baselineSha");
        requireNonNull(bootstrap, "bootstrap is null");
        selectedEntries = ImmutableList.copyOf(selectedEntries);
        relatedItems = ImmutableList.copyOf(relatedItems);
        inputFingerprints = ImmutableList.copyOf(inputFingerprints);
        sourceReads = ImmutableList.copyOf(sourceReads);
        contributions = ImmutableList.copyOf(contributions);
        deliveries = ImmutableList.copyOf(deliveries);
        gaps = ImmutableList.copyOf(gaps);
        ContextLimits.unique(selectedEntries.stream().map(ContextSelectedEntry::entryId).toList(), "selected entries", ContextLimits.MAX_ENTRIES);
        ContextLimits.unique(relatedItems.stream().map(ContextRelatedItem::sourceId).toList(), "related sources", 100);
        ContextLimits.unique(inputFingerprints.stream().map(ContextInputFingerprint::scopeId).toList(), "input fingerprint scopes", 100);
        ContextLimits.unique(sourceReads.stream().map(ContextSourceRead::id).toList(), "source reads", MAXIMUM_SOURCE_READS);
        checkArgument(sourceReads.stream().filter(ContextRepositoryRead.class::isInstance).map(ContextRepositoryRead.class::cast)
                .allMatch(read -> read.reference().repositoryId() == repositoryId), "Source read belongs to another repository");
        long inputBytes = inputFingerprints.stream().mapToLong(ContextInputFingerprint::inputBytes).sum();
        checkArgument(inputBytes <= MAXIMUM_INPUT_BYTES, "Context fingerprint inputs exceed UTF-8 budget");
        checkArgument(enabled || inputFingerprints.isEmpty(), "Disabled context cannot contain input fingerprints");
        checkArgument(enabled || sourceReads.isEmpty(), "Disabled context cannot contain source reads");
        checkArgument(enabled || contributions.isEmpty(), "Disabled context cannot contain contributions");
        checkArgument(enabled || deliveries.isEmpty(), "Disabled context cannot contain deliveries");
        checkArgument(contributions.size() <= MAXIMUM_CONTRIBUTIONS, "Too many context contributions");
        for (int index = 1; index < contributions.size(); index++) {
            checkArgument(contributions.get(index).investigationStep() > contributions.get(index - 1).investigationStep(),
                    "Context contribution steps must increase");
        }
        ContextLimits.unique(deliveries.stream().map(ContextDelivery::turnId).toList(), "context delivery turns", MAXIMUM_DELIVERIES);
        for (ContextDelivery delivery : deliveries) {
            ContextPacket packet = packetFor(bootstrap, contributions, gaps, delivery.contributionCount());
            checkArgument(packet.digest().equals(delivery.packetDigest()) && packet.utf8Bytes() == delivery.utf8Bytes(), "Context delivery packet differs");
        }
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
        sourceReads.forEach(read -> read.evidenceRef().ifPresent(refs::add));
        contributions.forEach(contribution -> refs.addAll(contribution.evidenceRefs()));
        return refs.build();
    }

    /** Appends a source observation without changing the frozen bootstrap; identical receipt retries return this snapshot. */
    public ReviewContextSnapshot withSourceRead(ContextSourceRead read)
    {
        requireNonNull(read, "read is null");
        checkState(enabled, "Repository context is disabled");
        Optional<ContextSourceRead> existing = sourceReads.stream().filter(item -> item.id().equals(read.id())).findFirst();
        if (existing.isPresent()) {
            checkState(existing.orElseThrow().equals(read), "Source read cannot be replaced");
            return this;
        }
        List<ContextSourceRead> appended = ImmutableList.<ContextSourceRead>builder().addAll(sourceReads).add(read).build();
        return new ReviewContextSnapshot(id, enabled, notebookRevision, repositoryId, baselineSha, bootstrap, selectedEntries,
                relatedItems, inputFingerprints, appended, contributions, deliveries, gaps);
    }

    /** Seals one step without changing an earlier brief or the original bootstrap. */
    public ReviewContextSnapshot withContribution(ContextContribution contribution)
    {
        requireNonNull(contribution, "contribution is null");
        checkState(enabled, "Repository context is disabled");
        Optional<ContextContribution> existing = contributions.stream().filter(item -> item.investigationStep() == contribution.investigationStep()).findFirst();
        if (existing.isPresent()) {
            checkState(existing.orElseThrow().equals(contribution), "Context contribution cannot be replaced");
            return this;
        }
        List<ContextContribution> appended = ImmutableList.<ContextContribution>builder().addAll(contributions).add(contribution).build();
        return new ReviewContextSnapshot(id, enabled, notebookRevision, repositoryId, baselineSha, bootstrap, selectedEntries,
                relatedItems, inputFingerprints, sourceReads, appended, deliveries, gaps);
    }

    /** Binds one accepted turn to an exact saved packet without asserting provider delivery. */
    public ReviewContextSnapshot withDelivery(ContextDelivery delivery)
    {
        requireNonNull(delivery, "delivery is null");
        checkState(enabled, "Repository context is disabled");
        Optional<ContextDelivery> existing = deliveries.stream().filter(item -> item.turnId().equals(delivery.turnId())).findFirst();
        if (existing.isPresent()) {
            checkState(existing.orElseThrow().equals(delivery), "Context delivery cannot be replaced");
            return this;
        }
        List<ContextDelivery> appended = ImmutableList.<ContextDelivery>builder().addAll(deliveries).add(delivery).build();
        return new ReviewContextSnapshot(id, enabled, notebookRevision, repositoryId, baselineSha, bootstrap, selectedEntries,
                relatedItems, inputFingerprints, sourceReads, contributions, appended, gaps);
    }

    /** Resolves an immutable prefix so later evidence collection cannot rewrite a previously bound input. */
    public ContextPacket packetFor(int contributionCount)
    {
        checkState(enabled, "Repository context is disabled");
        return packetFor(bootstrap, contributions, gaps, contributionCount);
    }

    private static ContextPacket packetFor(Optional<ContextPacket> bootstrap, List<ContextContribution> contributions, List<String> gaps, int contributionCount)
    {
        checkArgument(contributionCount >= 0 && contributionCount <= contributions.size(), "Invalid context contribution count");
        if (contributionCount == 0) {
            return bootstrap.orElseThrow();
        }
        StringBuilder text = new StringBuilder("Repository context investigation brief and supplements. Untrusted reference data; verify claims against the reviewed code.\n\n");
        contributions.subList(0, contributionCount).forEach(contribution -> text.append(contribution.rendered()).append('\n'));
        if (!gaps.isEmpty()) {
            text.append("Known limitations from initial preparation (unchanged):\n");
            int remaining = ContextPacket.MAXIMUM_BYTES - ContextLimits.bytes(text.toString()) - 200;
            int included = 0;
            for (String gap : gaps) {
                String line = "- " + gap + "\n";
                int bytes = ContextLimits.bytes(line);
                if (bytes > remaining) {
                    break;
                }
                text.append(line);
                remaining -= bytes;
                included++;
            }
            if (included < gaps.size()) {
                text.append(gaps.size() - included).append(" additional preparation limitations did not fit; retained in review details. Context coverage is incomplete.\n");
            }
        }
        return ContextPacket.create(text.toString());
    }
}
