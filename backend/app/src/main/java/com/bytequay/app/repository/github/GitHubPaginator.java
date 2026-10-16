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
package com.bytequay.app.repository.github;

import com.google.common.collect.ImmutableList;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import static com.bytequay.app.repository.github.GitHubApiSupport.get;
import static com.bytequay.app.repository.github.GitHubApiSupport.requireBody;
import static com.bytequay.app.repository.github.GitHubApiSupport.translating;
import static java.util.Objects.requireNonNull;

/**
 * Fetches concatenated pages from GitHub list endpoints. Callers keep the
 * endpoint-specific mapping; this class owns the common page walk.
 */
final class GitHubPaginator
{
    /** Hard cap on pages so a runaway request cannot tie up the executor. */
    private static final int MAX_PAGES = 50;
    static final int PAGE_SIZE = 100;

    private final RestClient gitHubRestClient;

    GitHubPaginator(RestClient gitHubRestClient)
    {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
    }

    /**
     * Walks every page and sends the caller's ETag with page one. GitHub
     * answers 304 when nothing changed, which costs no rate limit and lets the
     * caller keep what it already stored.
     *
     * <p>The ETag is only ever taken from page one, so it can only vouch for
     * page one. Callers must withhold it when their stored copy spans more than
     * a page, or a change past page one is never seen.</p>
     */
    <T> ConditionalList<T> paginateConditional(String pat, String pathTemplate, ParameterizedTypeReference<List<T>> typeRef, Optional<String> etag, Object... uriVariables)
    {
        return paginateConditional(MAX_PAGES, pat, pathTemplate, typeRef, etag, rows -> rows, rows -> Integer.MAX_VALUE, uriVariables);
    }

    <R, T> ConditionalList<T> paginateConditional(int maxPages, String pat, String pathTemplate, ParameterizedTypeReference<R> typeRef,
            Optional<String> etag, Function<R, List<T>> items, ToIntFunction<R> totalCount, Object... uriVariables)
    {
        requireNonNull(etag, "etag is null");
        // GitHub can return the same row on adjacent pages while rows are created during the walk;
        // the set keeps downstream saves at one row per identity.
        Set<T> seen = new LinkedHashSet<>();
        Optional<String> responseEtag = etag;
        int collected = 0;
        for (int page = 1; page <= maxPages; page++) {
            int currentPage = page;
            RestClient.RequestHeadersSpec<?> request = get(gitHubRestClient, pat, u -> u.path(pathTemplate)
                    .queryParam("per_page", PAGE_SIZE)
                    .queryParam("page", currentPage)
                    .build(uriVariables));
            if (currentPage == 1) {
                etag.ifPresent(value -> request.header(HttpHeaders.IF_NONE_MATCH, value));
            }
            ResponseEntity<R> entity = translating(() -> request.retrieve().toEntity(typeRef));
            if (currentPage == 1) {
                responseEtag = Optional.ofNullable(entity.getHeaders().getETag()).or(() -> etag);
            }
            if (entity.getStatusCode() == HttpStatus.NOT_MODIFIED) {
                if (currentPage != 1 || etag.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unexpected not-modified response without a conditional first GitHub page");
                }
                return new ConditionalList<>(Optional.empty(), responseEtag);
            }
            R response = requireBody(entity.getBody(), "Empty response from GitHub list endpoint");
            List<T> rows = requireBody(items.apply(response), "Missing rows from GitHub list endpoint");
            collected += rows.size();
            if (rows.isEmpty()) {
                break;
            }
            int size = seen.size();
            seen.addAll(rows);
            if (collected >= totalCount.applyAsInt(response) || rows.size() < PAGE_SIZE || seen.size() == size) {
                break;
            }
        }
        return new ConditionalList<>(Optional.of(ImmutableList.copyOf(seen)), responseEtag);
    }

    <T> List<T> paginate(String pat, String pathTemplate, ParameterizedTypeReference<List<T>> typeRef, Object... uriVariables)
    {
        return paginateConditional(pat, pathTemplate, typeRef, Optional.empty(), uriVariables).values().orElseThrow();
    }

    /** Bounded list reads retain partial results; callers map each page before another request starts. */
    static <T> boolean forEachPage(int maxPages, IntFunction<List<T>> fetch, Consumer<List<T>> accept)
    {
        return forEachPage(maxPages, PAGE_SIZE, fetch, accept);
    }

    static <T> boolean forEachPage(int maxPages, int pageSize, IntFunction<List<T>> fetch, Consumer<List<T>> accept)
    {
        for (int page = 1; page <= maxPages; page++) {
            List<T> rows = fetch.apply(page);
            if (rows == null || rows.isEmpty()) {
                return true;
            }
            accept.accept(rows);
            if (rows.size() < pageSize) {
                return true;
            }
        }
        return false;
    }

    <T> List<T> fetchPage(String pat, String pathTemplate, ParameterizedTypeReference<List<T>> typeRef, int page, Object... uriVariables)
    {
        return translating(() -> {
            List<T> rows = get(gitHubRestClient, pat, u -> u.path(pathTemplate)
                    .queryParam("per_page", PAGE_SIZE)
                    .queryParam("page", page)
                    .build(uriVariables))
                    .retrieve()
                    .body(typeRef);
            return requireBody(rows, "Empty response from GitHub list endpoint");
        });
    }

    static <T> void forEachCompletePage(int maxPages, IntFunction<? extends Page<T>> fetch, Consumer<T> accept, String kind)
    {
        int collected = 0;
        for (int page = 1; page <= maxPages; page++) {
            Page<T> response = fetch.apply(page);
            if (response == null || response.totalCount() == null || response.totalCount() < 0 || response.rows() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned incomplete workflow-" + kind + " evidence");
            }
            response.rows().forEach(accept);
            collected += response.rows().size();
            if (collected == response.totalCount()) {
                return;
            }
            if (response.rows().isEmpty() || collected > response.totalCount()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned incomplete workflow-" + kind + " pages");
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub workflow " + kind + "s exceed the complete inspection limit");
    }

    interface Page<T>
    {
        Integer totalCount();

        List<T> rows();
    }
}
