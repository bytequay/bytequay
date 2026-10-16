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
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.RegisteredContextSource;
import com.google.common.hash.Hashing;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.io.CloseMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bytequay.core.context.ContextEntryOrigin.USER;
import static com.bytequay.core.context.RegisteredContextSource.Kind.DOCUMENTATION_PAGE;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Isolated("Temporarily changes the JVM proxy selector")
class TestDocumentationHttpReader
{
    private static final RegisteredContextSource PAGE = new RegisteredContextSource("page", DOCUMENTATION_PAGE, "https://docs.example.com/manual/concepts.html", USER, Optional.empty());
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final CloseableHttpClient client = mock(CloseableHttpClient.class);
    private final DocumentationHttpReader reader = new DocumentationHttpReader(CLOCK, () -> client, Duration.ofSeconds(2));

    @Test
    void readsOneSelectedHtmlPageAsUntrustedText()
            throws Exception
    {
        when(client.executeOpen(isNull(), any(), isNull())).thenReturn(response("text/html; charset=UTF-8", """
                <html><head><style>body { color: red; }</style><script>secret()</script></head>
                <body><h1>Concepts &amp; glossary</h1><p>Snapshot 😀</p><a href="other.html">Other page</a></body></html>
                """));

        DocumentationHttpReader.Read result = reader.read(PAGE);

        assertThat(result.resolvedUrl()).isEqualTo(PAGE.url());
        assertThat(result.text()).contains("Concepts & glossary", "Snapshot 😀", "Other page").doesNotContain("secret", "color", "href", "other.html");
        assertThat(result.contentHash()).isEqualTo(Hashing.sha256().hashString(result.text(), UTF_8).toString());
        assertThat(result.readAt()).isEqualTo(CLOCK.instant());
        assertThat(result.truncated()).isFalse();
        assertThat(result.gaps()).anyMatch(gap -> gap.contains("untrusted") && gap.contains("unverified"));
        ArgumentCaptor<ClassicHttpRequest> request = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        verify(client).executeOpen(isNull(), request.capture(), isNull());
        assertThat(request.getValue().getFirstHeader("Accept-Encoding").getValue()).isEqualTo("identity");
        assertThat(request.getValue().getFirstHeader("Authorization")).isNull();
        assertThat(request.getValue().getFirstHeader("Cookie")).isNull();
        verify(client).close(CloseMode.IMMEDIATE);
    }

    @Test
    void followsOnlyRegisteredRedirects()
            throws Exception
    {
        when(client.executeOpen(isNull(), any(), isNull()))
                .thenReturn(redirect("concepts.html"), response("text/plain", "Concepts"));

        DocumentationHttpReader.Read result = reader.read(PAGE);

        assertThat(result.resolvedUrl()).isEqualTo(PAGE.url());
        assertThat(result.text()).isEqualTo("Concepts");
        verify(client, times(2)).executeOpen(isNull(), any(), isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://outside.example.com/manual/concepts.html", "http://docs.example.com/manual/concepts.html", "/elsewhere/concepts.html",
            "/manual/concepts.html/more", "https://docs.example.com:8443/manual/concepts.html", "https://user@docs.example.com/manual/concepts.html",
            "/manual/%2e%2e/manual/concepts.html", "/manual/concepts.html?q=a"})
    void rejectsEscapingRedirectBeforeSecondRequest(String location)
            throws Exception
    {
        when(client.executeOpen(isNull(), any(), isNull())).thenReturn(redirect(location));

        assertStatus(() -> reader.read(PAGE), HttpStatus.BAD_GATEWAY);
        verify(client).executeOpen(isNull(), any(), isNull());
    }

    @Test
    void exactPageCannotRedirectToSibling()
            throws Exception
    {
        when(client.executeOpen(isNull(), any(), isNull())).thenReturn(redirect("other.html"));

        assertStatus(() -> reader.read(PAGE), HttpStatus.BAD_GATEWAY);
        verify(client).executeOpen(isNull(), any(), isNull());
    }

