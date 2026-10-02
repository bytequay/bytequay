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

import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static com.bytequay.core.context.ContextEntryOrigin.USER;
import static com.bytequay.core.context.ContextLimits.bytes;
import static com.bytequay.core.context.RegisteredContextSource.Kind.DOCUMENTATION_ROOT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestSelectedDocumentationReader
{
    private static final String WORKSPACE = "workspace-1";
    private static final RegisteredContextSource SOURCE = new RegisteredContextSource("docs", DOCUMENTATION_ROOT, "https://docs.example.com/current/", USER, Optional.empty());
    private static final Optional<String> RELATIVE_PAGE = Optional.of("concepts.html");
    private static final Instant READ_AT = Instant.parse("2026-01-01T00:00:00Z");

    private final RepositoryContextService notebooks = mock(RepositoryContextService.class);
    private final DocumentationHttpReader http = mock(DocumentationHttpReader.class);
    private final AtomicLong nanos = new AtomicLong();
    private final SelectedDocumentationReader reader = new SelectedDocumentationReader(notebooks, http, new Ticker()
    {
        @Override
        public long read()
        {
            return nanos.get();
        }
    });

    @BeforeEach
    void setUp()
    {
        when(notebooks.getNotebook(WORKSPACE)).thenReturn(notebook(SOURCE));
        when(http.read(SOURCE, RELATIVE_PAGE)).thenReturn(capture("Captured concepts"));
    }

    @Test
    void listingDoesNotFetchAndRepeatedReadsReuseCapturedObservation()
    {
        assertThat(reader.notebook(WORKSPACE).sources()).containsExactly(SOURCE);
        verifyNoInteractions(http);
        SelectedDocumentationReader.Page first = first(false);
        SelectedDocumentationReader.Page reused = first(false);
        assertThat(first.cacheHit()).isFalse();
        assertThat(reused.cacheHit()).isTrue();
        assertThat(reused.captureId()).isEqualTo(first.captureId());
        assertThat(reused.readAt()).isEqualTo(READ_AT);
        assertThat(reused.contentHash()).isEqualTo(first.contentHash());
        assertThat(reused.gaps()).anyMatch(gap -> gap.contains("unverified"));
        verify(http).read(SOURCE, RELATIVE_PAGE);
    }

    @Test
    void linksRemainAttachedToTheSameCaptureAcrossTextPages()
    {
        String text = "a".repeat(SelectedDocumentationReader.PAGE_BYTES + 1);
        DocumentationHttpReader.Link link = new DocumentationHttpReader.Link(SOURCE.url() + "next.html", Optional.of("next.html"));
        when(http.read(SOURCE, RELATIVE_PAGE)).thenReturn(new DocumentationHttpReader.Read(
                SOURCE.url() + RELATIVE_PAGE.orElseThrow(), text, Hashing.sha256().hashString(text, UTF_8).toString(), READ_AT, false,
                List.of(), List.of(link), List.of("https://other.example.com/"), true, ImmutableMap.of()));

        SelectedDocumentationReader.Page first = first(false);
        SelectedDocumentationReader.Page second = read(first.captureId(), 2);

        assertThat(first.links()).containsExactly(link);
        assertThat(second.links()).containsExactly(link);
        assertThat(second.suggestedExpansionUrls()).containsExactly("https://other.example.com/");
        assertThat(second.linksTruncated()).isTrue();
        verify(http).read(SOURCE, RELATIVE_PAGE);
    }

    @Test
    void unicodePagesReconstructExactTextAndContinuationDoesNotFetch()
    {
        String text = "a".repeat(SelectedDocumentationReader.PAGE_BYTES - 1) + "😀世界".repeat(2000);
        when(http.read(SOURCE, RELATIVE_PAGE)).thenReturn(capture(text));
        SelectedDocumentationReader.Page first = first(false);
        StringBuilder reconstructed = new StringBuilder(first.excerpt());
        for (int page = 2; page <= first.totalPages(); page++) {
            SelectedDocumentationReader.Page next = read(first.captureId(), page);
            assertThat(bytes(next.excerpt())).isLessThanOrEqualTo(SelectedDocumentationReader.PAGE_BYTES);
            assertThat(next.excerpt()).doesNotStartWith("\uDE00");
            assertThat(next.captureId()).isEqualTo(first.captureId());
            reconstructed.append(next.excerpt());
        }
        assertThat(first.excerpt()).isEqualTo("a".repeat(SelectedDocumentationReader.PAGE_BYTES - 1));
        assertThat(reconstructed.toString()).isEqualTo(text);
        verify(http).read(SOURCE, RELATIVE_PAGE);
    }

    @Test
    void successfulEmptyTextDiffersFromFailedCapture()
    {
        when(http.read(SOURCE, RELATIVE_PAGE)).thenReturn(capture(""));
        SelectedDocumentationReader.Page empty = first(false);
        assertThat(empty.totalPages()).isEqualTo(1);
        assertThat(empty.excerpt()).isEmpty();
        assertThat(empty.refreshFailed()).isFalse();
        when(http.read(SOURCE, RELATIVE_PAGE)).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unavailable"));
        assertThat(first(true).refreshFailed()).isTrue();
    }

    @Test
    void refreshReplacesReceiptAndFailedRefreshPreservesOriginalObservation()
    {
        SelectedDocumentationReader.Page original = first(false);
        when(http.read(SOURCE, RELATIVE_PAGE)).thenThrow(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Timed out"));
        SelectedDocumentationReader.Page fallback = first(true);
        assertThat(fallback.captureId()).isEqualTo(original.captureId());
        assertThat(fallback.excerpt()).isEqualTo(original.excerpt());
        assertThat(fallback.readAt()).isEqualTo(READ_AT);
        assertThat(fallback.refreshFailed()).isTrue();
        assertThat(fallback.cacheHit()).isTrue();
        assertThat(fallback.gaps()).anyMatch(gap -> gap.contains("refresh failed"));

        doReturn(capture("Changed concepts")).when(http).read(SOURCE, RELATIVE_PAGE);
        SelectedDocumentationReader.Page refreshed = first(true);
        assertThat(refreshed.captureId()).isNotEqualTo(original.captureId());
        assertThat(refreshed.excerpt()).isEqualTo("Changed concepts");
        assertThat(refreshed.cacheHit()).isFalse();
        assertThatThrownBy(() -> read(original.captureId(), 1)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(http, times(3)).read(SOURCE, RELATIVE_PAGE);
    }

    @Test
    void failedMissIsNotCachedAsEmpty()
    {
        when(http.read(SOURCE, RELATIVE_PAGE)).thenThrow(new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported format"));
        assertThatThrownBy(() -> first(false)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("415");
        doReturn(capture("Recovered document")).when(http).read(SOURCE, RELATIVE_PAGE);
        assertThat(first(false).excerpt()).isEqualTo("Recovered document");
        verify(http, times(2)).read(SOURCE, RELATIVE_PAGE);
    }

    @Test
    void expiryAndRestartRequireNewReadInsteadOfSilentRefetch()
    {
        SelectedDocumentationReader.Page captured = first(false);
        nanos.set(Duration.ofMinutes(10).toNanos());
        assertThatThrownBy(() -> read(captured.captureId(), 1)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        SelectedDocumentationReader restarted = new SelectedDocumentationReader(notebooks, http);
        assertThatThrownBy(() -> restarted.read(WORKSPACE, SOURCE.id(), RELATIVE_PAGE, Optional.of(captured.captureId()), 1, false))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(http).read(SOURCE, RELATIVE_PAGE);
        assertThat(first(false).captureId()).isNotEqualTo(captured.captureId());
        verify(http, times(2)).read(SOURCE, RELATIVE_PAGE);
    }

    @Test
    void captureIsBoundToWorkspaceAndSelectedPage()
    {
        SelectedDocumentationReader.Page captured = first(false);
        when(notebooks.getNotebook("workspace-2")).thenReturn(notebook(SOURCE));
        assertThatThrownBy(() -> reader.read("workspace-2", SOURCE.id(), RELATIVE_PAGE, Optional.of(captured.captureId()), 1, false))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThatThrownBy(() -> reader.read(WORKSPACE, SOURCE.id(), Optional.of("other.html"), Optional.of(captured.captureId()), 1, false))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(http).read(SOURCE, RELATIVE_PAGE);
    }

    @Test
    void reconfiguredSourceCannotUseEarlierCapture()
    {
        SelectedDocumentationReader.Page captured = first(false);
        RegisteredContextSource changed = new RegisteredContextSource(SOURCE.id(), DOCUMENTATION_ROOT, "https://docs.example.com/older/", USER, Optional.empty());
        when(notebooks.getNotebook(WORKSPACE)).thenReturn(notebook(changed));
        assertThatThrownBy(() -> read(captured.captureId(), 1)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(http).read(SOURCE, RELATIVE_PAGE);
    }

    @Test
    void sourceRemovalDuringFetchRejectsResultWithoutCaching()
    {
        when(http.read(SOURCE, RELATIVE_PAGE)).thenAnswer(_ -> {
            when(notebooks.getNotebook(WORKSPACE)).thenReturn(RepositoryNotebook.empty());
            return capture("Now revoked source");
        });
        assertThatThrownBy(() -> first(false)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        when(notebooks.getNotebook(WORKSPACE)).thenReturn(notebook(SOURCE));
        doReturn(capture("New observation")).when(http).read(SOURCE, RELATIVE_PAGE);
        assertThat(first(false).excerpt()).isEqualTo("New observation");
        verify(http, times(2)).read(SOURCE, RELATIVE_PAGE);
    }

    @Test
    void unknownSourceDoesNotFetch()
    {
        assertThatThrownBy(() -> reader.read(WORKSPACE, "unknown", RELATIVE_PAGE, Optional.empty(), 1, false)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(http);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://other.example.com/", "//other.example.com/", "../outside.html", "page.html?token=secret", "page%2Fother.html"})
    void escapedSelectorsAreRejectedBeforeFetch(String relative)
    {
        assertThatThrownBy(() -> reader.read(WORKSPACE, SOURCE.id(), Optional.of(relative), Optional.empty(), 1, false)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(http);
    }

    @Test
    void invalidPaginationCannotStartRemoteWork()
    {
        assertThatThrownBy(() -> reader.read(WORKSPACE, SOURCE.id(), RELATIVE_PAGE, Optional.empty(), 2, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(WORKSPACE, SOURCE.id(), RELATIVE_PAGE, Optional.empty(), 0, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(WORKSPACE, SOURCE.id(), RELATIVE_PAGE, Optional.of("invalid"), 1, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(WORKSPACE, SOURCE.id(), RELATIVE_PAGE, Optional.of("invalid"), 1, true)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(http);
        SelectedDocumentationReader.Page captured = first(false);
        assertThatThrownBy(() -> read(captured.captureId(), Integer.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
        verify(http).read(SOURCE, RELATIVE_PAGE);
    }

    @Test
    void aPageReadByItsAddressNeedsNoRegistrationAndContinuesFromItsCapture()
    {
        RegisteredContextSource page = SelectedDocumentationReader.page("https://iceberg.apache.org/spec/");
        String text = "a".repeat(SelectedDocumentationReader.PAGE_BYTES + 1);
        when(http.read(page, Optional.empty())).thenReturn(new DocumentationHttpReader.Read(page.url(), text, Hashing.sha256().hashString(text, UTF_8).toString(),
                READ_AT, false, ImmutableList.of()));

        SelectedDocumentationReader.Page first = reader.readPage(WORKSPACE, page, Optional.empty(), 1, Optional.empty());
        assertThat(first.source()).isEqualTo(page);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(reader.readPage(WORKSPACE, page, Optional.of(first.captureId()), 2, Optional.empty()).excerpt()).isEqualTo("a");
        assertThat(SelectedDocumentationReader.readByAddress(page)).isTrue();
        assertThat(SelectedDocumentationReader.readByAddress(SOURCE)).isFalse();
        assertThatThrownBy(() -> reader.readPage(WORKSPACE, SOURCE, Optional.empty(), 1, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        verify(http).read(page, Optional.empty());
    }

    @Test
    void cacheHasBoundedCapacity()
    {
        when(http.read(any(), any())).thenReturn(capture("Bounded capture"));
        SelectedDocumentationReader.Page oldest = first(false);
        for (int index = 0; index < 32; index++) {
            reader.read(WORKSPACE, SOURCE.id(), Optional.of("page-" + index + ".html"), Optional.empty(), 1, false);
        }
        assertThatThrownBy(() -> read(oldest.captureId(), 1)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(http, times(33)).read(any(), any());
    }

    private SelectedDocumentationReader.Page first(boolean refresh)
    {
        return reader.read(WORKSPACE, SOURCE.id(), RELATIVE_PAGE, Optional.empty(), 1, refresh);
    }

    private SelectedDocumentationReader.Page read(String captureId, int page)
    {
        return reader.read(WORKSPACE, SOURCE.id(), RELATIVE_PAGE, Optional.of(captureId), page, false);
    }

    @Test
    void aLinksSectionOrLineOpensThePageHoldingItAndContinuesFromThere()
    {
        RegisteredContextSource page = SelectedDocumentationReader.page("https://iceberg.apache.org/spec/");
        String contents = "Partitioning\n" + "a".repeat(SelectedDocumentationReader.PAGE_BYTES);
        String text = contents + "\nPartitioning\nPartition specs describe how\n" + "b".repeat(SelectedDocumentationReader.PAGE_BYTES);
        int heading = contents.length() + 1;
        when(http.read(page, Optional.empty())).thenReturn(new DocumentationHttpReader.Read(page.url(), text, Hashing.sha256().hashString(text, UTF_8).toString(),
                READ_AT, false, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), false, ImmutableMap.of("partitioning", heading)));

        SelectedDocumentationReader.Page section = reader.readPage(WORKSPACE, page, Optional.empty(), 1, Optional.of("partitioning"));
        assertThat(section.page()).isEqualTo(2);
        assertThat(section.totalPages()).isEqualTo(3);
        assertThat(section.excerpt()).contains("Partition specs describe how");
        assertThat(section.gaps()).contains("Opened at #partitioning on page 2 of 3; earlier pages hold what comes before it.");
        assertThat(reader.readPage(WORKSPACE, page, Optional.of(section.captureId()), 3, Optional.empty()).excerpt()).endsWith("b");

        assertThat(reader.readPage(WORKSPACE, page, Optional.empty(), 1, Optional.of("L3")).page()).isEqualTo(2);
        SelectedDocumentationReader.Page missing = reader.readPage(WORKSPACE, page, Optional.empty(), 1, Optional.of("appendix-z"));
        assertThat(missing.page()).isEqualTo(1);
        assertThat(missing.gaps()).contains("No anchor, heading or line of the captured text matches #appendix-z; this is the first page.");
    }

    @Test
    void aFirstReadReturnsTheHeadingsOfTheDocumentWithThePageEachStartsOn()
    {
        RegisteredContextSource page = SelectedDocumentationReader.page("https://iceberg.apache.org/spec/");
        String contents = "Contents\nPartitioning\nManifests\n";
        String text = contents + "Partitioning 🔗\nPartition specs\n" + "a".repeat(SelectedDocumentationReader.PAGE_BYTES) + "\nManifests\nManifest files\n";
        int partitioning = contents.length();
        int manifests = text.indexOf("\nManifests\n", partitioning) + 1;
        when(http.read(page, Optional.empty())).thenReturn(new DocumentationHttpReader.Read(page.url(), text, Hashing.sha256().hashString(text, UTF_8).toString(),
                READ_AT, false, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), false,
                ImmutableMap.of("toc", 0, "partitioning", partitioning, "manifests", manifests, "manifests-1", manifests)));

        SelectedDocumentationReader.Page first = reader.readPage(WORKSPACE, page, Optional.empty(), 1, Optional.empty());
        assertThat(first.outline()).containsExactly(new SelectedDocumentationReader.Heading("partitioning", "Partitioning", 1),
                new SelectedDocumentationReader.Heading("manifests", "Manifests", 2), new SelectedDocumentationReader.Heading("manifests-1", "Manifests", 2));
        assertThat(reader.readPage(WORKSPACE, page, Optional.of(first.captureId()), 2, Optional.empty()).outline()).isEmpty();
    }

    @Test
    void aCaptureHoldsAnEarlierExcerptWhereverItNowFalls()
    {
        RegisteredContextSource page = SelectedDocumentationReader.page("https://iceberg.apache.org/spec/");
        String text = "A new section added above.\n\nManifests\nReaders must ignore   entries\nwith status DELETED.\n";
        when(http.read(page, Optional.empty())).thenReturn(new DocumentationHttpReader.Read(page.url(), text, Hashing.sha256().hashString(text, UTF_8).toString(),
                READ_AT, false, ImmutableList.of()));

        String captureId = reader.readPage(WORKSPACE, page, Optional.empty(), 1, Optional.empty()).captureId();

        assertThat(reader.holds(captureId, "Manifests\nReaders must ignore entries with status DELETED.")).isTrue();
        assertThat(reader.holds(captureId, "Readers must ignore entries with status EXISTING.")).isFalse();
        assertThat(reader.holds("00000000-0000-0000-0000-000000000000", "Manifests")).isFalse();
    }

    private static DocumentationHttpReader.Read capture(String text)
    {
        return new DocumentationHttpReader.Read(SOURCE.url() + RELATIVE_PAGE.orElseThrow(), text, Hashing.sha256().hashString(text, UTF_8).toString(),
                READ_AT, false, ImmutableList.of());
    }

    private static RepositoryNotebook notebook(RegisteredContextSource source)
    {
        return new RepositoryNotebook(1, 1, true, ImmutableList.of(source), ImmutableList.of(ContextScope.project()),
                ImmutableList.of(), ImmutableList.of(), Optional.empty());
    }
}
