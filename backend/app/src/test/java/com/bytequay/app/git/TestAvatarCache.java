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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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

        assertThat(key).isEqualTo("a36323bc972a4aa7fcb929653533b2585eb5d63b59f5e9a004643340cf247c0b");
        assertThat(cache.cache(URL)).contains(key);
        assertThat(cache.find(key)).isPresent();
        assertThat(Files.readAllBytes(cache.find(key).orElseThrow().path())).containsExactly(1, 2, 3);
        verify(client, times(1)).sendAsync(any(), any());

        HttpClient offline = mock(HttpClient.class);
        AvatarCache reloaded = new AvatarCache(root, offline);
        assertThat(reloaded.find(key)).isPresent();
        verify(offline, never()).sendAsync(any(), any());
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
    @Timeout(10)
    void cancelsADownloadWhoseBodyNeverCompletes()
            throws Exception
    {
        HttpClient client = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> stalledBody = new CompletableFuture<>();
        doReturn(stalledBody).when(client).sendAsync(any(), any(HttpResponse.BodyHandler.class));

        assertThat(new AvatarCache(root, client, Duration.ofMillis(100)).cache(URL)).isEmpty();

        assertThat(stalledBody).isCancelled();
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

    @Test
    void repositoryReadsAreLocalAndSurviveRestart()
            throws Exception
    {
        HttpClient client = mock(HttpClient.class);
        AvatarCache cache = new AvatarCache(root, client);
        assertThat(cache.repository("TrinoDB", false)).isEmpty();
        verify(client, never()).sendAsync(any(), any());

        response(client, 200, "image/png", new byte[] {1, 2, 3});
        Path file = cache.repository("TrinoDB", true).orElseThrow().path();
        assertThat(cache.repository("trinodb", true).orElseThrow().path()).isEqualTo(file);
        verify(client, times(1)).sendAsync(any(), any());

        HttpClient offline = mock(HttpClient.class);
        AvatarCache reloaded = new AvatarCache(root, offline);
        assertThat(reloaded.repository("TRINODB", false).orElseThrow().path()).isEqualTo(file);
        assertThat(reloaded.repository("trinodb", true).orElseThrow().path()).isEqualTo(file);
        verify(offline, never()).sendAsync(any(), any());
    }

    @Test
    void servesThePreviousImageWhileAnExpiredAvatarRefreshes()
            throws Exception
    {
        HttpClient client = mock(HttpClient.class);
        response(client, 200, "image/png", new byte[] {1, 2, 3});
        AvatarCache cache = new AvatarCache(root, client);
        Path file = cache.repository("trinodb", true).orElseThrow().path();
        Files.setLastModifiedTime(file, FileTime.from(Instant.EPOCH));

        HttpClient refreshing = mock(HttpClient.class);
        response(refreshing, 200, "image/png", new byte[] {4, 5, 6});
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
            return refreshing.sendAsync(invocation.getArgument(0), invocation.getArgument(1));
        }).when(client).sendAsync(any(), any());

        CompletableFuture<?> refresh = CompletableFuture.supplyAsync(() -> cache.repository("trinodb", true));
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(Files.readAllBytes(cache.repository("trinodb", false).orElseThrow().path())).containsExactly(1, 2, 3);
            assertThat(refresh).isNotDone();
        }
        finally {
            finish.countDown();
        }
        refresh.get(10, TimeUnit.SECONDS);
        assertThat(Files.readAllBytes(cache.repository("trinodb", false).orElseThrow().path())).containsExactly(4, 5, 6);
    }

    @Test
    void preservesAnExpiredImageWhenRefreshFailsAndRetriesLater()
            throws Exception
    {
        HttpClient client = mock(HttpClient.class);
        response(client, 200, "image/png", new byte[] {1, 2, 3});
        AvatarCache cache = new AvatarCache(root, client);
        Path file = cache.repository("trinodb", true).orElseThrow().path();
        Files.setLastModifiedTime(file, FileTime.from(Instant.EPOCH));

        response(client, 500, "text/html", new byte[] {9});
        assertThat(Files.readAllBytes(cache.repository("trinodb", true).orElseThrow().path())).containsExactly(1, 2, 3);
        response(client, 200, "image/jpeg", new byte[] {4, 5, 6});
        AvatarCache.CachedAvatar refreshed = cache.repository("trinodb", true).orElseThrow();
        assertThat(refreshed.mediaType().toString()).isEqualTo("image/jpeg");
        assertThat(Files.readAllBytes(refreshed.path())).containsExactly(4, 5, 6);
        assertThat(file).doesNotExist();
    }

    @Test
    void restrictsRepositoryDownloadsToGitHubOwners()
    {
        AvatarCache cache = new AvatarCache(root, mock(HttpClient.class));
        for (String owner : ImmutableList.of("", "../tokens", "alice/repo", "alice?size=2000", "https://example.com", "a".repeat(40))) {
            assertThatThrownBy(() -> cache.repository(owner, true)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @SuppressWarnings("unchecked")
    private static void response(HttpClient client, int status, String contentType, byte[] body)
            throws Exception
    {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(ImmutableMap.of("Content-Type", ImmutableList.of(contentType)), (name, value) -> true));
        when(response.body()).thenReturn(body);
        doReturn(CompletableFuture.completedFuture(response)).when(client).sendAsync(any(), any(HttpResponse.BodyHandler.class));
    }
}
