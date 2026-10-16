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
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Public pages read by their address, kept briefly so a reader can page through one capture. */
@Service
public class SelectedDocumentationReader
{
    public static final int PAGE_BYTES = 16 * 1024;
    private static final int MAX_CAPTURES = 16;
    private static final Pattern LINE_ANCHOR = Pattern.compile("L(\\d{1,7})(-L\\d{1,7})?");
    private static final int MAX_OUTLINE = 300;
    private static final Duration RETENTION = Duration.ofMinutes(10);

    private final DocumentationHttpReader reader;
    private final Cache<String, Captured> captures;

    @Autowired
    public SelectedDocumentationReader(DocumentationHttpReader reader)
    {
        this(reader, Ticker.systemTicker());
    }

    SelectedDocumentationReader(DocumentationHttpReader reader, Ticker ticker)
    {
        this.reader = requireNonNull(reader, "reader is null");
        captures = CacheBuilder.newBuilder().maximumSize(MAX_CAPTURES).expireAfterWrite(RETENTION).ticker(ticker).build();
    }

    /**
     * Reads one public page by its address, fresh on its first page. The HTTPS, public-address and redirect checks apply, and none of
     * its links is followed. The first read opens at the page holding the address's #section when the text has it.
     *
     * @param url the page address, without its #section
     * @param captureId receipt returned by the first read, required after page one
     * @param page one-based exact UTF-8 text page, without inserted ellipses
     * @param section a link's #section to open the first read at
     * @return captured text and observation metadata; neither content hashes nor cache reuse establish applicability
     * @throws ResponseStatusException for unavailable pages or lost or replaced captures
     */
    public Page readPage(String url, Optional<String> captureId, int page, Optional<String> section)
    {
        RegisteredContextSource source = new RegisteredContextSource("page:" + Hashing.sha256().hashString(url, UTF_8).toString().substring(0, 16),
                RegisteredContextSource.Kind.DOCUMENTATION_PAGE, url, ContextEntryOrigin.GENERATED, Optional.empty());
        requireNonNull(captureId, "captureId is null");
        checkArgument(page > 0, "page must be positive");
        checkArgument(page == 1 || captureId.isPresent(), "Later document pages require captureId from the first read");
        captureId.ifPresent(id -> checkArgument(id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid document captureId"));
        String key = source.url();
        Captured cached = captures.getIfPresent(key);
        boolean cacheHit = captureId.isPresent();
        boolean refreshFailed = false;
        Captured captured;
        if (captureId.isPresent()) {
            if (cached == null || !cached.id().equals(captureId.orElseThrow())) {
                throw restart();
            }
            captured = cached;
        }
        else {
            try {
                captured = capture(source);
                captures.put(key, captured);
            }
            catch (ResponseStatusException failure) {
                if (cached == null || failure.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
                    throw failure;
                }
                captured = cached;
                cacheHit = true;
                refreshFailed = true;
            }
        }
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
        return new Page(read.resolvedUrl(), captured.id(), read.contentHash(), read.readAt(), shown, pages.size(), pages.get(shown - 1), read.truncated(),
                cacheHit, refreshFailed, gaps.build(), captureId.isEmpty() ? outline(read, pages) : ImmutableList.of());
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

    private Captured capture(RegisteredContextSource source)
    {
        return new Captured(UUID.randomUUID().toString(), reader.read(source));
    }

    private static ResponseStatusException restart()
    {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Page capture expired or was replaced; restart at page one without captureId");
    }

    private static List<String> pages(String text)
    {
        ImmutableList.Builder<String> pages = ImmutableList.builder();
        int offset = 0;
        do {
            String page = ContextText.prefix(text.substring(offset), PAGE_BYTES);
            pages.add(page);
            offset += page.length();
        }
        while (offset < text.length());
        return pages.build();
    }

    /** The outline is the capture's headings, returned with a first read only. */
    public record Page(
            String resolvedUrl, String captureId, String contentHash, Instant readAt, int page, int totalPages, String excerpt, boolean truncated,
            boolean cacheHit, boolean refreshFailed, List<String> gaps, List<Heading> outline)
    {
        public Page
        {
            gaps = ImmutableList.copyOf(gaps);
            outline = ImmutableList.copyOf(outline);
        }
    }

    /** A section of a captured document: its anchor, heading and the page it starts on. */
    public record Heading(String anchor, String title, int page) {}

    private record Captured(String id, DocumentationHttpReader.Read read) {}
}
