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

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static com.bytequay.core.context.ContextLimits.bytes;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestSelectedDocumentationReader
{
    private static final String URL = "https://iceberg.apache.org/spec/";
    private static final Instant READ_AT = Instant.parse("2026-01-01T00:00:00Z");

    private final DocumentationHttpReader http = mock(DocumentationHttpReader.class);
    private final AtomicLong nanos = new AtomicLong();
    private final SelectedDocumentationReader reader = new SelectedDocumentationReader(http, new Ticker()
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
        when(http.read(any())).thenReturn(capture("Captured concepts"));
    }

    @Test
    void aFirstReadFetchesAndContinuationReusesItsCapture()
    {
        SelectedDocumentationReader.Page first = first();
        SelectedDocumentationReader.Page reused = read(first.captureId(), 1);
        assertThat(first.cacheHit()).isFalse();
        assertThat(reused.cacheHit()).isTrue();
        assertThat(reused.captureId()).isEqualTo(first.captureId());
        assertThat(reused.readAt()).isEqualTo(READ_AT);
        assertThat(reused.contentHash()).isEqualTo(first.contentHash());
        assertThat(reused.gaps()).anyMatch(gap -> gap.contains("unverified"));
        verify(http).read(any());

        SelectedDocumentationReader.Page fresh = first();
        assertThat(fresh.captureId()).isNotEqualTo(first.captureId());
        assertThatThrownBy(() -> read(first.captureId(), 1)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(http, times(2)).read(argThat(source -> source.url().equals(URL)));
    }

    @Test
    void unicodePagesReconstructExactTextAndContinuationDoesNotFetch()
    {
        String text = "a".repeat(SelectedDocumentationReader.PAGE_BYTES - 1) + "😀世界".repeat(2000);
        when(http.read(any())).thenReturn(capture(text));
        SelectedDocumentationReader.Page first = first();
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
        verify(http).read(any());
    }

    @Test
    void aFailedReadFallsBackToTheEarlierCaptureAndSaysSo()
    {
        when(http.read(any())).thenReturn(capture(""));
        SelectedDocumentationReader.Page original = first();
        assertThat(original.totalPages()).isEqualTo(1);
        assertThat(original.excerpt()).isEmpty();
        assertThat(original.refreshFailed()).isFalse();
        doThrow(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Timed out")).when(http).read(any());

        SelectedDocumentationReader.Page fallback = first();
        assertThat(fallback.captureId()).isEqualTo(original.captureId());
        assertThat(fallback.readAt()).isEqualTo(READ_AT);
        assertThat(fallback.refreshFailed()).isTrue();
        assertThat(fallback.cacheHit()).isTrue();
        assertThat(fallback.gaps()).anyMatch(gap -> gap.contains("refresh failed"));
    }

    @Test
    void failedMissIsNotCachedAsEmpty()
    {
        doThrow(new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported format")).when(http).read(any());
        assertThatThrownBy(this::first).isInstanceOf(ResponseStatusException.class).hasMessageContaining("415");
        doReturn(capture("Recovered document")).when(http).read(any());
        assertThat(first().excerpt()).isEqualTo("Recovered document");
        verify(http, times(2)).read(any());
    }

    @Test
    void expiryAndRestartRequireANewFirstRead()
    {
        SelectedDocumentationReader.Page captured = first();
        nanos.set(Duration.ofMinutes(10).toNanos());
        assertThatThrownBy(() -> read(captured.captureId(), 1)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        SelectedDocumentationReader restarted = new SelectedDocumentationReader(http);
        assertThatThrownBy(() -> restarted.readPage(URL, Optional.of(captured.captureId()), 1, Optional.empty()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(http).read(any());
    }

    @Test
    void aCaptureBelongsToItsPage()
    {
        SelectedDocumentationReader.Page captured = first();
        assertThatThrownBy(() -> reader.readPage("https://iceberg.apache.org/view-spec/", Optional.of(captured.captureId()), 1, Optional.empty()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(http).read(any());
    }

    @Test
    void invalidPaginationCannotStartRemoteWork()
    {
        assertThatThrownBy(() -> reader.readPage(URL, Optional.empty(), 2, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.readPage(URL, Optional.empty(), 0, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.readPage(URL, Optional.of("invalid"), 1, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(http);
        SelectedDocumentationReader.Page captured = first();
        assertThatThrownBy(() -> read(captured.captureId(), Integer.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
        verify(http).read(any());
    }

    @Test
    void cacheHasBoundedCapacity()
    {
        SelectedDocumentationReader.Page oldest = first();
        for (int index = 0; index < 32; index++) {
            reader.readPage("https://docs.example.com/page-" + index + ".html", Optional.empty(), 1, Optional.empty());
        }
        assertThatThrownBy(() -> read(oldest.captureId(), 1)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(http, times(33)).read(any());
    }

    @Test
    void aLinksSectionOrLineOpensThePageHoldingItAndContinuesFromThere()
    {
        String contents = "Partitioning\n" + "a".repeat(SelectedDocumentationReader.PAGE_BYTES);
        String text = contents + "\nPartitioning\nPartition specs describe how\n" + "b".repeat(SelectedDocumentationReader.PAGE_BYTES);
        int heading = contents.length() + 1;
        when(http.read(any())).thenReturn(new DocumentationHttpReader.Read(URL, text, Hashing.sha256().hashString(text, UTF_8).toString(),
                READ_AT, false, ImmutableList.of(), ImmutableMap.of("partitioning", heading)));

        SelectedDocumentationReader.Page section = reader.readPage(URL, Optional.empty(), 1, Optional.of("partitioning"));
        assertThat(section.page()).isEqualTo(2);
        assertThat(section.totalPages()).isEqualTo(3);
        assertThat(section.excerpt()).contains("Partition specs describe how");
        assertThat(section.gaps()).contains("Opened at #partitioning on page 2 of 3; earlier pages hold what comes before it.");
        assertThat(read(section.captureId(), 3).excerpt()).endsWith("b");

        assertThat(reader.readPage(URL, Optional.empty(), 1, Optional.of("L3")).page()).isEqualTo(2);
        SelectedDocumentationReader.Page missing = reader.readPage(URL, Optional.empty(), 1, Optional.of("appendix-z"));
        assertThat(missing.page()).isEqualTo(1);
        assertThat(missing.gaps()).contains("No anchor, heading or line of the captured text matches #appendix-z; this is the first page.");
    }

    @Test
    void aFirstReadReturnsTheHeadingsOfTheDocumentWithThePageEachStartsOn()
    {
        String contents = "Contents\nPartitioning\nManifests\n";
        String text = contents + "Partitioning 🔗\nPartition specs\n" + "a".repeat(SelectedDocumentationReader.PAGE_BYTES) + "\nManifests\nManifest files\n";
        int partitioning = contents.length();
        int manifests = text.indexOf("\nManifests\n", partitioning) + 1;
        when(http.read(any())).thenReturn(new DocumentationHttpReader.Read(URL, text, Hashing.sha256().hashString(text, UTF_8).toString(),
                READ_AT, false, ImmutableList.of(), ImmutableMap.of("toc", 0, "partitioning", partitioning, "manifests", manifests, "manifests-1", manifests)));

        SelectedDocumentationReader.Page first = first();
        assertThat(first.outline()).containsExactly(new SelectedDocumentationReader.Heading("partitioning", "Partitioning", 1),
                new SelectedDocumentationReader.Heading("manifests", "Manifests", 2), new SelectedDocumentationReader.Heading("manifests-1", "Manifests", 2));
        assertThat(read(first.captureId(), 2).outline()).isEmpty();
    }

    private SelectedDocumentationReader.Page first()
    {
        return reader.readPage(URL, Optional.empty(), 1, Optional.empty());
    }

    private SelectedDocumentationReader.Page read(String captureId, int page)
    {
        return reader.readPage(URL, Optional.of(captureId), page, Optional.empty());
    }

    private static DocumentationHttpReader.Read capture(String text)
    {
        return new DocumentationHttpReader.Read(URL, text, Hashing.sha256().hashString(text, UTF_8).toString(),
                READ_AT, false, ImmutableList.of(), ImmutableMap.of());
    }
}
