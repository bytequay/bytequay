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
import com.bytequay.app.team.TeamActivitySnapshot.Coverage;
import com.bytequay.app.team.TeamActivitySnapshot.Day;
import com.bytequay.app.team.TeamActivitySnapshot.PersonStatistics;
import com.bytequay.app.team.TeamActivitySnapshot.Statistics;
import com.bytequay.core.domain.PrDetailFreshness;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.TeamActivityEvent;
import com.bytequay.core.domain.Workspace;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
import java.util.stream.IntStream;

import static com.bytequay.app.repository.github.GitHubTeamActivityClient.LOOK_BACK;
import static com.bytequay.app.repository.github.GitHubTeamActivityClient.mentions;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Comparator.naturalOrder;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toSet;

@Service
public class TeamActivityService
{
    private static final int RETENTION_DAYS = 90;
    private static final Set<String> FILTERS = ImmutableSet.of("all", "merged", "reviews", "issues", "prs-opened", "authored-merges", "merges-performed",
            "reviews-performed", "issues-opened", "issues-closed", "comments", "interactions", "shared");

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
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This person is not in this circle");
        }
        Set<Long> memberIds = members.stream().map(TeamMember::githubId).collect(toSet());
        List<TeamActivityEvent> observations = store.findSince(accountId, Instant.now().minusSeconds(days * 86400L)).stream()
                .filter(event -> circles.stream().anyMatch(circle -> includes(circle, event)))
                .filter(event -> memberIds.stream().anyMatch(id -> involves(event, id)) || event.actorId() == accountId)
                .sorted(Comparator.comparing(TeamActivityEvent::occurredAt).reversed().thenComparing(TeamActivityEvent::id)).collect(toImmutableList());
        Set<String> viewerWork = observations.stream().filter(event -> event.actorId() == accountId || event.subjectAuthorId().filter(id -> id == accountId).isPresent()
                || !event.viewerInteractionMemberIds().isEmpty()).map(TeamActivityService::workKey).collect(toSet());
        // A person counts only in repositories that one of their circles covers.
        Map<Long, List<TeamActivityEvent>> scoped = new LinkedHashMap<>();
        members.forEach(member -> scoped.put(member.githubId(), observations.stream()
                .filter(event -> circles.stream().anyMatch(circle -> includes(circle, event) && contains(circle, member.githubId()))).collect(toImmutableList())));
        List<TeamActivityEvent> display = observations.stream().filter(event -> {
            Set<Long> ids = circles.stream().filter(circle -> includes(circle, event)).flatMap(circle -> circle.members().stream())
                    .map(TeamMember::githubId).filter(memberIds::contains).collect(toSet());
            return ids.stream().anyMatch(id -> involves(event, id)) && matchesFilter(event, filter, ids, viewerWork);
        }).collect(toImmutableList());
        CollectionState state = state(accountId);
        List<String> repositories = repositories(circles);
        int membersScanned = (int) state.membersScanned().stream().filter(memberIds::contains).count();
        int repositoriesScanned = (int) state.repositoriesScanned().stream().filter(repositories::contains).count();
        Coverage coverage = new Coverage(!state.errors().isEmpty() || membersScanned < members.size() || repositoriesScanned < repositories.size(),
                state.observedSince(), state.lastRefreshedAt(), state.errors(), members.size(), membersScanned, repositories.size(), repositoriesScanned);
        requireSameAccount(credential);
        List<PersonStatistics> people = members.stream().map(member -> new PersonStatistics(member.githubId(), member.login(),
                statistics(ImmutableMap.of(member.githubId(), scoped.get(member.githubId())), viewerWork),
                scoped.get(member.githubId()).stream().filter(event -> event.actorId() == member.githubId()).map(TeamActivityEvent::occurredAt).max(naturalOrder())))
                .collect(toImmutableList());
        return new TeamActivitySnapshot(directory.accountLogin(), display.stream().limit(limit).collect(toImmutableList()), display.size(),
                (int) display.stream().map(TeamActivityService::workKey).distinct().count(), people, statistics(scoped, viewerWork), perDay(display, days, state), coverage);
    }

    /** Each pass reads back {@link GitHubTeamActivityClient#LOOK_BACK}, so days before the first pass's window or after the latest pass were never observed. */
    private static List<Day> perDay(List<TeamActivityEvent> events, int days, CollectionState state)
    {
        // ponytail: one window from the first to the latest pass; record pass dates if a closed app outlasting the look-back must show as a gap.
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        Map<LocalDate, Long> updates = events.stream().collect(groupingBy(event -> LocalDate.ofInstant(event.occurredAt(), zone), counting()));
        Optional<LocalDate> from = state.observedSince().map(since -> LocalDate.ofInstant(since.minus(LOOK_BACK), zone));
        Optional<LocalDate> to = state.lastRefreshedAt().map(at -> LocalDate.ofInstant(at, zone));
        return IntStream.range(0, days).mapToObj(offset -> today.minusDays(days - 1 - offset)).map(date -> new Day(date, updates.getOrDefault(date, 0L),
                from.filter(start -> !date.isBefore(start)).isPresent() && to.filter(end -> !date.isAfter(end)).isPresent())).collect(toImmutableList());
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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A refresh is already running");
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
                Observation result = github.repository(credential.token(), repository, identity, reviewOffsets.get(repository),
                        (number, updatedAt) -> cachedDetail(repository, number, updatedAt));
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

    private Optional<StoredPrDetail> cachedDetail(String repository, int number, Instant updatedAt)
    {
        return pulls.findIdByRepoAndNumber(repository, number).flatMap(id -> {
            PrDetailFreshness freshness = details.findFreshness(id);
            if (freshness.discussionAt().filter(at -> at.isAfter(Instant.now().minus(Duration.ofMinutes(10)))).isEmpty()
                    || freshness.discussionSourceUpdatedAt().filter(at -> !at.isBefore(updatedAt)).isEmpty()) {
                return Optional.empty();
            }
            return details.find(id).filter(detail -> detail.reviewsComplete() && detail.timelineComplete()
                    && detail.raw().updatedAt() != null && !detail.raw().updatedAt().isBefore(updatedAt));
        });
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

    /** Each count is a set of facts its drill-down filter selects, so a scope total counts a fact that several people share once. */
    static Statistics statistics(Map<Long, List<TeamActivityEvent>> scoped, Set<String> viewerWork)
    {
        return new Statistics(count("prs-opened", scoped, viewerWork), count("authored-merges", scoped, viewerWork),
                count("merges-performed", scoped, viewerWork), count("reviews-performed", scoped, viewerWork), count("issues-opened", scoped, viewerWork),
                count("issues-closed", scoped, viewerWork), count("comments", scoped, viewerWork), count("interactions", scoped, viewerWork),
                count("shared", scoped, viewerWork, TeamActivityEvent::pullRequest), count("shared", scoped, viewerWork, event -> !event.pullRequest()));
    }

    private static long count(String filter, Map<Long, List<TeamActivityEvent>> scoped, Set<String> viewerWork)
    {
        return count(filter, scoped, viewerWork, event -> true);
    }

    private static long count(String filter, Map<Long, List<TeamActivityEvent>> scoped, Set<String> viewerWork, Predicate<TeamActivityEvent> subject)
    {
        Set<String> facts = new HashSet<>();
        scoped.forEach((id, events) -> events.stream().filter(subject).filter(event -> counts(filter, event, id, viewerWork))
                .forEach(event -> facts.add(fact(filter, event, id))));
        return facts.size();
    }

    /** A review counts once per person and pull request, comments and interactions once per observation, anything else once per item. */
    private static String fact(String filter, TeamActivityEvent event, long id)
    {
        return switch (filter) {
            case "reviews-performed" -> id + ":" + workKey(event);
            case "comments", "interactions" -> event.id();
            default -> workKey(event);
        };
    }

    /** One member's part in an event, as both a drill-down filter and its statistic select it. */
    private static boolean counts(String filter, TeamActivityEvent event, long id, Set<String> viewerWork)
    {
        boolean performed = event.actorId() == id;
        return switch (filter) {
            case "prs-opened" -> performed && event.kind().equals("PR_OPENED");
            case "authored-merges" -> event.kind().equals("PR_MERGED") && event.subjectAuthorId().filter(author -> author == id).isPresent();
            case "merges-performed" -> performed && event.kind().equals("PR_MERGED");
            case "reviews-performed" -> performed && event.kind().equals("PR_REVIEWED");
            case "issues-opened" -> performed && event.kind().equals("ISSUE_OPENED");
            case "issues-closed" -> performed && event.kind().equals("ISSUE_CLOSED");
            case "comments" -> performed && event.kind().endsWith("COMMENT");
            case "interactions" -> event.viewerInteractionMemberIds().contains(id);
            case "shared" -> involves(event, id) && viewerWork.contains(workKey(event));
            default -> throw new IllegalArgumentException("Not a statistic filter: " + filter);
        };
    }

    private static boolean involves(TeamActivityEvent event, long memberId)
    {
        return event.actorId() == memberId || event.subjectAuthorId().filter(author -> author == memberId).isPresent()
                || event.viewerInteractionMemberIds().contains(memberId);
    }

    private static boolean matchesFilter(TeamActivityEvent event, String filter, Set<Long> memberIds, Set<String> viewerWork)
    {
        return switch (filter) {
            case "all" -> true;
            case "merged" -> event.kind().equals("PR_MERGED");
            case "reviews" -> event.kind().equals("PR_REVIEWED") || event.kind().equals("PR_REVIEW_COMMENT");
            case "issues" -> event.kind().startsWith("ISSUE_");
            default -> memberIds.stream().anyMatch(id -> counts(filter, event, id, viewerWork));
        };
    }

    private static String workKey(TeamActivityEvent event)
    {
        return event.repository() + "#" + event.number();
    }

    private static boolean contains(TeamCircle circle, long memberId)
    {
        return circle.members().stream().anyMatch(member -> member.githubId() == memberId);
    }

    private static boolean includes(TeamCircle circle, TeamActivityEvent event)
    {
        return circle.repositories().isEmpty() || circle.repositories().stream().anyMatch(repository -> repository.equalsIgnoreCase(event.repository()));
    }

    private static List<TeamCircle> circles(TeamDirectory directory, Optional<String> selected)
    {
        List<TeamCircle> circles = directory.circles().stream().filter(circle -> selected.isEmpty() || circle.id().equals(selected.get())).collect(toImmutableList());
        if (selected.isPresent() && circles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Private circle was not found");
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
        workspaces.findAll().stream().filter(Workspace::isGitHub).forEach(workspace -> watched.add(workspace.monitoredRepository().fullName().toLowerCase(Locale.ROOT)));
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
                throw new IllegalStateException("Could not read circle activity coverage", failure);
            }
        }).orElse(new CollectionState(Optional.empty(), Optional.empty(), 0, 0, ImmutableMap.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of()));
    }

    private void writeState(long accountId, CollectionState state)
    {
        try {
            settings.put("teams.activity." + accountId, mapper.writeValueAsString(state));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not save circle activity coverage", failure);
        }
    }

    private void requireSameAccount(GitHubCredential credential)
    {
        if (credentials.resolveGitHubCredential() != credential) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GitHub account changed; reload circle activity");
        }
    }

    private static void validate(int days, int limit, String filter)
    {
        if (days < 1 || days > RETENTION_DAYS || limit < 1 || limit > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be 1–90 and limit must be 1–500");
        }
        if (!FILTERS.contains(filter)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown activity filter");
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
