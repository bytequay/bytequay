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
package com.bytequay.app.web;

import com.bytequay.app.domain.MyPrColumn;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.Team;
import com.bytequay.app.domain.TeamSummary;
import com.bytequay.app.service.teams.TeamService;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/teams")
public class TeamController
{
    private final TeamService teamService;

    public TeamController(TeamService teamService)
    {
        this.teamService = requireNonNull(teamService, "teamService is null");
    }

    public record CreateTeamRequest(String name, String avatar, String color, String description, List<String> members) {}

    public record UpdateTeamRequest(String name, String avatar, String color, String description) {}

    public record ReplaceMembersRequest(List<String> members) {}

    /** GET /api/teams — list all teams as lightweight summaries. */
    @GetMapping
    public List<TeamSummary> list()
    {
        return teamService.listSummaries();
    }

    /** POST /api/teams — create a new team. */
    @PostMapping
    public Team create(@RequestBody CreateTeamRequest req)
    {
        if (req == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "body is required");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "name is required");
        }
        return teamService.create(req.name(), req.avatar(), req.color(), req.description(), toMemberSet(req.members()));
    }

    /** GET /api/teams/{id} — full team incl. roster. */
    @GetMapping("/{id}")
    public Team get(@PathVariable long id)
    {
        return teamService.get(id);
    }

    /** PATCH /api/teams/{id} — rename / re-colour an existing team. */
    @PatchMapping("/{id}")
    public Team update(@PathVariable long id, @RequestBody UpdateTeamRequest req)
    {
        if (req == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "body is required");
        }
        return teamService.update(id, req.name(), req.avatar(), req.color(), req.description());
    }

    /** PUT /api/teams/{id}/members — replace the entire roster. */
    @PutMapping("/{id}/members")
    public Team replaceMembers(@PathVariable long id, @RequestBody ReplaceMembersRequest req)
    {
        if (req == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "body is required");
        }
        return teamService.replaceMembers(id, toMemberSet(req.members()));
    }

    /** DELETE /api/teams/{id} — delete a team and its members. */
    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable long id)
    {
        teamService.delete(id);
        return ImmutableMap.of("result", "deleted");
    }

    /** GET /api/teams/{id}/pulls — open PRs in watched repos authored by any team member. */
    @GetMapping("/{id}/pulls")
    public List<PullRequest> pulls(@PathVariable long id)
    {
        return teamService.listPullRequestsForTeam(id);
    }

    /**
     * GET /api/teams/{id}/pulls/by-column?perColumn=N&force=true|false
     *
     * <p>Returns the first N items per kanban column plus the total per
     * column (so the frontend can render header counts and "+ N more"
     * affordances). The team kanban initial-paint endpoint — replaces
     * the old fetch-everything-then-categorize-on-frontend pattern.
     * {@code force=true} bypasses the per-team TTL cache.
     */
    @GetMapping("/{id}/pulls/by-column")
    public TeamService.TeamColumnsResponse pullsByColumn(
            @PathVariable long id,
            @RequestParam(value = "perColumn", defaultValue = "5") int perColumn,
            @RequestParam(value = "force", defaultValue = "false") boolean force)
    {
        return teamService.listPullRequestsForTeamByColumn(id, perColumn, force);
    }

    public record MergedRecentlyResponse(int count, int days) {}

    /**
     * GET /api/teams/{id}/merged-recently?days=7
     *
     * <p>Total number of PRs authored by team members in the user's
     * watched repos that merged within the last {@code days} days.
     * Powers the "Merged this week" stat on the team home page.
     * Computed via a dedicated {@code is:merged} GitHub-search fan-out;
     * the team kanban's open-only data path can't surface this number.
     * Frontend caches the response with a 10-minute TTL.
     */
    @GetMapping("/{id}/merged-recently")
    public MergedRecentlyResponse mergedRecently(
            @PathVariable long id,
            @RequestParam(value = "days", defaultValue = "7") int days)
    {
        int sanitized = Math.max(1, Math.min(90, days));
        int count = teamService.countMergedRecently(id, sanitized);
        return new MergedRecentlyResponse(count, sanitized);
    }

    /**
     * GET /api/teams/{id}/pulls/column?column=...&offset=N&limit=M
     *
     * <p>Returns one page (offset + limit) of the named column. Backed
     * by the TTL cache populated by /pulls/by-column, so this is an
     * O(1) slice — no GitHub round-trip on every "+ N more" click.
     * Cache expiration triggers a one-time fan-out as a side effect.
     */
    @GetMapping("/{id}/pulls/column")
    public TeamService.ColumnPage pullsColumnPage(
            @PathVariable long id,
            // String + manual fromSlug because Spring's default
            // StringToEnumConverter calls Enum.valueOf which expects
            // the constant name (WAITING_ON_REVIEW), not the lowercase
            // slug we use on the wire (waiting_on_review). The
            // @JsonCreator on the enum only kicks in for JSON body
            // deserialization, not query-string binding.
            @RequestParam("column") String columnSlug,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "10") int limit)
    {
        MyPrColumn column = MyPrColumn.fromSlug(columnSlug);
        return teamService.listPullRequestsForTeamColumnPage(id, column, offset, limit);
    }

    /**
     * JSON arrives as a {@link List} (Jackson default), but the service layer
     * works in {@link Set}s — convert here so duplicates and null collapse
     * before crossing the boundary.
     */
    private static Set<String> toMemberSet(List<String> members)
    {
        if (members == null || members.isEmpty()) {
            return ImmutableSet.of();
        }
        return ImmutableSet.copyOf(members);
    }
}
