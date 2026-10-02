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

import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

/**
 * The newest capture a learning run made of each web page, with the notes' cited reads whose text it no longer holds. A page edited
 * elsewhere leaves its notes unflagged, as a code note is checked by its cited lines, not its file.
 */
@Component
public class ContextPageCaptures
{
    static final String PREFIX = "workspace.repositoryContextPages.";
    static final int MAX_PAGES = 200;

    private final AppSettingsStore settings;
    private final ObjectMapper mapper;

    public ContextPageCaptures(AppSettingsStore settings, ObjectMapper mapper)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
    }

    public List<Page> find(String workspaceId)
    {
        return settings.get(PREFIX + workspaceId).map(json -> {
            try {
                checkArgument(ContextLimits.bytes(json) <= ContextLimits.MAX_NOTEBOOK_BYTES, "Saved page captures exceed 1 MiB");
                JsonNode saved = mapper.readTree(json);
                checkArgument(saved instanceof ArrayNode, "Saved page captures must be an array");
                // Captures saved when any edit to a page flagged every note citing it record no cited text; they flag nothing.
                saved.forEach(page -> {
                    if (page instanceof ObjectNode value && !value.has("staleSources")) {
                        value.putArray("staleSources");
                    }
                });
                return ImmutableList.copyOf(mapper.treeToValue(saved, Page[].class));
            }
            catch (JsonProcessingException | IllegalArgumentException | NullPointerException failure) {
                throw new ContextStorageException("Saved page captures cannot be read", failure);
            }
        }).orElse(ImmutableList.of());
    }

    /** Keeps the newest capture of each address, for the 200 most recently captured addresses. */
    public void record(String workspaceId, Page page)
    {
        WorkspaceOperationLocks.withLock(workspaceId, () -> {
            List<Page> current = find(workspaceId);
            if (current.stream().anyMatch(saved -> saved.url().equals(page.url()) && !saved.capturedAt().isBefore(page.capturedAt()))) {
                return null;
            }
            List<Page> kept = Stream.concat(Stream.of(page), current.stream().filter(saved -> !saved.url().equals(page.url())))
                    .sorted(comparing(Page::capturedAt).reversed()).limit(MAX_PAGES).collect(toImmutableList());
            try {
                settings.put(PREFIX + workspaceId, mapper.writeValueAsString(kept));
                return null;
            }
            catch (JsonProcessingException failure) {
                throw new ContextStorageException("Page captures cannot be saved", failure);
            }
        });
    }

    /** The notebook's sources whose cited text a later capture of their page no longer holds, with the time of that capture. */
    public Map<String, Instant> changed(String workspaceId, RepositoryNotebook notebook)
    {
        List<Page> pages = find(workspaceId);
        ImmutableMap.Builder<String, Instant> changed = ImmutableMap.builder();
        notebook.sourceEvidence().forEach(source -> changed(pages, source).ifPresent(at -> changed.put(source.id(), at)));
        return changed.buildOrThrow();
    }

    /** When a learning run captured the source's page without the text the source cites: the time of that capture. */
    static Optional<Instant> changed(List<Page> pages, ContextSourceEvidence source)
    {
        if (!(source.reference() instanceof ContextReference.Document document)) {
            return Optional.empty();
        }
        return pages.stream().filter(page -> page.url().equals(document.url()) && page.staleSources().contains(source.id()))
                .map(Page::capturedAt).findFirst();
    }

    /** A capture of a page, and the notes' cited reads of that page whose text it no longer holds. */
    public record Page(String url, String contentHash, Instant capturedAt, List<String> staleSources)
    {
        public static final int MAX_STALE_SOURCES = 200;

        public Page
        {
            ContextLimits.documentationUrl(url);
            ContextLimits.text(contentHash, "page content hash", 64);
            requireNonNull(capturedAt, "capturedAt is null");
            staleSources = ImmutableList.copyOf(staleSources);
            checkArgument(staleSources.size() <= MAX_STALE_SOURCES, "Too many stale sources for one page");
        }
    }
}
