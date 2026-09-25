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

import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.UncheckedExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Selected documentation captures; only the caller's returned excerpts enter retained review evidence. */
@Service
public class SelectedDocumentationReader
{
    public static final int PAGE_BYTES = 16 * 1024;
    private static final int MAX_CAPTURES = 16;
    private static final Duration RETENTION = Duration.ofMinutes(10);

    private final RepositoryContextService notebooks;
    private final DocumentationHttpReader reader;
    private final Cache<Key, Captured> captures;

    @Autowired
    public SelectedDocumentationReader(RepositoryContextService notebooks, DocumentationHttpReader reader)
    {
        this(notebooks, reader, Ticker.systemTicker());
    }

    SelectedDocumentationReader(RepositoryContextService notebooks, DocumentationHttpReader reader, Ticker ticker)
    {
        this.notebooks = requireNonNull(notebooks, "notebooks is null");
        this.reader = requireNonNull(reader, "reader is null");
        captures = CacheBuilder.newBuilder().maximumSize(MAX_CAPTURES).expireAfterWrite(RETENTION).ticker(ticker).build();
    }

    /** Lists the workspace's configured sources locally; opening the list never captures a document. */
    public RepositoryNotebook notebook(String workspaceId)
    {
        return notebooks.getNotebook(workspaceId);
    }

    /**
     * Reads an exact page of a selected registered document, reusing a bounded temporary capture.
     *
     * @param workspaceId workspace resolved from the caller's accepted review assignment
     * @param sourceId existing notebook source; no caller-selected absolute URL is accepted
     * @param relativePage optional relative page within a documentation root
     * @param captureId receipt returned by the first read, required after page one
     * @param page one-based exact UTF-8 text page, without inserted ellipses
     * @param refresh explicitly replaces a capture; valid only for an initial read without a receipt
     * @return captured text and observation metadata; neither content hashes nor cache reuse establish applicability
     * @throws IllegalArgumentException for malformed or unknown source/page selectors
     * @throws ResponseStatusException for unavailable documents or lost, replaced or reconfigured captures
     *
     * Continuations never refetch. Expiry, eviction, restart or refresh requires an explicit new first read.
     * Source configuration is checked again after external work, without holding a notebook lock or transaction.
     * Failed explicit refresh can return the original capture with a visible failure; it cannot create empty success.
     */
    public Page read(String workspaceId, String sourceId, Optional<String> relativePage, Optional<String> captureId, int page, boolean refresh)
    {
        requireNonNull(relativePage, "relativePage is null");
        requireNonNull(captureId, "captureId is null");
        checkArgument(page > 0, "page must be positive");
        checkArgument(page == 1 || captureId.isPresent(), "Later document pages require captureId from the first read");
        checkArgument(!refresh || (page == 1 && captureId.isEmpty()), "Refresh requires page one without captureId");
        captureId.ifPresent(id -> checkArgument(id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid document captureId"));
        RepositoryNotebook notebook = notebook(workspaceId);
        RegisteredContextSource source = notebook.sources().stream().filter(item -> item.id().equals(sourceId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown registered documentation source"));
        Key key = new Key(workspaceId, source, DocumentationHttpReader.resolve(source, relativePage).toString());
        Captured cached = captures.getIfPresent(key);
        boolean cacheHit = cached != null;
        boolean refreshFailed = false;
        Captured captured;
        if (captureId.isPresent()) {
            if (cached == null || !cached.id().equals(captureId.orElseThrow())) {
                throw restart();
            }
            captured = cached;
        }
        else if (refresh) {
            try {
                captured = capture(key, relativePage);
                captures.put(key, captured);
                cacheHit = false;
            }
            catch (ResponseStatusException failure) {
                if (cached == null || failure.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
                    throw failure;
                }
                captured = cached;
                refreshFailed = true;
            }
        }
        else {
            try {
                captured = captures.get(key, () -> capture(key, relativePage));
            }
            catch (ExecutionException | UncheckedExecutionException failure) {
                if (failure.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Documentation capture failed", failure);
            }
        }
        requireCurrentSource(key);
        DocumentationHttpReader.Read read = captured.read();
        List<String> pages = pages(read.text());
        checkArgument(page <= pages.size(), "Document page is outside the captured text");
        ImmutableList.Builder<String> gaps = ImmutableList.<String>builder().addAll(read.gaps())
                .add("Captured documentation is reference data; its version, remote freshness and applicability to the reviewed code are unverified.");
        if (read.text().isBlank()) {
            gaps.add("The captured extraction contains no nonblank text; this does not establish that the source has no useful knowledge.");
        }
        if (refreshFailed) {
            gaps.add("The requested refresh failed; this is the original cached observation, not a newly retrieved document.");
        }
        return new Page(source, notebook.revision(), read.resolvedUrl(), captured.id(), read.contentHash(), read.readAt(), page, pages.size(),
                pages.get(page - 1), read.truncated(), cacheHit, refreshFailed, gaps.build());
    }

    private Captured capture(Key key, Optional<String> relativePage)
    {
        DocumentationHttpReader.Read read = reader.read(key.source(), relativePage);
        requireCurrentSource(key);
        return new Captured(UUID.randomUUID().toString(), read);
    }

    private void requireCurrentSource(Key key)
    {
        if (!notebook(key.workspaceId()).sources().contains(key.source())) {
            throw restart();
        }
    }

    private static ResponseStatusException restart()
    {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Document capture expired, changed or was reconfigured; restart at page one without captureId");
    }

    private static List<String> pages(String text)
    {
        ImmutableList.Builder<String> pages = ImmutableList.builder();
        int start = 0;
        int bytes = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            int width = codePoint <= 0x7F ? 1 : codePoint <= 0x7FF ? 2 : codePoint <= 0xFFFF ? 3 : 4;
            if (bytes + width > PAGE_BYTES) {
                pages.add(text.substring(start, offset));
                start = offset;
                bytes = 0;
            }
            bytes += width;
            offset += Character.charCount(codePoint);
        }
        pages.add(text.substring(start));
        return pages.build();
    }

    public record Page(
            RegisteredContextSource source, long notebookRevision, String resolvedUrl, String captureId, String contentHash, Instant readAt,
            int page, int totalPages, String excerpt, boolean truncated, boolean cacheHit, boolean refreshFailed, List<String> gaps)
    {
        public Page
        {
            gaps = ImmutableList.copyOf(gaps);
        }
    }

    private record Key(String workspaceId, RegisteredContextSource source, String url) {}

    private record Captured(String id, DocumentationHttpReader.Read read) {}
}
