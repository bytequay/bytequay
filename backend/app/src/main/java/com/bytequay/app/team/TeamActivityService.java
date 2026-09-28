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
package com.bytequay.app.team;

import com.bytequay.app.repository.github.GitHubTeamActivityClient;
import com.bytequay.app.repository.github.GitHubTeamActivityClient.Identity;
import com.bytequay.app.repository.github.GitHubTeamActivityClient.Observation;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.TeamActivityEvent;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.TeamActivityStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import static com.bytequay.app.repository.github.GitHubTeamActivityClient.mentions;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

@Service
public class TeamActivityService
{
    private static final int RETENTION_DAYS = 90;
    private static final Set<String> FILTERS = ImmutableSet.of("all", "merged", "reviews", "issues", "interactions", "authored-merges", "reviews-performed", "issues-opened");
    private static final String COVERAGE = "Observed activity only: public member events have GitHub's 300-event/30-day ceiling. "
            + "Each pass rotates across up to 4 members and 2 watched repositories; repository sources inspect the newest 100 updated items/comments "
            + "and up to 5 PR review pages plus first/last timeline pages. "
            + "Private activity outside accessible watched repositories, missed pages and periods while ByteQuay is closed are absent. "
            + "History is retained for 90 days. Direct interactions are explicit mentions, person-to-person review requests "
            + "or reviews of each other's PRs; shared work is counted separately. "
            + "Closure counts require an identified actor. Zero means no recorded observation, not no work.";

    private final TeamService teams;
    private final CredentialService credentials;
    private final WorkspaceStore workspaces;
    private final GitHubTeamActivityClient github;
    private final TeamActivityStore store;
    private final AppSettingsStore settings;
    private final ObjectMapper mapper;
    private final PullRequestStore pulls;
    private final PrDetailStore details;
    private final Lock refreshLock = new ReentrantLock();

