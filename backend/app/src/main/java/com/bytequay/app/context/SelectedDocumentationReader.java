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

import com.bytequay.core.context.ContextEntryOrigin;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.UncheckedExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Selected documentation captures; only the caller's returned excerpts enter retained review evidence. */
@Service
public class SelectedDocumentationReader
{
    public static final int PAGE_BYTES = 16 * 1024;
    private static final int MAX_CAPTURES = 16;
    private static final Pattern LINE_ANCHOR = Pattern.compile("L(\\d{1,7})(-L\\d{1,7})?");
    private static final int MAX_OUTLINE = 300;
    private static final Duration RETENTION = Duration.ofMinutes(10);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

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
        RepositoryNotebook notebook = notebook(workspaceId);
        RegisteredContextSource source = notebook.sources().stream().filter(item -> item.id().equals(sourceId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown registered documentation source"));
        return read(new Key(workspaceId, source, DocumentationHttpReader.resolve(source, relativePage).toString(), true), notebook.revision(),
                relativePage, captureId, page, refresh, Optional.empty());
    }

    /**
     * Reads one public page a learning run named by its address, fresh on its first page; a registered page is read the same way.
     * A saved note that cites an unregistered page registers it. The same HTTPS, public-address and redirect checks apply, and
     * none of its links is followed. The first read opens at the page holding the address's #section when the text has it.
     */
    public Page readPage(String workspaceId, RegisteredContextSource source, Optional<String> captureId, int page, Optional<String> section)
    {
        checkArgument(source.kind() == RegisteredContextSource.Kind.DOCUMENTATION_PAGE, "Only an exact page can be read by its address");
        RepositoryNotebook notebook = notebook(workspaceId);
        return read(new Key(workspaceId, source, DocumentationHttpReader.resolve(source, Optional.empty()).toString(), notebook.sources().contains(source)),
                notebook.revision(), Optional.empty(), captureId, page, captureId.isEmpty() && page == 1, section);
    }

    /** The page source a learning run's read by address becomes when a saved note cites it; its id follows from the address. */
    public static RegisteredContextSource page(String url)
    {
        return new RegisteredContextSource("page:" + Hashing.sha256().hashString(url, UTF_8).toString().substring(0, 16),
                RegisteredContextSource.Kind.DOCUMENTATION_PAGE, url, ContextEntryOrigin.GENERATED, Optional.empty());
    }

    /**
     * Whether a capture still holds an excerpt of an earlier one, wherever it now falls; whitespace is ignored. A capture this reader
     * no longer keeps holds nothing.
     */
    public boolean holds(String captureId, String excerpt)
    {
        String wanted = WHITESPACE.matcher(excerpt).replaceAll(" ").strip();
        return captures.asMap().values().stream().filter(captured -> captured.id().equals(captureId)).findFirst()
                .map(captured -> WHITESPACE.matcher(captured.read().text()).replaceAll(" ").contains(wanted)).orElse(false);
    }

    /** Whether a learning run read this page by its address rather than through a registered source. */
    public static boolean readByAddress(RegisteredContextSource source)
    {
        return source.equals(page(source.url()));
    }

    private Page read(Key key, long notebookRevision, Optional<String> relativePage, Optional<String> captureId, int page, boolean refresh,
            Optional<String> section)
    {
        requireNonNull(relativePage, "relativePage is null");
        requireNonNull(captureId, "captureId is null");
        checkArgument(page > 0, "page must be positive");
        checkArgument(page == 1 || captureId.isPresent(), "Later document pages require captureId from the first read");
        checkArgument(!refresh || (page == 1 && captureId.isEmpty()), "Refresh requires page one without captureId");
        captureId.ifPresent(id -> checkArgument(id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid document captureId"));
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
        int shown = page;
        if (section.isPresent() && captureId.isEmpty() && page == 1) {
            OptionalInt start = sectionStart(read, section.orElseThrow());
            if (start.isPresent()) {
                shown = pageAt(pages, start.getAsInt());
                gaps.add("Opened at #" + section.orElseThrow() + " on page " + shown + " of " + pages.size() + "; earlier pages hold what comes before it.");
            }
            else {
                gaps.add("No anchor, heading or line of the captured text matches #" + section.orElseThrow()
                        + (read.truncated() ? "; it may lie beyond what was captured" : "") + "; this is the first page.");
            }
        }
        return new Page(key.source(), notebookRevision, read.resolvedUrl(), captured.id(), read.contentHash(), read.readAt(), shown, pages.size(),
                pages.get(shown - 1), read.truncated(), cacheHit, refreshFailed, gaps.build(), read.links(), read.suggestedExpansionUrls(),
                read.linksTruncated(), captureId.isEmpty() ? outline(read, pages) : ImmutableList.of());
    }

    /**
     * The captured text's headings, so a reader can plan which sections to read: anchors whose text starts with a heading that
     * slugs to the anchor, in document order. Contents entries and menus link to anchors without being them, so they drop out.
     */
    private static List<Heading> outline(DocumentationHttpReader.Read read, List<String> pages)
    {
        return read.sections().entrySet().stream().sorted(Map.Entry.comparingByValue())
                .flatMap(section -> heading(read.text(), section.getKey(), section.getValue(), pages).stream())
                .limit(MAX_OUTLINE).collect(ImmutableList.toImmutableList());
    }

    private static Optional<Heading> heading(String text, String anchor, int start, List<String> pages)
    {
        int end = text.indexOf('\n', start);
        String title = text.substring(start, end < 0 ? text.length() : end).strip().replaceFirst("^#{1,6}\\s+", "").replaceAll("[\\s#¶🔗]+$", "").strip();
        String slug = DocumentationHttpReader.slug(title);
        boolean named = !slug.isEmpty() && title.length() <= 150
                && (anchor.equalsIgnoreCase(slug) || anchor.toLowerCase(Locale.ROOT).matches(Pattern.quote(slug) + "-\\d+"));
        return named ? Optional.of(new Heading(anchor, title, pageAt(pages, start))) : Optional.empty();
    }

    /** Where a link's #section starts: an element id or Markdown heading anchor, or a GitHub line anchor such as L120 or L120-L140. */
    private static OptionalInt sectionStart(DocumentationHttpReader.Read read, String section)
    {
        Matcher line = LINE_ANCHOR.matcher(section);
        if (line.matches()) {
            int start = 0;
            for (int number = Integer.parseInt(line.group(1)); number > 1; number--) {
                start = read.text().indexOf('\n', start) + 1;
                if (start == 0) {
                    return OptionalInt.empty();
                }
            }
            return OptionalInt.of(start);
        }
        Integer start = read.sections().getOrDefault(section, read.sections().get(section.toLowerCase(Locale.ROOT)));
        return start == null ? OptionalInt.empty() : OptionalInt.of(start);
    }

    private static int pageAt(List<String> pages, int offset)
    {
        int end = 0;
        for (int index = 0; index < pages.size(); index++) {
            end += pages.get(index).length();
            if (offset < end) {
                return index + 1;
            }
        }
        return pages.size();
    }

    private Captured capture(Key key, Optional<String> relativePage)
    {
        DocumentationHttpReader.Read read = reader.read(key.source(), relativePage);
        requireCurrentSource(key);
        return new Captured(UUID.randomUUID().toString(), read);
    }

    private void requireCurrentSource(Key key)
    {
        if (key.registered() && !notebook(key.workspaceId()).sources().contains(key.source())) {
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

    /** The outline is the capture's headings, returned with a first read only. */
    public record Page(
            RegisteredContextSource source, long notebookRevision, String resolvedUrl, String captureId, String contentHash, Instant readAt,
            int page, int totalPages, String excerpt, boolean truncated, boolean cacheHit, boolean refreshFailed, List<String> gaps,
            List<DocumentationHttpReader.Link> links, List<String> suggestedExpansionUrls, boolean linksTruncated, List<Heading> outline)
    {
        public Page(RegisteredContextSource source, long notebookRevision, String resolvedUrl, String captureId, String contentHash, Instant readAt,
                int page, int totalPages, String excerpt, boolean truncated, boolean cacheHit, boolean refreshFailed, List<String> gaps)
        {
            this(source, notebookRevision, resolvedUrl, captureId, contentHash, readAt, page, totalPages, excerpt, truncated, cacheHit,
                    refreshFailed, gaps, ImmutableList.of(), ImmutableList.of(), false, ImmutableList.of());
        }

        public Page
        {
            gaps = ImmutableList.copyOf(gaps);
            links = ImmutableList.copyOf(links);
            suggestedExpansionUrls = ImmutableList.copyOf(suggestedExpansionUrls);
            outline = ImmutableList.copyOf(outline);
        }
    }

    /** A section of a captured document: its anchor, heading and the page it starts on. */
    public record Heading(String anchor, String title, int page) {}

    /** A registered source must still be configured when its capture returns; a page read by its address has no registration. */
    private record Key(String workspaceId, RegisteredContextSource source, String url, boolean registered) {}

    private record Captured(String id, DocumentationHttpReader.Read read) {}
}
