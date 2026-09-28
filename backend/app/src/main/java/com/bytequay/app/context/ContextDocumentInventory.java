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

import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.RegisteredContextSource;
import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** URLs observed under selected source snapshots; this inventory never fetches or authorizes a new source. */
public record ContextDocumentInventory(List<RegisteredContextSource> sources, List<Item> items, boolean truncated,
        List<String> suggestedExpansionUrls)
{
    public static final int MAX_ITEMS = 200;
    public static final int MAX_SUGGESTIONS = 40;
    public static final int MAX_LINK_HOPS = 2;

    public ContextDocumentInventory(List<RegisteredContextSource> sources, List<Item> items, boolean truncated)
    {
        this(sources, items, truncated, ImmutableList.of());
    }

    public ContextDocumentInventory
    {
        sources = ImmutableList.copyOf(sources);
        items = ImmutableList.copyOf(items);
        suggestedExpansionUrls = ImmutableList.copyOf(suggestedExpansionUrls);
        checkArgument(items.size() <= MAX_ITEMS, "Documentation inventory exceeds its item limit");
        ContextLimits.unique(suggestedExpansionUrls, "suggested expansion URLs", MAX_SUGGESTIONS);
        suggestedExpansionUrls.forEach(ContextLimits::documentationUrl);
        Set<String> sourceIds = new HashSet<>();
        for (RegisteredContextSource source : sources) {
            checkArgument(sourceIds.add(source.id()), "Duplicate documentation source ID");
        }
        Set<String> keys = new HashSet<>();
        for (Item item : items) {
            RegisteredContextSource source = sources.stream().filter(candidate -> candidate.id().equals(item.sourceId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Inventory item has no selected source"));
            checkArgument(DocumentationHttpReader.resolve(source, item.relativePage()).toString().equals(item.resolvedUrl()),
                    "Inventory item leaves its selected source");
            checkArgument(keys.add(key(item.sourceId(), item.resolvedUrl())), "Duplicate documentation inventory item");
            if (item.parentUrl().isEmpty()) {
                checkArgument(item.depth() == 0 && item.relativePage().isEmpty(), "Only selected source pages can start a link chain");
            }
            else {
                Item parent = items.stream().filter(candidate -> candidate.sourceId().equals(item.sourceId())
                        && candidate.resolvedUrl().equals(item.parentUrl().orElseThrow())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Inventory link has no parent"));
                checkArgument(parent.depth() + 1 == item.depth(), "Inventory link depth does not follow its parent");
            }
        }
    }

    public static ContextDocumentInventory initial(List<RegisteredContextSource> selectedSources)
    {
        ImmutableList.Builder<Item> roots = ImmutableList.builder();
        for (RegisteredContextSource source : selectedSources) {
            roots.add(new Item(source.id(), DocumentationHttpReader.resolve(source, Optional.empty()).toString(), Optional.empty(),
                    Optional.empty(), 0, Status.PENDING, Optional.empty(), Optional.empty(), ""));
        }
        return new ContextDocumentInventory(selectedSources, roots.build(), false);
    }

    /** Rejects caller-chosen URLs that were not selected roots or links returned by a prior recorded read. */
    public Item choose(String sourceId, Optional<String> relativePage)
    {
        RegisteredContextSource source = source(sourceId);
        String url = DocumentationHttpReader.resolve(source, relativePage).toString();
        Item item = find(sourceId, url).orElseThrow(() -> new IllegalArgumentException("Documentation page was not in the approved inventory"));
        checkArgument(item.status() != Status.EXCLUDED, "Documentation page was excluded from learning");
        return item;
    }

    /** Adds only same-root links observed in a recorded parent page; existing depth and parent never change. */
    public ContextDocumentInventory offer(String sourceId, String parentUrl, List<DocumentationHttpReader.Link> links, boolean linksTruncated)
    {
        RegisteredContextSource source = source(sourceId);
        Item parent = find(sourceId, parentUrl).orElseThrow(() -> new IllegalArgumentException("Unknown documentation link parent"));
        checkArgument(parent.status() == Status.READ, "Documentation links require a recorded parent read");
        List<Item> updated = new ArrayList<>(items);
        Set<String> known = new HashSet<>();
        for (Item item : updated) {
            known.add(key(item.sourceId(), item.resolvedUrl()));
        }
        boolean incomplete = truncated || linksTruncated;
        for (DocumentationHttpReader.Link link : links) {
            checkArgument(DocumentationHttpReader.resolve(source, link.relativePage()).toString().equals(link.resolvedUrl()),
                    "Documentation link leaves its selected source");
            if (!known.add(key(sourceId, link.resolvedUrl()))) {
                continue;
            }
            if (parent.depth() == MAX_LINK_HOPS) {
                incomplete = true;
                continue;
            }
            if (updated.size() == MAX_ITEMS) {
                incomplete = true;
                break;
            }
            updated.add(new Item(sourceId, link.resolvedUrl(), link.relativePage(), Optional.of(parentUrl), parent.depth() + 1,
                    Status.PENDING, Optional.empty(), Optional.empty(), ""));
        }
        return new ContextDocumentInventory(sources, updated, incomplete, suggestedExpansionUrls);
    }

    /** Retains only server-observed external URLs for later, explicit source approval. */
    public ContextDocumentInventory suggest(List<String> urls)
    {
        List<String> updated = new ArrayList<>(suggestedExpansionUrls);
        boolean incomplete = truncated;
        for (String url : urls) {
            ContextLimits.documentationUrl(url);
            if (updated.contains(url) || sources.stream().anyMatch(source -> source.url().equals(url))) {
                continue;
            }
            if (updated.size() == MAX_SUGGESTIONS) {
                incomplete = true;
                break;
            }
            updated.add(url);
        }
        return new ContextDocumentInventory(sources, items, incomplete, updated);
    }

    public ContextDocumentInventory withoutSuggestion(String url)
    {
        checkArgument(suggestedExpansionUrls.contains(url), "URL was not suggested by a recorded document read");
        return new ContextDocumentInventory(sources, items, truncated,
                suggestedExpansionUrls.stream().filter(value -> !value.equals(url)).toList());
    }

    public ContextDocumentInventory read(Item selected, String readId, String contentHash)
    {
        ContextLimits.text(readId, "documentation read ID", 200);
        ContextLimits.text(contentHash, "documentation content hash", 200);
        return replace(selected, new Item(selected.sourceId(), selected.resolvedUrl(), selected.relativePage(), selected.parentUrl(),
                selected.depth(), Status.READ, Optional.of(readId), Optional.of(contentHash), ""));
    }

    public ContextDocumentInventory failed(Item selected, String detail)
    {
        ContextLimits.text(detail, "documentation read failure", 500);
        return replace(selected, new Item(selected.sourceId(), selected.resolvedUrl(), selected.relativePage(), selected.parentUrl(),
                selected.depth(), Status.FAILED, selected.readId(), selected.contentHash(), detail));
    }

    public ContextDocumentInventory exclude(Item selected, String detail)
    {
        ContextLimits.text(detail, "documentation exclusion", 500);
        return replace(selected, new Item(selected.sourceId(), selected.resolvedUrl(), selected.relativePage(), selected.parentUrl(),
                selected.depth(), Status.EXCLUDED, selected.readId(), selected.contentHash(), detail));
    }

    private ContextDocumentInventory replace(Item selected, Item replacement)
    {
        List<Item> updated = new ArrayList<>(items);
        int index = updated.indexOf(selected);
        checkArgument(index >= 0 && find(selected.sourceId(), selected.resolvedUrl()).orElseThrow().equals(selected),
                "Documentation inventory item changed; reload before updating it");
        updated.set(index, replacement);
        return new ContextDocumentInventory(sources, updated, truncated, suggestedExpansionUrls);
    }

    private RegisteredContextSource source(String sourceId)
    {
        return sources.stream().filter(source -> source.id().equals(sourceId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Documentation source was not selected"));
    }

    private Optional<Item> find(String sourceId, String url)
    {
        return items.stream().filter(item -> item.sourceId().equals(sourceId) && item.resolvedUrl().equals(url)).findFirst();
    }

    private static String key(String sourceId, String url)
    {
        return sourceId + '\n' + url;
    }

    public enum Status
    {
        PENDING,
        READ,
        FAILED,
        EXCLUDED
    }

    public record Item(String sourceId, String resolvedUrl, Optional<String> relativePage, Optional<String> parentUrl, int depth,
            Status status, Optional<String> readId, Optional<String> contentHash, String detail)
    {
        public Item
        {
            ContextLimits.text(sourceId, "source ID", 200);
            ContextLimits.documentationUrl(resolvedUrl);
            requireNonNull(relativePage, "relativePage is null");
            requireNonNull(parentUrl, "parentUrl is null");
            parentUrl.ifPresent(ContextLimits::documentationUrl);
            checkArgument(depth >= 0 && depth <= MAX_LINK_HOPS, "Documentation link depth is outside the approved limit");
            requireNonNull(status, "status is null");
            requireNonNull(readId, "readId is null");
            requireNonNull(contentHash, "contentHash is null");
            requireNonNull(detail, "detail is null");
            checkArgument(ContextLimits.bytes(detail) <= 500, "Documentation detail is too long");
            checkArgument(status != Status.READ || (readId.isPresent() && contentHash.isPresent()),
                    "Recorded documentation reads need a receipt and content hash");
        }
    }
}
