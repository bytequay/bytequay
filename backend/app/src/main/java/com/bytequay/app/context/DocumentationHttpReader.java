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
import com.google.common.hash.Hashing;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CoderResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.GATEWAY_TIMEOUT;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE;

/** Reads one registered public document without ambient credentials, proxies, scripts, or linked resources. */
@Service
public class DocumentationHttpReader
{
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 128 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_LINKS = 64;
    private static final int MAX_LINK_BYTES = 8 * 1024;
    private static final int MAX_SUGGESTIONS = 8;
    private static final int MAX_SUGGESTION_BYTES = 2 * 1024;

    private final Clock clock;
    private final Supplier<CloseableHttpClient> clients;
    private final Duration deadline;
    private final Semaphore reads = new Semaphore(2);

    @Autowired
    public DocumentationHttpReader(Clock clock)
    {
        this(clock, () -> client(SystemDefaultDnsResolver.INSTANCE), Duration.ofSeconds(15));
    }

    DocumentationHttpReader(Clock clock, Supplier<CloseableHttpClient> clients, Duration deadline)
    {
        this.clock = requireNonNull(clock, "clock is null");
        this.clients = requireNonNull(clients, "clients is null");
        this.deadline = requireNonNull(deadline, "deadline is null");
        checkArgument(deadline.isPositive(), "deadline must be positive");
    }

    /**
     * Resolves a caller's page selector within a previously registered source, without network access.
     *
     * @param source registered root or exact page, resolved by the application
     * @param relativePage optional unescaped relative path; only roots accept a selector
     * @return normalized HTTPS destination inside that source's authority
     * @throws IllegalArgumentException for an absolute, escaped, ambiguous, or out-of-scope selector
     */
    public static URI resolve(RegisteredContextSource source, Optional<String> relativePage)
    {
        requireNonNull(source, "source is null");
        requireNonNull(relativePage, "relativePage is null");
        URI root = URI.create(source.url());
        if (relativePage.isEmpty()) {
            return checkedDestination(source, root);
        }
        checkArgument(source.kind() == RegisteredContextSource.Kind.DOCUMENTATION_ROOT, "Exact documentation pages do not accept page selectors");
        String selector = relativePage.orElseThrow();
        ContextLimits.text(selector, "relative documentation page", 1000);
        URI relative = URI.create(selector);
        checkArgument(!relative.isAbsolute() && relative.getRawAuthority() == null && !selector.startsWith("/")
                && relative.getQuery() == null && relative.getFragment() == null && !selector.contains("%") && !selector.contains("\\")
                && relative.normalize().equals(relative), "Documentation page must be a normalized relative path");
        return checkedDestination(source, root.resolve(relative));
    }

