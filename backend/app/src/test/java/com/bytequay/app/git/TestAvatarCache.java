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
package com.bytequay.app.git;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAvatarCache
{
    private static final String URL = "https://avatars.example.invalid/example.png";

    @TempDir
    private Path root;

    @Test
    void downloadsOnceAndReloadsFromTheLocalFile()
            throws Exception
    {
        HttpClient client = mock(HttpClient.class);
        response(client, 200, "image/png", new byte[] {1, 2, 3});
        AvatarCache cache = new AvatarCache(root, client);

        String key = cache.cache(URL).orElseThrow();

        assertThat(cache.cache(URL)).contains(key);
        assertThat(cache.find(key)).isPresent();
        assertThat(Files.readAllBytes(cache.find(key).orElseThrow().path())).containsExactly(1, 2, 3);
        verify(client, times(1)).send(any(), any());

        HttpClient offline = mock(HttpClient.class);
        AvatarCache reloaded = new AvatarCache(root, offline);
        assertThat(reloaded.find(key)).isPresent();
        verify(offline, never()).send(any(), any());
    }

    @Test
    void leavesNoFileAfterAFailedDownload()
            throws Exception
    {
        HttpClient client = mock(HttpClient.class);
        response(client, 500, "image/png", new byte[] {1, 2, 3});

        assertThat(new AvatarCache(root, client).cache(URL)).isEmpty();
        try (var files = Files.list(root)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void neverPublishesAnOversizedPartialFile()
            throws Exception
    {
        HttpClient client = mock(HttpClient.class);
        response(client, 200, "image/png", new byte[(5 * 1024 * 1024) + 1]);

        assertThat(new AvatarCache(root, client).cache(URL)).isEmpty();
        try (var files = Files.list(root)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void rejectsMalformedKeysAndUnsafeUrls()
    {
        AvatarCache cache = new AvatarCache(root, mock(HttpClient.class));

        assertThatThrownBy(() -> cache.find("../outside"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.cache("http://avatars.example.invalid/example.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private static void response(HttpClient client, int status, String contentType, byte[] body)
            throws Exception
    {
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(
                ImmutableMap.of("Content-Type", ImmutableList.of(contentType)), (name, value) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(body));
        doReturn(response).when(client).send(any(), any(HttpResponse.BodyHandler.class));
    }
}
