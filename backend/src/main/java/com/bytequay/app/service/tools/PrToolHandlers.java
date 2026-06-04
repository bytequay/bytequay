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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.pr.filters.PullRequestFilters;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * PR-side AUTO tool handlers. Lives next to its siblings under
 * {@code service/tools/} so the registry's bean scan finds the
 * {@code @AgentTool} methods at startup the same way it finds the
 * read-only thread / task / repo handlers in {@link AgentToolHandlers}.
 *
 * <p>The first tool is {@code list_prs}: filter the full PR cache by
 * a named filter and return the matching rows as JSON. The filter
 * names come from
 * {@link com.bytequay.app.service.pr.filters.NamedFilter}-implementing
 * beans — each carries a {@code @Concept(kind=FILTER)} so the agent
 * reads the meaning of the value at the param's site via
 * {@link ToolParam#enumFromConcepts()}.
 */
@Component
public class PrToolHandlers
{
    /** Hard upper bound on {@code list_prs.limit}. Keeps the response
     *  small enough for the model's context budget even when the
     *  underlying cache is large. */
    private static final int LIST_PRS_MAX_LIMIT = 50;

    /** Default for {@code list_prs.limit} when the caller leaves it
     *  null — small enough to fit comfortably inside one tool result
     *  while still being useful. */
    private static final int LIST_PRS_DEFAULT_LIMIT = 20;

    private final PullRequestService pullRequestService;
    private final PullRequestFilters filters;
    private final ObjectMapper mapper;
    /** Pinned at construction to {@link Clock#systemUTC()} so the
     *  filter sees a single instant for one call. A test-only setter
     *  could be added if a future test needs a fixed wall-clock; the
     *  pure filter unit tests already pin {@code now} on the call
     *  itself so this seam isn't load-bearing yet. */
    private final Clock clock;

    public PrToolHandlers(
            PullRequestService pullRequestService,
            PullRequestFilters filters,
            ObjectMapper mapper)
    {
        this.pullRequestService = requireNonNull(pullRequestService, "pullRequestService is null");
        this.filters = requireNonNull(filters, "filters is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = Clock.systemUTC();
    }

    /** Args record for {@code list_prs}. */
    public record ListPrsArgs(
            @ToolParam(
                    description = "Named filter to apply. Each value resolves to a "
                            + "@Concept(kind=FILTER) in the concept registry — call "
                            + "lookup_term to see the full definition.",
                    required = true,
                    enumFromConcepts = {"awaiting_me", "urgent", "stale", "blocked", "mine_open"})
            String filter,
            @ToolParam(description = "Optional cap on the number of PRs returned (1–50).")
            Integer limit) {}

    @AgentTool(
            name = "list_prs",
            description = "Lists pull requests from the local cache filtered by a "
                    + "named filter (urgent, awaiting_me, …). Use this instead of "
                    + "guessing what 'urgent' means — the filter resolves to a "
                    + "concrete @Concept on the backend.",
            security = SecurityType.VCS_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome listPrs(ListPrsArgs args, ToolCall call)
    {
        if (args.filter() == null || args.filter().isBlank()) {
            return ToolOutcome.Completed.error("filter is required");
        }
        int limit = args.limit() == null ? LIST_PRS_DEFAULT_LIMIT
                : Math.max(1, Math.min(LIST_PRS_MAX_LIMIT, args.limit()));
        List<PullRequest> all = pullRequestService.listPullRequests();
        List<PullRequest> matched;
        try {
            matched = filters.apply(args.filter(), all, Instant.now(clock));
        }
        catch (IllegalArgumentException e) {
            return ToolOutcome.Completed.error(e.getMessage());
        }
        List<PullRequest> capped = matched.size() <= limit
                ? matched
                : matched.subList(0, limit);
        try {
            return ToolOutcome.Completed.ok(mapper.writeValueAsString(capped));
        }
        catch (JsonProcessingException e) {
            return ToolOutcome.Completed.error("failed to serialise PRs: " + e.getMessage());
        }
    }
}