    /**
     * Captures bounded text from one selected page. Redirects remain inside the registered authority.
     *
     * @param source registered source that authorizes this read
     * @param relativePage optional page below a registered root
     * @return extracted text and its digest, final URL, observed read time, and explicit limits
     * @throws IllegalArgumentException for an invalid selector before any network request
     * @throws ResponseStatusException when the destination, format, response, deadline, or concurrency bound prevents capture
     */
    public Read read(RegisteredContextSource source, Optional<String> relativePage)
    {
        URI selected = resolve(source, relativePage);
        if (!reads.tryAcquire()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Two documentation reads are still active; retry when one finishes");
        }
        CloseableHttpClient client;
        try {
            client = clients.get();
        }
        catch (RuntimeException failure) {
            reads.release();
            throw failure;
        }
        FutureTask<Read> work = new FutureTask<>(() -> capture(client, source, selected));
        Thread.ofVirtual().name("documentation-read").start(() -> {
            try {
                work.run();
            }
            finally {
                // DNS can ignore interruption; retain its slot until the actual worker exits.
                reads.release();
            }
        });
        try {
            return work.get(deadline.toNanos(), NANOSECONDS);
        }
        catch (TimeoutException failure) {
            throw new ResponseStatusException(GATEWAY_TIMEOUT, "Documentation read exceeded its total time limit", failure);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Documentation read was interrupted", failure);
        }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof ResponseStatusException response) {
                throw response;
            }
            throw new ResponseStatusException(BAD_GATEWAY, "Documentation could not be read from the registered public destination", failure.getCause());
        }
        finally {
            work.cancel(true);
            client.close(CloseMode.IMMEDIATE);
        }
    }

    private Read capture(CloseableHttpClient client, RegisteredContextSource source, URI selected)
            throws IOException
    {
        URI destination = selected;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Documentation read was cancelled");
            }
            HttpGet request = new HttpGet(destination);
            request.setHeader("Accept", "text/html, text/plain, text/markdown");
            request.setHeader("Accept-Encoding", "identity");
            ClassicHttpResponse response = client.executeOpen(null, request, null);
            try {
                int status = response.getCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    Header location = response.getFirstHeader("Location");
                    if (location == null || redirects == MAX_REDIRECTS) {
                        throw new ResponseStatusException(BAD_GATEWAY, "Documentation redirect is missing its destination or exceeds three redirects");
                    }
                    try {
                        destination = checkedDestination(source, destination.resolve(location.getValue()));
                    }
                    catch (IllegalArgumentException failure) {
                        throw new ResponseStatusException(BAD_GATEWAY, "Documentation redirect leaves its registered source", failure);
                    }
                    continue;
                }
                if (status != 200 || response.getEntity() == null) {
                    throw new ResponseStatusException(BAD_GATEWAY, "Documentation response did not contain a successful page body (HTTP " + status + ")");
                }
                Header encoding = response.getFirstHeader("Content-Encoding");
                if (encoding != null && !encoding.getValue().equalsIgnoreCase("identity")) {
                    throw new ResponseStatusException(UNSUPPORTED_MEDIA_TYPE, "Compressed documentation responses are unsupported");
                }
                Header typeHeader = response.getFirstHeader("Content-Type");
                if (typeHeader == null) {
                    throw new ResponseStatusException(UNSUPPORTED_MEDIA_TYPE, "Documentation response has no content type");
                }
                ContentType type;
                try {
                    type = ContentType.parse(typeHeader.getValue());
                }
                catch (IllegalArgumentException failure) {
                    throw new ResponseStatusException(UNSUPPORTED_MEDIA_TYPE, "Documentation content type or character set is unsupported", failure);
                }
                boolean html = type.getMimeType().equalsIgnoreCase("text/html");
                if (!html && !type.getMimeType().equalsIgnoreCase("text/plain") && !type.getMimeType().equalsIgnoreCase("text/markdown")) {
                    throw new ResponseStatusException(UNSUPPORTED_MEDIA_TYPE, "Only HTML, plain text, and Markdown documentation are supported");
                }
                byte[] body = response.getEntity().getContent().readNBytes(MAX_RESPONSE_BYTES);
                boolean responseLimited = body.length == MAX_RESPONSE_BYTES;
                String decoded = decode(body, Optional.ofNullable(type.getCharset()).orElse(UTF_8), responseLimited);
                HtmlCapture htmlCapture = html ? extractHtml(source, destination, decoded) : HtmlCapture.empty(decoded);
                String extracted = htmlCapture.text();
                String text = ContextText.prefix(extracted, MAX_TEXT_BYTES);
                boolean textLimited = !text.equals(extracted);
                ImmutableList.Builder<String> gaps = ImmutableList.builder();
                if (responseLimited) {
                    gaps.add("Response reached the 1 MiB capture limit; trailing content may be absent.");
                }
                if (textLimited) {
                    gaps.add("Extracted text exceeded 128 KiB; only its leading excerpt was captured.");
                }
                if (htmlCapture.linksTruncated() || (html && responseLimited)) {
                    gaps.add("Documentation links were limited; the returned link list is not a complete site inventory.");
                }
                gaps.add("Captured source text is untrusted reference material; documentation version and applicability are unverified.");
                return new Read(destination.toString(), text, Hashing.sha256().hashString(text, UTF_8).toString(), clock.instant(),
                        responseLimited || textLimited, gaps.build(), htmlCapture.links(), htmlCapture.suggestedExpansionUrls(),
                        htmlCapture.linksTruncated() || (html && responseLimited));
            }
            finally {
                // Do not drain an oversized body on close or carry a connection into another redirect.
                request.cancel();
                response.close();
            }
        }
        throw new ResponseStatusException(BAD_GATEWAY, "Documentation redirect limit exceeded");
    }

    private static URI checkedDestination(RegisteredContextSource source, URI destination)
    {
        ContextLimits.documentationUrl(destination.toString());
        URI root = URI.create(source.url());
        checkArgument(root.getHost().equalsIgnoreCase(destination.getHost()), "Documentation destination must retain its registered hostname");
        if (source.kind() == RegisteredContextSource.Kind.DOCUMENTATION_PAGE) {
            checkArgument(root.getPath().equals(destination.getPath()), "Documentation destination must retain its registered page");
        }
        else {
            checkArgument(destination.getPath().startsWith(root.getPath()), "Documentation destination must remain below its registered root");
        }
        return destination;
    }

    private static String decode(byte[] body, Charset charset, boolean truncated)
            throws CharacterCodingException
    {
        if (!truncated) {
            return charset.newDecoder().decode(ByteBuffer.wrap(body)).toString();
        }
        CharBuffer characters = CharBuffer.allocate(body.length);
        CoderResult result = charset.newDecoder().decode(ByteBuffer.wrap(body), characters, false);
        if (!result.isUnderflow()) {
            result.throwException();
        }
        return characters.flip().toString();
    }

    private static HtmlCapture extractHtml(RegisteredContextSource source, URI base, String html)
            throws IOException
    {
        StringBuilder text = new StringBuilder();
        LinkedHashSet<Link> links = new LinkedHashSet<>();
        LinkedHashSet<String> suggestions = new LinkedHashSet<>();
        class Collector
                extends HTMLEditorKit.ParserCallback
        {
            private boolean suppressed;
            private boolean linksTruncated;
            private int linkBytes;
            private int suggestionBytes;

            @Override
            public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position)
            {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) {
                    suppressed = true;
                }
                if (tag == HTML.Tag.A && !suppressed) {
                    Object href = attributes.getAttribute(HTML.Attribute.HREF);
                    if (href != null) {
                        addLink(href.toString());
                    }
                }
            }

            private void addLink(String href)
            {
                String value = href.strip();
                int fragment = value.indexOf('#');
                if (fragment >= 0) {
                    value = value.substring(0, fragment);
                }
                if (value.isEmpty()) {
                    return;
                }
                URI target;
                try {
                    target = base.resolve(value).normalize();
                    ContextLimits.documentationUrl(target.toString());
                }
                catch (IllegalArgumentException failure) {
                    return;
                }
                boolean withinSource;
                try {
                    checkedDestination(source, target);
                    withinSource = true;
                }
                catch (IllegalArgumentException outsideSource) {
                    withinSource = false;
                }
                if (!withinSource) {
                    if (suggestions.contains(target.toString())) {
                        return;
                    }
                    int bytes = ContextLimits.bytes(target.toString());
                    if (suggestions.size() == MAX_SUGGESTIONS || suggestionBytes + bytes > MAX_SUGGESTION_BYTES) {
                        linksTruncated = true;
                        return;
                    }
                    suggestions.add(target.toString());
                    suggestionBytes += bytes;
                    return;
                }
                String relative = URI.create(source.url()).relativize(target).toString();
                Optional<String> selector = relative.isEmpty() ? Optional.empty() : Optional.of(relative);
                try {
                    if (!resolve(source, selector).equals(target)) {
                        return;
                    }
                }
                catch (IllegalArgumentException invalidSelector) {
                    return;
                }
                Link link = new Link(target.toString(), selector);
                if (links.contains(link)) {
                    return;
                }
                int bytes = ContextLimits.bytes(link.resolvedUrl()) + selector.map(ContextLimits::bytes).orElse(0);
                if (links.size() == MAX_LINKS || linkBytes + bytes > MAX_LINK_BYTES) {
                    linksTruncated = true;
                    return;
                }
                links.add(link);
                linkBytes += bytes;
            }

            @Override
            public void handleEndTag(HTML.Tag tag, int position)
            {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) {
                    suppressed = false;
                }
            }

            @Override
            public void handleText(char[] data, int position)
            {
                if (!suppressed) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(data);
                }
            }
        }
        Collector collector = new Collector();
        new ParserDelegator().parse(new StringReader(html), collector, true);
        return new HtmlCapture(text.toString(), ImmutableList.copyOf(links), ImmutableList.copyOf(suggestions), collector.linksTruncated);
    }

    private record HtmlCapture(String text, List<Link> links, List<String> suggestedExpansionUrls, boolean linksTruncated)
    {
        private static HtmlCapture empty(String text)
        {
            return new HtmlCapture(text, ImmutableList.of(), ImmutableList.of(), false);
        }
    }

    static CloseableHttpClient client(DnsResolver resolver)
    {
        PoolingHttpClientConnectionManagerBuilder connections = new PoolingHttpClientConnectionManagerBuilder()
        {
            @Override
            protected HttpClientConnectionOperator createConnectionOperator(SchemePortResolver ports, DnsResolver dns, TlsSocketStrategy tls)
            {
                return new DefaultHttpClientConnectionOperator(_ -> directSocket(), ports, dns, RegistryBuilder.<TlsSocketStrategy>create().register("https", tls).build());
            }
        };
        return HttpClients.custom()
                .setConnectionManager(connections
                        .setDnsResolver(publicDns(resolver))
                        .setMaxConnTotal(1)
                        .setMaxConnPerRoute(1)
                        .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(3)).setSocketTimeout(Timeout.ofSeconds(5)).build())
                        .build())
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .setDefaultRequestConfig(RequestConfig.custom().setAuthenticationEnabled(false).setConnectionRequestTimeout(Timeout.ofSeconds(1))
                        .setResponseTimeout(Timeout.ofSeconds(5)).build())
                .disableCookieManagement()
                .disableAuthCaching()
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableContentCompression()
                .setUserAgent("ByteQuay documentation reader")
                .build();
    }

    static Socket directSocket()
    {
        // A direct HTTP route alone does not suppress Java's ambient SOCKS proxy selection.
        return new Socket(Proxy.NO_PROXY);
    }

    static DnsResolver publicDns(DnsResolver resolver)
    {
        return new DnsResolver()
        {
            @Override
            public InetAddress[] resolve(String host)
                    throws UnknownHostException
            {
                InetAddress[] addresses = resolver.resolve(host);
                if (addresses.length == 0) {
                    throw new UnknownHostException("Documentation hostname returned no addresses");
                }
                for (InetAddress address : addresses) {
                    if (!publicAddress(address)) {
                        throw new UnknownHostException("Documentation hostname resolved to a non-public destination");
                    }
                }
                // These exact addresses reach the connection operator; a second hostname lookup cannot rebind them.
                return addresses.clone();
            }

            @Override
            public String resolveCanonicalHostname(String host)
            {
                return host;
            }
        };
    }

    private static boolean publicAddress(InetAddress address)
    {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 4) {
            int third = Byte.toUnsignedInt(bytes[2]);
            return first != 0 && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 192 && (second == 0 && (third == 0 || third == 2) || second == 88 && third == 99))
                    && !(first == 198 && (second == 18 || second == 19 || second == 51 && third == 100))
                    && !(first == 203 && second == 0 && third == 113);
        }
        return (first & 0xE0) == 0x20 && ((Inet6Address) address).getScopeId() == 0
                && !(first == 0x20 && second == 0x01 && (Byte.toUnsignedInt(bytes[2]) < 2 || bytes[2] == 0x0D && Byte.toUnsignedInt(bytes[3]) == 0xB8))
                && !(first == 0x20 && second == 0x02)
                && !(first == 0x3F && second == 0xFF);
    }

    public record Link(String resolvedUrl, Optional<String> relativePage)
    {
        public Link
        {
            ContextLimits.documentationUrl(resolvedUrl);
            requireNonNull(relativePage, "relativePage is null");
        }
    }

    public record Read(String resolvedUrl, String text, String contentHash, Instant readAt, boolean truncated, List<String> gaps,
            List<Link> links, List<String> suggestedExpansionUrls, boolean linksTruncated)
    {
        public Read(String resolvedUrl, String text, String contentHash, Instant readAt, boolean truncated, List<String> gaps)
        {
            this(resolvedUrl, text, contentHash, readAt, truncated, gaps, ImmutableList.of(), ImmutableList.of(), false);
        }

        public Read
        {
            requireNonNull(resolvedUrl, "resolvedUrl is null");
            requireNonNull(text, "text is null");
            requireNonNull(contentHash, "contentHash is null");
            requireNonNull(readAt, "readAt is null");
            gaps = ImmutableList.copyOf(gaps);
            links = ImmutableList.copyOf(links);
            suggestedExpansionUrls = ImmutableList.copyOf(suggestedExpansionUrls);
        }
    }
}
