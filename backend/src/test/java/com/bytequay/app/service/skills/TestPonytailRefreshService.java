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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class TestPonytailRefreshService
{
    @TempDir
    Path tempDir;

    @Test
    void refreshDownloadsAndInstallsVerifiedPackage()
            throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        byte[] archive = tarGz(Map.of(
                "package/package.json", """
                        {"name":"@dietrichgebert/ponytail","version":"9.9.9","license":"MIT"}
                        """,
                "package/LICENSE", "MIT",
                "package/skills/ponytail/SKILL.md", "fresh ponytail",
                "package/skills/ponytail-review/SKILL.md", "fresh review"));
        String integrity = "sha512-" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-512").digest(archive));
        PonytailBundleService bundles = new PonytailBundleService(mapper, tempDir);
        PonytailRefreshService service = new PonytailRefreshService(
                bundles, mapper, new FakeClient(archive, integrity));

        PonytailRefreshService.Status status = service.refresh();

        assertThat(status.activeVersion()).isEqualTo("9.9.9");
        assertThat(status.activeSource()).isEqualTo("cache");
        assertThat(bundles.snapshot().select(List.of("ponytail")))
                .singleElement()
                .satisfies(skill -> assertThat(skill.body()).isEqualTo("fresh ponytail"));
    }

    private record FakeClient(byte[] archive, String integrity)
            implements PonytailRefreshService.PackageClient
    {
        @Override
        public String get(URI uri)
        {
            return """
                    {
                      "name": "@dietrichgebert/ponytail",
                      "version": "9.9.9",
                      "license": "MIT",
                      "dist": {
                        "tarball": "https://example.test/ponytail.tgz",
                        "integrity": "%s"
                      }
                    }
                    """.formatted(integrity);
        }

        @Override
        public byte[] getBytes(URI uri)
        {
            return archive;
        }
    }

    private static byte[] tarGz(Map<String, String> files)
            throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                byte[] body = file.getValue().getBytes(StandardCharsets.UTF_8);
                gzip.write(header(file.getKey(), body.length));
                gzip.write(body);
                int padding = (512 - (body.length % 512)) % 512;
                gzip.write(new byte[padding]);
            }
            gzip.write(new byte[1024]);
        }
        return bytes.toByteArray();
    }

    private static byte[] header(String name, int size)
    {
        byte[] header = new byte[512];
        put(header, 0, 100, name);
        put(header, 100, 8, "0000644");
        put(header, 108, 8, "0000000");
        put(header, 116, 8, "0000000");
        put(header, 124, 12, "%011o".formatted(size));
        put(header, 136, 12, "00000000000");
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        header[156] = '0';
        put(header, 257, 6, "ustar");
        int sum = 0;
        for (byte b : header) {
            sum += b & 0xff;
        }
        put(header, 148, 8, "%06o\0 ".formatted(sum));
        return header;
    }

    private static void put(byte[] target, int offset, int len, String value)
    {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, Math.min(bytes.length, len));
    }
}