    public TeamActivityService(TeamService teams, CredentialService credentials, WorkspaceStore workspaces, GitHubTeamActivityClient github,
            TeamActivityStore store, AppSettingsStore settings, ObjectMapper mapper, PullRequestStore pulls, PrDetailStore details)
    {
        this.teams = requireNonNull(teams, "teams is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.github = requireNonNull(github, "github is null");
        this.store = requireNonNull(store, "store is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.pulls = requireNonNull(pulls, "pulls is null");
        this.details = requireNonNull(details, "details is null");
    }

    public TeamActivitySnapshot snapshot(Optional<String> circleId, Optional<Long> memberId, int days, int limit, String filter)
    {
        validate(days, limit, filter);
        GitHubCredential credential = credentials.resolveGitHubCredential();
        TeamDirectory directory = teams.directory();
        long accountId = teams.accountId();
        requireSameAccount(credential);
        List<TeamCircle> circles = circles(directory, circleId);
        List<TeamMember> members = members(circles).stream().filter(member -> memberId.isEmpty() || member.githubId() == memberId.get()).collect(toImmutableList());
        if (memberId.isPresent() && members.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Team member not found in this circle");
        }
        Set<Long> memberIds = members.stream().map(TeamMember::githubId).collect(toSet());
        List<TeamActivityEvent> observations = store.findSince(accountId, Instant.now().minusSeconds(days * 86400L)).stream()
                .filter(event -> circles.stream().anyMatch(circle -> includes(circle, event)))
                .filter(event -> memberIds.contains(event.actorId()) || event.subjectAuthorId().filter(memberIds::contains).isPresent()
                        || event.viewerInteractionMemberIds().stream().anyMatch(memberIds::contains) || event.actorId() == accountId)
                .sorted(Comparator.comparing(TeamActivityEvent::occurredAt).reversed().thenComparing(TeamActivityEvent::id)).collect(toImmutableList());
        List<TeamActivityEvent> display = observations.stream().filter(event -> memberIds.contains(event.actorId())
                || event.subjectAuthorId().filter(memberIds::contains).isPresent() || event.viewerInteractionMemberIds().stream().anyMatch(memberIds::contains))
                .filter(event -> circles.stream().anyMatch(circle -> includes(circle, event) && circle.members().stream()
                        .anyMatch(member -> memberIds.contains(member.githubId()) && involves(event, member.githubId()))))
                .filter(event -> matchesFilter(event, filter, circles.stream().filter(circle -> includes(circle, event))
                        .flatMap(circle -> circle.members().stream()).map(TeamMember::githubId).filter(memberIds::contains).collect(toSet())))
                .collect(toImmutableList());
        CollectionState state = state(accountId);
        List<String> repositories = repositories(circles);
        TeamActivitySnapshot.Coverage coverage = new TeamActivitySnapshot.Coverage(true, COVERAGE, state.observedSince(), state.lastRefreshedAt(), state.errors(),
                members.size(), (int) state.membersScanned().stream().filter(memberIds::contains).count(), repositories.size(),
                (int) state.repositoriesScanned().stream().filter(repositories::contains).count(), display.size() > limit);
        requireSameAccount(credential);
        List<TeamActivitySnapshot.PersonStatistics> people = members.stream().map(member -> statistics(ImmutableList.of(member), observations.stream()
                .filter(event -> circles.stream().anyMatch(circle -> includes(circle, event)
                        && circle.members().stream().anyMatch(candidate -> candidate.githubId() == member.githubId())))
                .collect(toImmutableList()), accountId).getFirst()).collect(toImmutableList());
        return new TeamActivitySnapshot(directory.accountLogin(), display.stream().limit(limit).collect(toImmutableList()), people, coverage);
    }

    public TeamActivitySnapshot refresh(Optional<String> circleId, Optional<Long> memberId, int days, int limit, String filter)
    {
        validate(days, limit, filter);
        // Validate requested filters before any collection; the shared round-robin remains independent of the open page.
        snapshot(circleId, memberId, days, limit, filter);
        collect();
        return snapshot(circleId, memberId, days, limit, filter);
    }

    public void collect()
    {
        if (!refreshLock.tryLock()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Team activity refresh is already running");
        }
        try {
            GitHubCredential credential = credentials.resolveGitHubCredential();
            TeamDirectory directory = teams.directory();
            long accountId = teams.accountId();
            requireSameAccount(credential);
            List<TeamMember> members = members(directory.circles());
            if (members.isEmpty()) {
                return;
            }
            List<String> repositories = repositories(directory.circles());
            Map<Long, String> identities = new LinkedHashMap<>();
            members.forEach(member -> identities.put(member.githubId(), member.login()));
            Identity identity = new Identity(accountId, directory.accountLogin(), identities);
            CollectionState old = state(accountId);
            List<String> errors = new ArrayList<>();
            List<Long> scannedMembers = new ArrayList<>();
            List<String> scannedRepositories = new ArrayList<>();
            int memberCursor = old.memberCursor();
            int repositoryCursor = old.repositoryCursor();
            Map<String, Integer> reviewOffsets = new LinkedHashMap<>();
            repositories.forEach(repository -> reviewOffsets.put(repository, old.reviewOffsets().getOrDefault(repository, 0)));
            boolean rateLimited = false;
            for (int index = 0; index < Math.min(4, members.size()) && !rateLimited; index++) {
                TeamMember member = members.get(Math.floorMod(memberCursor++, members.size()));
                Observation result = github.publicEvents(credential.token(), member.login(), identity);
                requireSameAccount(credential);
                store.save(accountId, result.events());
                errors.addAll(result.errors());
                if (result.errors().isEmpty()) {
                    scannedMembers.add(member.githubId());
                }
                rateLimited = result.rateLimited();
            }
            for (int index = 0; index < Math.min(2, repositories.size()) && !rateLimited; index++) {
                String repository = repositories.get(Math.floorMod(repositoryCursor++, repositories.size()));
                Observation result = github.repository(credential.token(), repository, identity, reviewOffsets.get(repository));
                requireSameAccount(credential);
                store.save(accountId, result.events());
                requireSameAccount(credential);
                store.save(accountId, cachedReviews(result.events(), identity));
                reviewOffsets.put(repository, Math.floorMod(reviewOffsets.get(repository) + 5, 100));
                errors.addAll(result.errors());
                if (result.errors().isEmpty()) {
                    scannedRepositories.add(repository);
                }
                rateLimited = result.rateLimited();
            }
            Instant now = Instant.now();
            requireSameAccount(credential);
            writeState(accountId, new CollectionState(old.observedSince().or(() -> Optional.of(now)), Optional.of(now), memberCursor, repositoryCursor,
                    reviewOffsets, scannedMembers, scannedRepositories, errors));
            store.deleteBefore(now.minusSeconds(RETENTION_DAYS * 86400L));
            requireSameAccount(credential);
        }
        finally {
            refreshLock.unlock();
        }
    }

    /** Cached reviews have logins but no actor user IDs; only enrich identities confirmed by this GitHub batch. */
    private List<TeamActivityEvent> cachedReviews(List<TeamActivityEvent> observations, Identity identity)
    {
        Map<String, Long> verified = new LinkedHashMap<>();
        verified.put(identity.viewerLogin().toLowerCase(Locale.ROOT), identity.viewerId());
        observations.forEach(event -> {
            if (event.actorId() > 0) {
                verified.put(event.actorLogin().toLowerCase(Locale.ROOT), event.actorId());
            }
            event.subjectAuthorId().ifPresent(id -> event.subjectAuthorLogin().ifPresent(login -> verified.put(login.toLowerCase(Locale.ROOT), id)));
        });
        Map<String, TeamActivityEvent> subjects = new LinkedHashMap<>();
        observations.stream().filter(TeamActivityEvent::pullRequest).forEach(event -> subjects.putIfAbsent(workKey(event), event));
        List<TeamActivityEvent> cached = new ArrayList<>();
        subjects.values().stream().limit(5).forEach(subject -> pulls.findIdByRepoAndNumber(subject.repository(), subject.number()).flatMap(details::find)
                .ifPresent(detail -> {
                    for (PrReviewState review : detail.reviews()) {
                        Long actorId = verified.get(review.login().toLowerCase(Locale.ROOT));
                        if (actorId == null || review.githubId() == null || review.submittedAt() == null || review.state().equalsIgnoreCase("pending")) {
                            continue;
                        }
                        List<Long> direct = new ArrayList<>();
                        String body = Optional.ofNullable(review.body()).orElse("");
                        identity.members().forEach((memberId, login) -> {
                            if (memberId != identity.viewerId() && (actorId.equals(memberId)
                                    && (mentions(body, identity.viewerLogin()) || subject.subjectAuthorId().filter(id -> id == identity.viewerId()).isPresent())
                                    || actorId == identity.viewerId() && (mentions(body, login) || subject.subjectAuthorId().filter(id -> id.equals(memberId)).isPresent()))) {
                                direct.add(memberId);
                            }
                        });
                        cached.add(new TeamActivityEvent("review:" + review.githubId(), "PR_REVIEWED", actorId, review.login(), subject.repository(), subject.number(),
                                subject.title(), subject.url(), review.submittedAt(), subject.subjectAuthorId(), subject.subjectAuthorLogin(), "CACHED_DETAIL", direct));
                    }
                }));
        return ImmutableList.copyOf(cached);
    }

    static List<TeamActivitySnapshot.PersonStatistics> statistics(List<TeamMember> members, List<TeamActivityEvent> events, long viewerId)
    {
        Set<String> viewerWork = events.stream().filter(event -> event.actorId() == viewerId || event.subjectAuthorId().filter(id -> id == viewerId).isPresent()
                || !event.viewerInteractionMemberIds().isEmpty()).map(TeamActivityService::workKey).collect(toSet());
        return members.stream().map(member -> {
            long id = member.githubId();
            List<TeamActivityEvent> performed = events.stream().filter(event -> event.actorId() == id).toList();
            List<TeamActivityEvent> shared = events.stream().filter(event -> event.actorId() == id || event.subjectAuthorId().filter(author -> author == id).isPresent()
                    || event.viewerInteractionMemberIds().contains(id)).filter(event -> viewerWork.contains(workKey(event))).toList();
            return new TeamActivitySnapshot.PersonStatistics(id, member.login(), count(performed, event -> event.kind().equals("PR_OPENED")),
                    count(events, event -> event.kind().equals("PR_MERGED") && event.subjectAuthorId().filter(author -> author == id).isPresent()),
                    count(performed, event -> event.kind().equals("PR_MERGED")), count(performed, event -> event.kind().equals("PR_REVIEWED")),
                    count(performed, event -> event.kind().equals("ISSUE_OPENED")), count(performed, event -> event.kind().equals("ISSUE_CLOSED")),
                    performed.stream().filter(event -> event.kind().endsWith("COMMENT")).count(),
                    events.stream().filter(event -> event.viewerInteractionMemberIds().contains(id)).count(),
                    count(shared, TeamActivityEvent::pullRequest), count(shared, event -> !event.pullRequest()));
        }).collect(toImmutableList());
    }

    private static boolean involves(TeamActivityEvent event, long memberId)
    {
        return event.actorId() == memberId || event.subjectAuthorId().filter(author -> author == memberId).isPresent()
                || event.viewerInteractionMemberIds().contains(memberId);
    }

    private static boolean matchesFilter(TeamActivityEvent event, String filter, Set<Long> memberIds)
    {
        return switch (filter) {
            case "all" -> true;
            case "merged" -> event.kind().equals("PR_MERGED");
            case "reviews" -> event.kind().equals("PR_REVIEWED");
            case "issues" -> event.kind().startsWith("ISSUE_");
            case "interactions" -> event.viewerInteractionMemberIds().stream().anyMatch(memberIds::contains);
            case "authored-merges" -> event.kind().equals("PR_MERGED") && event.subjectAuthorId().filter(memberIds::contains).isPresent();
            case "reviews-performed" -> event.kind().equals("PR_REVIEWED") && memberIds.contains(event.actorId());
            case "issues-opened" -> event.kind().equals("ISSUE_OPENED") && memberIds.contains(event.actorId());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown Team activity filter");
        };
    }

    private static long count(List<TeamActivityEvent> events, Predicate<TeamActivityEvent> predicate)
    {
        return events.stream().filter(predicate).map(TeamActivityService::workKey).distinct().count();
    }

    private static String workKey(TeamActivityEvent event)
    {
        return event.repository() + "#" + event.number();
    }

    private static boolean includes(TeamCircle circle, TeamActivityEvent event)
    {
        return circle.repositories().isEmpty() || circle.repositories().stream().anyMatch(repository -> repository.equalsIgnoreCase(event.repository()));
    }

    private static List<TeamCircle> circles(TeamDirectory directory, Optional<String> selected)
    {
        List<TeamCircle> circles = directory.circles().stream().filter(circle -> selected.isEmpty() || circle.id().equals(selected.get())).collect(toImmutableList());
        if (selected.isPresent() && circles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Team circle not found");
        }
        return circles;
    }

    private static List<TeamMember> members(List<TeamCircle> circles)
    {
        Map<Long, TeamMember> result = new LinkedHashMap<>();
        circles.forEach(circle -> circle.members().forEach(member -> result.putIfAbsent(member.githubId(), member)));
        return ImmutableList.copyOf(result.values());
    }

    private List<String> repositories(List<TeamCircle> circles)
    {
        Set<String> watched = new LinkedHashSet<>();
        workspaces.findAll().forEach(workspace -> watched.add(workspace.monitoredRepository().fullName().toLowerCase(Locale.ROOT)));
        return watched.stream().filter(repository -> circles.stream().anyMatch(circle -> circle.repositories().isEmpty()
                || circle.repositories().stream().anyMatch(repository::equalsIgnoreCase))).collect(toImmutableList());
    }

    private CollectionState state(long accountId)
    {
        return settings.get("teams.activity." + accountId).map(json -> {
            try {
                return mapper.readValue(json, CollectionState.class);
            }
            catch (JsonProcessingException failure) {
                throw new IllegalStateException("Could not read Team activity coverage", failure);
            }
        }).orElse(new CollectionState(Optional.empty(), Optional.empty(), 0, 0, ImmutableMap.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of()));
    }

    private void writeState(long accountId, CollectionState state)
    {
        try {
            settings.put("teams.activity." + accountId, mapper.writeValueAsString(state));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not save Team activity coverage", failure);
        }
    }

    private void requireSameAccount(GitHubCredential credential)
    {
        if (credentials.resolveGitHubCredential() != credential) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GitHub account changed; reload Team activity");
        }
    }

    private static void validate(int days, int limit, String filter)
    {
        if (days < 1 || days > RETENTION_DAYS || limit < 1 || limit > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be 1–90 and limit must be 1–500");
        }
        if (!FILTERS.contains(filter)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown Team activity filter");
        }
    }

    private record CollectionState(Optional<Instant> observedSince, Optional<Instant> lastRefreshedAt, int memberCursor, int repositoryCursor,
            Map<String, Integer> reviewOffsets, List<Long> membersScanned, List<String> repositoriesScanned, List<String> errors)
    {
        private CollectionState
        {
            reviewOffsets = ImmutableMap.copyOf(reviewOffsets);
            membersScanned = ImmutableList.copyOf(membersScanned);
            repositoriesScanned = ImmutableList.copyOf(repositoriesScanned);
            errors = ImmutableList.copyOf(errors);
        }
    }
}
