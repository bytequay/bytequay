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

import com.bytequay.core.domain.PrMergeQueueRemoval;
import com.bytequay.core.domain.PrMergeQueueRemoval.FailedCheck;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.repository.github.GitHubApiSupport.graphql;
import static java.util.Objects.requireNonNull;

/** Optional enrichment: REST timeline events remain usable when GraphQL details are unavailable. */
final class GitHubMergeQueueTimeline
{
    private static final String QUERY = """
            query($ids:[ID!]!,$after:String) {
              nodes(ids:$ids) { ... on RemovedFromMergeQueueEvent {
                id reason beforeCommit { url statusCheckRollup { contexts(first:100,after:$after) {
                  nodes {
                    ... on CheckRun { name conclusion detailsUrl }
                    ... on StatusContext { context state targetUrl }
                  }
                  pageInfo { hasNextPage endCursor }
                } } }
              } }
            }
            """;
    private static final Set<String> FAILED_CONCLUSIONS = ImmutableSet.of("FAILURE", "TIMED_OUT", "CANCELLED", "ACTION_REQUIRED", "STARTUP_FAILURE", "STALE");
    private final RestClient client;

    GitHubMergeQueueTimeline(RestClient client)
    {
        this.client = requireNonNull(client, "client is null");
    }

    Map<String, PrMergeQueueRemoval> read(String pat, List<String> ids)
    {
        Map<String, PrMergeQueueRemoval> result = new LinkedHashMap<>();
        for (List<String> batch : Lists.partition(ids, 50)) {
            for (JsonNode event : fetch(pat, batch, Optional.empty())) {
                text(event, "id").filter(batch::contains).ifPresent(id -> result.put(id, removal(pat, id, event)));
            }
        }
        return ImmutableMap.copyOf(result);
    }

    private PrMergeQueueRemoval removal(String pat, String id, JsonNode event)
    {
        Optional<String> reason = text(event, "reason");
        Optional<String> commitUrl = text(event.path("beforeCommit"), "url");
        List<FailedCheck> checks = new ArrayList<>();
        JsonNode page = event;
        Optional<String> cursor = Optional.empty();
        for (int index = 0; index < 10; index++) {
            // A missing later page must not erase the reason or links already retrieved.
            if (!commitUrl.equals(text(page.path("beforeCommit"), "url"))) {
                break;
            }
            JsonNode contexts = page.path("beforeCommit").path("statusCheckRollup").path("contexts");
            for (JsonNode check : contexts.path("nodes")) {
                boolean checkRun = check.has("name");
                String state = text(check, checkRun ? "conclusion" : "state").orElse("");
                if (checkRun ? FAILED_CONCLUSIONS.contains(state) : state.equals("FAILURE") || state.equals("ERROR")) {
                    text(check, checkRun ? "name" : "context").ifPresent(name ->
                            checks.add(new FailedCheck(name, text(check, checkRun ? "detailsUrl" : "targetUrl"))));
                }
            }
            JsonNode pageInfo = contexts.path("pageInfo");
            Optional<String> next = text(pageInfo, "endCursor");
            if (!pageInfo.path("hasNextPage").asBoolean() || next.isEmpty() || next.equals(cursor)) {
                break;
            }
            cursor = next;
            page = fetch(pat, ImmutableList.of(id), cursor).path(0);
        }
        return new PrMergeQueueRemoval(reason, commitUrl, checks.stream().distinct().toList());
    }

    private JsonNode fetch(String pat, List<String> ids, Optional<String> cursor)
    {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("ids", ids);
        cursor.ifPresent(value -> variables.put("after", value));
        try {
            JsonNode response = graphql(client, pat, QUERY, variables).body(JsonNode.class);
            return response == null ? MissingNode.getInstance() : response.path("data").path("nodes");
        }
        catch (RestClientException ignored) {
            return MissingNode.getInstance();
        }
    }

    private static Optional<String> text(JsonNode node, String field)
    {
        return Optional.ofNullable(node.path(field).textValue()).filter(value -> !value.isBlank());
    }
}
