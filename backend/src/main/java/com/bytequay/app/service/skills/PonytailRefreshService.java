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
package com.bytequay.app.service.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

@Service
public class PonytailRefreshService
{
    private static final Logger log = LoggerFactory.getLogger(PonytailRefreshService.class);
    private static final URI LATEST_URI =
            URI.create("https://registry.npmjs.org/@dietrichgebert%2Fponytail/latest");
    private static final String PACKAGE_NAME = "@dietrichgebert/ponytail";

    private final PonytailBundleService bundles;
    private final ObjectMapper mapper;
    private final PackageClient client;
    private volatile Instant lastCheckedAt;
    private volatile String lastError;

    public PonytailRefreshService(PonytailBundleService bundles, ObjectMapper mapper)
    {
        this(bundles, mapper, new HttpPackageClient());
    }

    PonytailRefreshService(PonytailBundleService bundles, ObjectMapper mapper, PackageClient client)
    {
        this.bundles = requireNonNull(bundles, "bundles is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.client = requireNonNull(client, "client is null");
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT24H")
    public void refreshQuietly()
    {
        try {
            refresh();
        }
        catch (RuntimeException e) {
            lastError = e.getMessage();
            log.debug("Ponytail refresh skipped: {}", e.getMessage());
        }
    }

    public Status status()
    {
        PonytailBundleService.Status bundle = bundles.status();
        return new Status(
                bundle.bundledVersion(),
                bundle.activeVersion(),
                bundle.activeSource(),
                bundle.cacheRoot(),
                lastCheckedAt,
                lastError);
    }

    public Status refresh()
    {
        lastCheckedAt = Instant.now();
        try {
            JsonNode latest = mapper.readTree(client.get(LATEST_URI));
            String name = latest.path("name").asText("");
            String version = latest.path("version").asText("");
            String license = latest.path("license").asText("");
            String tarball = latest.path("dist").path("tarball").asText("");
            String integrity = latest.path("dist").path("integrity").asText("");
            if (!PACKAGE_NAME.equals(name) || version.isBlank() || tarball.isBlank()) {
                throw new IllegalStateException("unexpected Ponytail package metadata");
            }
            if (!"MIT".equals(license)) {
                throw new IllegalStateException("unexpected Ponytail license: " + license);
            }
            byte[] archive = client.getBytes(URI.create(tarball));
            verifyIntegrity(archive, integrity);
            bundles.installCache(toPackage(version, integrity, archive));
            lastError = null;
            return status();
        }
        catch (IOException e) {
            throw new IllegalStateException("Ponytail refresh failed: " + e.getMessage(), e);
        }
    }

    private PonytailBundleService.DownloadedPackage toPackage(String version, String integrity, byte[] archive)
            throws IOException
    {
        Map<String, byte[]> files = tarGzFiles(archive);
        JsonNode packageJson = mapper.readTree(requiredText(files, "package/package.json"));
        if (!PACKAGE_NAME.equals(packageJson.path("name").asText())) {
            throw new IllegalStateException("downloaded package is not Ponytail");
        }
        if (!"MIT".equals(packageJson.path("license").asText())) {
            throw new IllegalStateException("downloaded Ponytail package is not MIT licensed");
        }
        return new PonytailBundleService.DownloadedPackage(
                version,
                integrity,
                requiredText(files, "package/skills/ponytail/SKILL.md"),
                requiredText(files, "package/skills/ponytail-review/SKILL.md"),
                requiredText(files, "package/LICENSE"));
    }

    private static String requiredText(Map<String, byte[]> files, String name)
    {
        byte[] data = files.get(name);
        if (data == null || data.length == 0) {
            throw new IllegalStateException("Ponytail package missing " + name);
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static void verifyIntegrity(byte[] data, String integrity)
    {
        if (integrity == null || integrity.isBlank()) {
            return;
        }
        if (!integrity.startsWith("sha512-")) {
            throw new IllegalStateException("unsupported Ponytail package integrity");
        }
        try {
            byte[] expected = Base64.getDecoder().decode(integrity.substring("sha512-".length()));
            byte[] actual = MessageDigest.getInstance("SHA-512").digest(data);
            if (!Arrays.equals(expected, actual)) {
                throw new IllegalStateException("Ponytail package integrity mismatch");
            }
        }
        catch (IllegalStateException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IllegalStateException("could not verify Ponytail package integrity", e);
        }
    }

    private static Map<String, byte[]> tarGzFiles(byte[] archive)
            throws IOException
    {
        Map<String, byte[]> out = new HashMap<>();
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(archive))) {
            byte[] header = new byte[512];
            while (readAll(gzip, header, 0, header.length) == header.length) {
                if (isZeroBlock(header)) {
                    break;
                }
                String name = tarString(header, 0, 100);
                String prefix = tarString(header, 345, 155);
                if (!prefix.isBlank()) {
                    name = prefix + "/" + name;
                }
                long size = tarOctal(header, 124, 12);
                byte type = header[156];
                if (type == 0 || type == '0') {
                    byte[] content = gzip.readNBytes(toIntExact(size));
                    if (isAllowedFile(name)) {
                        out.put(name, content);
                    }
                }
                else {
                    skipFully(gzip, size);
                }
                long padding = (512 - (size % 512)) % 512;
                skipFully(gzip, padding);
            }
        }
        return out;
    }

    private static boolean isAllowedFile(String name)
    {
        return "package/package.json".equals(name)
                || "package/LICENSE".equals(name)
                || "package/skills/ponytail/SKILL.md".equals(name)
                || "package/skills/ponytail-review/SKILL.md".equals(name);
    }

    private static int readAll(GZIPInputStream in, byte[] buffer, int offset, int len)
            throws IOException
    {
        int total = 0;
        while (total < len) {
            int read = in.read(buffer, offset + total, len - total);
            if (read < 0) {
                return total;
            }
            total += read;
        }
        return total;
    }

    private static void skipFully(GZIPInputStream in, long n)
            throws IOException
    {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    return;
                }
                skipped = 1;
            }
            n -= skipped;
        }
    }

    private static boolean isZeroBlock(byte[] block)
    {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String tarString(byte[] header, int offset, int len)
    {
        int end = offset;
        int max = offset + len;
        while (end < max && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long tarOctal(byte[] header, int offset, int len)
    {
        long value = 0;
        int end = offset + len;
        for (int i = offset; i < end; i++) {
            byte b = header[i];
            if (b == 0 || b == ' ') {
                continue;
            }
            value = (value << 3) + (b - '0');
        }
        return value;
    }

    interface PackageClient
    {
        String get(URI uri)
                throws IOException;

        byte[] getBytes(URI uri)
                throws IOException;
    }

    private static final class HttpPackageClient
            implements PackageClient
    {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public String get(URI uri)
                throws IOException
        {
            return new String(getBytes(uri), StandardCharsets.UTF_8);
        }

        @Override
        public byte[] getBytes(URI uri)
                throws IOException
        {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "bytequay-app")
                    .GET()
                    .build();
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() / 100 != 2) {
                    throw new IOException("HTTP " + response.statusCode() + " from " + uri);
                }
                return response.body();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted fetching " + uri, e);
            }
        }
    }

    public record Status(
            String bundledVersion,
            String activeVersion,
            String activeSource,
            String cacheRoot,
            Instant lastCheckedAt,
            String lastError)
    {
    }
}
