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
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static java.util.Objects.requireNonNull;

/**
 * Fetches concatenated pages from GitHub list endpoints. Callers keep the
 * endpoint-specific mapping; this class owns the common page walk.
 */
final class GitHubPaginator
{
    /** Hard cap on pages so a runaway request cannot tie up the executor. */
    private static final int MAX_PAGES = 10;
    private static final int PAGE_SIZE = 100;

    private final RestClient gitHubRestClient;

    GitHubPaginator(RestClient gitHubRestClient)
    {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
    }

    /**
     * Fetches every page of a paginated GitHub list endpoint, concatenating
     * the results in order. Stops as soon as a response has fewer than
     * {@code per_page=100} rows or after {@link #MAX_PAGES} pages.
     *
     * <p>Errors are swallowed so callers can fall back to a partial result
     * rather than losing already-fetched timeline/comment rows.
     */
    <T> List<T> paginate(
            String pat,
            String pathTemplate,
            ParameterizedTypeReference<List<T>> typeRef,
            Object... uriVariables)
    {
        return paginateSince(pat, pathTemplate, typeRef, null, uriVariables);
    }

    /**
     * Fetches every page from endpoints that support GitHub's {@code since}
     * query parameter.
     */
    <T> List<T> paginateSince(
            String pat,
            String pathTemplate,
            ParameterizedTypeReference<List<T>> typeRef,
            Instant since,
            Object... uriVariables)
    {
        // Defensive: guard against the edge case where GitHub returns the
        // same id on adjacent pages while comments/events are created during
        // the walk. Downstream saves can then rely on one row per identity.
        Set<T> seen = new LinkedHashSet<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<T> rows = fetchPage(pat, pathTemplate, typeRef, since, page, uriVariables);
            if (rows.isEmpty()) {
                break;
            }

            int added = addRows(seen, rows);
            if (shouldStop(rows, added)) {
                break;
            }
        }
        return ImmutableList.copyOf(seen);
    }

    private <T> List<T> fetchPage(
            String pat,
            String pathTemplate,
            ParameterizedTypeReference<List<T>> typeRef,
            Instant since,
            int page,
            Object... uriVariables)
    {
        try {
            List<T> rows = gitHubRestClient.get()
                    .uri(u -> buildPageUri(u, pathTemplate, since, page, uriVariables))
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(typeRef);
            return rows == null ? ImmutableList.of() : rows;
        }
        catch (RestClientResponseException e) {
            return ImmutableList.of();
        }
    }

    private static URI buildPageUri(
            UriBuilder uriBuilder,
            String pathTemplate,
            Instant since,
            int page,
            Object... uriVariables)
    {
        UriBuilder builder = uriBuilder.path(pathTemplate)
                .queryParam("per_page", PAGE_SIZE)
                .queryParam("page", page);
        if (since != null) {
            builder = builder.queryParam("since", since.toString());
        }
        return builder.build(uriVariables);
    }

    private static <T> int addRows(Set<T> seen, List<T> rows)
    {
        int beforeAdd = seen.size();
        seen.addAll(rows);
        return seen.size() - beforeAdd;
    }

    private static boolean shouldStop(List<?> rows, int added)
    {
        return rows.size() < PAGE_SIZE || added == 0;
    }
}
