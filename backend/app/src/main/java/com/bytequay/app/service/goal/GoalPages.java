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
package com.bytequay.app.service.goal;

import com.google.common.base.Throwables;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Opens the pages a research card names before the card floats. A page that does not exist is one the agent remembered wrongly, and the run
 * should not be sent to read it.
 */
@Component
public class GoalPages
{
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client;

    public GoalPages()
    {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(TIMEOUT).build());
    }

    GoalPages(HttpClient client)
    {
        this.client = requireNonNull(client, "client is null");
    }

    /**
     * The pages that do not exist: not found, gone, malformed or on a host that does not resolve, all asked at once. A page that refuses the
     * request, times out or fails otherwise is kept, since it may well exist.
     */
    public List<String> missing(List<String> pages)
    {
        List<CompletableFuture<Boolean>> checks = pages.stream().map(this::missing).collect(toImmutableList());
        return IntStream.range(0, pages.size()).filter(index -> checks.get(index).join()).mapToObj(pages::get).collect(toImmutableList());
    }

    private CompletableFuture<Boolean> missing(String page)
    {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(page)).method("HEAD", HttpRequest.BodyPublishers.noBody()).timeout(TIMEOUT).header("User-Agent", "ByteQuay")
                    .build();
        }
        catch (IllegalArgumentException malformed) {
            return CompletableFuture.completedFuture(true);
        }
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> response.statusCode() == 404 || response.statusCode() == 410)
                .exceptionally(GoalPages::unresolved);
    }

    private static boolean unresolved(Throwable failure)
    {
        return Throwables.getCausalChain(failure).stream().anyMatch(cause -> cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException);
    }
}