    @Test
    void stopsAfterThreeRedirects()
            throws Exception
    {
        when(client.executeOpen(isNull(), any(), isNull()))
                .thenAnswer(_ -> redirect("concepts.html"));

        assertStatus(() -> reader.read(PAGE), HttpStatus.BAD_GATEWAY);
        verify(client, times(4)).executeOpen(isNull(), any(), isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/pdf", "image/png", "application/xhtml+xml", "text/plain; charset=nonexistent"})
    void reportsUnsupportedFormatOrCharset(String type)
            throws Exception
    {
        when(client.executeOpen(isNull(), any(), isNull())).thenReturn(response(type, "body"));

        assertStatus(() -> reader.read(PAGE), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void rejectsUnexpectedCompression()
            throws Exception
    {
        ClassicHttpResponse response = response("text/plain", "body");
        response.setHeader("Content-Encoding", "gzip");
        when(client.executeOpen(isNull(), any(), isNull())).thenReturn(response);

        assertStatus(() -> reader.read(PAGE), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void emptySuccessDiffersFromMissingBody()
            throws Exception
    {
        when(client.executeOpen(isNull(), any(), isNull())).thenReturn(response("text/plain", ""), new BasicClassicHttpResponse(200));

        assertThat(reader.read(PAGE).text()).isEmpty();
        assertStatus(() -> reader.read(PAGE), HttpStatus.BAD_GATEWAY);
    }

    @Test
    void decodesDeclaredCharsetAndRejectsInvalidBytes()
            throws Exception
    {
        when(client.executeOpen(isNull(), any(), isNull()))
                .thenReturn(response("text/plain; charset=ISO-8859-1", "café".getBytes(ISO_8859_1)), response("text/plain", new byte[] {(byte) 0xc3}));

        assertThat(reader.read(PAGE).text()).isEqualTo("café");
        assertStatus(() -> reader.read(PAGE), HttpStatus.BAD_GATEWAY);
    }

    @Test
    void boundsExtractedUtf8TextWithoutSplittingSurrogatePairs()
            throws Exception
    {
        when(client.executeOpen(isNull(), any(), isNull())).thenReturn(response("text/html", "<p>" + "😀".repeat(80_000) + "</p>"));

        DocumentationHttpReader.Read result = reader.read(PAGE);

        assertThat(ContextLimits.bytes(result.text())).isLessThanOrEqualTo(256 * 1024);
        assertThat(result.text()).isEqualTo("😀".repeat(65_536));
        assertThat(result.truncated()).isTrue();
        assertThat(result.gaps()).anyMatch(gap -> gap.contains("256 KiB"));
    }

    @Test
    void readsAGitHubFilePageAsItsRawTextAndKeepsItsGitHubAddress()
            throws Exception
    {
        RegisteredContextSource protocol = new RegisteredContextSource("protocol", RegisteredContextSource.Kind.DOCUMENTATION_PAGE,
                "https://github.com/delta-io/delta/blob/master/PROTOCOL.md", ContextEntryOrigin.GENERATED, Optional.empty());
        String markdown = "# Delta Transaction Log Protocol\n\nIntro\n\n## Add File and Remove File\n\nPaths are URI encoded.\n\n```\n# not a heading\n```\n"
                + "## Add File and Remove File\n\nA repeat.\n";
        when(client.executeOpen(isNull(), any(), isNull())).thenReturn(response("text/plain; charset=utf-8", markdown));

        DocumentationHttpReader.Read result = reader.read(protocol);

        ArgumentCaptor<ClassicHttpRequest> request = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        verify(client).executeOpen(isNull(), request.capture(), isNull());
        assertThat(request.getValue().getUri()).isEqualTo(URI.create("https://raw.githubusercontent.com/delta-io/delta/master/PROTOCOL.md"));
        assertThat(result.resolvedUrl()).isEqualTo(protocol.url());
        assertThat(result.text()).isEqualTo(markdown);
        assertThat(result.sections()).containsEntry("add-file-and-remove-file", markdown.indexOf("## Add File"))
                .containsEntry("add-file-and-remove-file-1", markdown.lastIndexOf("## Add File"))
                .containsKey("delta-transaction-log-protocol");
        assertThat(DocumentationHttpReader.fetched(URI.create("https://github.com/delta-io/delta"))).isEqualTo(URI.create("https://github.com/delta-io/delta"));
        assertThat(DocumentationHttpReader.fetched(URI.create("https://docs.example.com/a/b/blob/c/d"))).isEqualTo(URI.create("https://docs.example.com/a/b/blob/c/d"));
    }

    @Test
    void anElementIdMarksWhereItsSectionStartsRatherThanAContentsEntry()
            throws Exception
    {
        when(client.executeOpen(isNull(), any(), isNull())).thenReturn(response("text/html",
                "<nav><a href=\"#partitioning\">Partitioning</a></nav><h2 id=\"partitioning\">Partitioning</h2><p>Partition specs</p>"));

        DocumentationHttpReader.Read result = reader.read(PAGE);

        assertThat(result.text().substring(result.sections().get("partitioning"))).isEqualTo("Partitioning\nPartition specs");
    }

    @Test
    void neverReadsBeyondResponseLimit()
            throws Exception
    {
        AtomicInteger observed = new AtomicInteger();
        InputStream stream = new ByteArrayInputStream(" ".repeat(1024 * 1024 + 100).getBytes(UTF_8))
        {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length)
            {
                int count = super.read(bytes, offset, length);
                observed.addAndGet(Math.max(count, 0));
                return count;
            }
        };
        ClassicHttpResponse response = response("text/plain", "");
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContent()).thenReturn(stream);
        response.setEntity(entity);
        when(client.executeOpen(isNull(), any(), isNull())).thenReturn(response);

        DocumentationHttpReader.Read result = reader.read(PAGE);

        assertThat(observed).hasValue(1024 * 1024);
        assertThat(result.truncated()).isTrue();
        assertThat(result.gaps()).anyMatch(gap -> gap.contains("1 MiB"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.1.2.3", "10.0.0.1", "127.0.0.1", "169.254.1.1", "172.16.0.1", "192.168.1.1", "100.64.1.1", "100.127.255.254",
            "192.0.0.1", "192.0.2.1", "192.88.99.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255",
            "::", "::1", "::ffff:127.0.0.1", "fe80::1", "fc00::1", "fd00::1", "ff00::1", "64:ff9b::7f00:1", "2001:db8::1", "2002:7f00:1::1", "3fff::1"})
    void rejectsNonPublicResolvedAddresses(String address)
            throws Exception
    {
        DnsResolver resolver = mock(DnsResolver.class);
        when(resolver.resolve("docs.example.com")).thenReturn(new InetAddress[] {InetAddress.getByName(address)});

        assertThatThrownBy(() -> DocumentationHttpReader.publicDns(resolver).resolve("docs.example.com")).isInstanceOf(UnknownHostException.class);
    }

    @Test
    void rejectsMixedAnswersAndRechecksEveryResolution()
            throws Exception
    {
        DnsResolver resolver = mock(DnsResolver.class);
        InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
        InetAddress privateAddress = InetAddress.getByName("127.0.0.1");
        when(resolver.resolve("docs.example.com")).thenReturn(new InetAddress[] {publicAddress}, new InetAddress[] {publicAddress, privateAddress});
        DnsResolver guarded = DocumentationHttpReader.publicDns(resolver);

        assertThat(guarded.resolve("docs.example.com")).containsExactly(publicAddress);
        assertThatThrownBy(() -> guarded.resolve("docs.example.com")).isInstanceOf(UnknownHostException.class);
        verify(resolver, times(2)).resolve("docs.example.com");
        assertThat(guarded.resolveCanonicalHostname("docs.example.com")).isEqualTo("docs.example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "192.0.64.1", "100.128.0.1", "2606:4700:4700::1111", "2001:4860:4860::8888"})
    void permitsPublicResolvedAddresses(String address)
            throws Exception
    {
        DnsResolver resolver = mock(DnsResolver.class);
        InetAddress resolved = InetAddress.getByName(address);
        when(resolver.resolve("docs.example.com")).thenReturn(new InetAddress[] {resolved});

        assertThat(DocumentationHttpReader.publicDns(resolver).resolve("docs.example.com")).containsExactly(resolved);
    }

    @Test
    void socketsNeverConsultAmbientProxySelection()
            throws Exception
    {
        ProxySelector previous = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector()
        {
            @Override
            public List<Proxy> select(URI uri)
            {
                throw new AssertionError("Ambient proxy selection must not run");
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure)
            {
                throw new AssertionError("Ambient proxy connection must not run");
            }
        });
        try (Socket socket = DocumentationHttpReader.directSocket()) {
            // An unresolved endpoint fails before a socket connection, keeping this regression offline.
            assertThatThrownBy(() -> socket.connect(InetSocketAddress.createUnresolved("docs.invalid", 443), 1)).isInstanceOf(UnknownHostException.class);
        }
        finally {
            ProxySelector.setDefault(previous);
        }
    }

    @Test
    void realHttpConnectionUsesGuardedResolverBeforeOpeningSocket()
            throws Exception
    {
        DnsResolver resolver = mock(DnsResolver.class);
        when(resolver.resolve("docs.example.com")).thenReturn(new InetAddress[] {InetAddress.getByName("127.0.0.1")});
        DocumentationHttpReader actual = new DocumentationHttpReader(CLOCK, () -> DocumentationHttpReader.client(resolver), Duration.ofSeconds(2));

        assertStatus(() -> actual.read(PAGE), HttpStatus.BAD_GATEWAY);
        verify(resolver).resolve("docs.example.com");
    }

    @Test
    void deadlinesCloseClientButRetainSlotsUntilActualWorkerExit()
            throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(2);
        when(client.executeOpen(isNull(), any(), isNull())).thenAnswer(_ -> {
            try {
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    }
                    catch (InterruptedException ignored) {
                        // Simulate a DNS resolver that cannot be interrupted.
                    }
                }
                throw new IOException("Released blocked fixture");
            }
            finally {
                exited.countDown();
            }
        });
        DocumentationHttpReader bounded = new DocumentationHttpReader(CLOCK, () -> client, Duration.ofMillis(200));
        try {
            assertStatus(() -> bounded.read(PAGE), HttpStatus.GATEWAY_TIMEOUT);
            assertStatus(() -> bounded.read(PAGE), HttpStatus.GATEWAY_TIMEOUT);
            assertStatus(() -> bounded.read(PAGE), HttpStatus.SERVICE_UNAVAILABLE);
            verify(client, times(2)).close(CloseMode.IMMEDIATE);
            verify(client, times(2)).executeOpen(isNull(), any(), isNull());
        }
        finally {
            release.countDown();
            assertThat(exited.await(2, SECONDS)).isTrue();
        }
    }

    private static ClassicHttpResponse response(String type, String body)
    {
        return response(type, body.getBytes(UTF_8));
    }

    private static ClassicHttpResponse response(String type, byte[] body)
    {
        ClassicHttpResponse response = new BasicClassicHttpResponse(200);
        response.setHeader("Content-Type", type);
        response.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_OCTET_STREAM));
        return response;
    }

    private static ClassicHttpResponse redirect(String location)
    {
        ClassicHttpResponse response = new BasicClassicHttpResponse(302);
        response.setHeader("Location", location);
        return response;
    }

    private static void assertStatus(Runnable action, HttpStatus status)
    {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(status));
    }
}
