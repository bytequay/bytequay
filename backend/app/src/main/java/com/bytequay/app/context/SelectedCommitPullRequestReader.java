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
package com.bytequay.app.context;

import com.bytequay.app.cherrypick.CherryPickPullRequestLookup;
import com.bytequay.app.cherrypick.PullRequestAssociation;
import com.bytequay.app.cherrypick.PullRequestLookupResult;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.repository.RepositoryEnrichmentService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.domain.CommitEnrichment;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.core.context.ContextLimits.bytes;
import static com.bytequay.core.domain.Checks.checkSha;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Resolves a bounded selection of commit associations using the existing durable enrichment and provider lookup. */
@Service
public class SelectedCommitPullRequestReader
{
    public static final int MAX_COMMITS = 10;

    private static final Logger log = LoggerFactory.getLogger(SelectedCommitPullRequestReader.class);
    private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_METADATA_BYTES = 256;

    private final RepositoryEnrichmentService enrichment;
    private final GitCommandRunner git;
    private final CherryPickPullRequestLookup lookup;
    private final Clock clock;

    public SelectedCommitPullRequestReader(RepositoryEnrichmentService enrichment, GitCommandRunner git, CherryPickPullRequestLookup lookup, Clock clock)
    {
        this.enrichment = requireNonNull(enrichment, "enrichment is null");
        this.git = requireNonNull(git, "git is null");
        this.lookup = requireNonNull(lookup, "lookup is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Reads only the supplied full commit IDs, normalized to lowercase before any I/O.
     *
     * @param workspace backend-resolved repository and monitored upstream routing
     * @param shas one to ten unique full 40- or 64-character hexadecimal commit IDs
     * @return one item per requested SHA in request order, with checked-negative and failed-read limitations
     * @throws IllegalArgumentException for invalid, duplicate or excessive requested IDs
     * @throws IllegalStateException when local enrichment belongs to another repository or commit
     *
     * Known positive associations need no Git or provider read. Each unresolved SHA has at most one
     * two-second local metadata read; only exact, complete commit metadata permits a due provider lookup.
     * There is at most one provider batch and one association-only upsert. No Git fetch, PR detail,
     * history scan or other write occurs. A failed check never becomes a new checked negative.
     */
    public Read read(WorkspaceRepositoryContext workspace, List<String> shas)
    {
        List<String> selected = ImmutableList.copyOf(shas);
        checkArgument(!selected.isEmpty() && selected.size() <= MAX_COMMITS, "Select between 1 and %s commits", MAX_COMMITS);
        selected = selected.stream().map(sha -> checkSha(sha.toLowerCase(Locale.ROOT), "sha")).collect(toImmutableList());
        checkArgument(ImmutableSet.copyOf(selected).size() == selected.size(), "Selected commits contain duplicate SHAs");
        requireNonNull(workspace, "workspace is null");
        long repositoryId = workspace.monitoredRepositoryId();
        checkArgument(repositoryId > 0, "Monitored repository identity is invalid");
        Instant now = Instant.now(clock);
        Map<String, CommitEnrichment> existing = stored(repositoryId, selected);
        Map<String, Item> items = new LinkedHashMap<>();
        List<String> due = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (String sha : selected) {
            Optional<CommitEnrichment> cached = Optional.ofNullable(existing.get(sha));
            items.put(sha, item(sha, cached, false, false, hasAssociationObservation(cached)));
            if (cached.flatMap(CommitEnrichment::pullRequestNumber).isPresent()) {
                continue;
            }
            Optional<String> committedAt = committedAt(workspace, sha);
            if (committedAt.isEmpty()) {
                gaps.add("Commit " + sha + " could not be read as one exact, complete local commit; no provider lookup was attempted.");
            }
            else if (RepositoryEnrichmentService.associationDue(committedAt.orElseThrow(), cached, now)) {
                due.add(sha);
            }
        }
        if (!due.isEmpty()) {
            resolve(workspace, repositoryId, due, existing, items, gaps);
        }
        if (items.values().stream().anyMatch(item -> item.pullRequestNumber().isEmpty() && item.checkedAt().isPresent())) {
            gaps.add("A checked negative means no single merged PR was identified by a stored or selected association lookup; it does not prove that no PR contains the commit.");
        }
        return new Read(selected.stream().map(items::get).collect(toImmutableList()), gaps);
    }

    private Optional<String> committedAt(WorkspaceRepositoryContext workspace, String sha)
    {
        try {
            GitCommandRunner.Result result = git.run(workspace.repository(), METADATA_TIMEOUT, ImmutableList.of("log", "--no-show-signature", "--format=%H%n%cI", "-1", sha, "--"));
            if (!result.successful() || result.truncated() || bytes(result.output()) > MAX_METADATA_BYTES) {
                return Optional.empty();
            }
            List<String> lines = result.output().lines().collect(toImmutableList());
            return lines.size() == 2 && lines.getFirst().equals(sha) ? Optional.of(lines.get(1)) : Optional.empty();
        }
        catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private Map<String, CommitEnrichment> stored(long repositoryId, List<String> shas)
    {
        Set<String> selected = ImmutableSet.copyOf(shas);
        Map<String, CommitEnrichment> result = new LinkedHashMap<>();
        for (CommitEnrichment value : enrichment.findCommits(repositoryId, shas).commits()) {
            checkState(value.repositoryId() == repositoryId && selected.contains(value.sha()), "Stored association identity differs from selected repository or commit");
            checkState(result.putIfAbsent(value.sha(), value) == null, "Stored associations contain a duplicate commit");
        }
        return result;
    }

    private void resolve(
            WorkspaceRepositoryContext workspace, long repositoryId, List<String> due, Map<String, CommitEnrichment> existing,
            Map<String, Item> items, List<String> gaps)
    {
        PullRequestLookupResult response;
        Map<String, PullRequestAssociation> associations = new LinkedHashMap<>();
        try {
            response = lookup.lookUpResult(workspace.monitoredRepository(), ImmutableList.copyOf(due), workspace.defaultBranch(), gaps);
            checkState(due.containsAll(response.failedShas()), "Provider failures contain an unrequested commit");
            for (PullRequestLookupResult.Association association : response.associations()) {
                checkState(due.contains(association.sha()) && !response.failedShas().contains(association.sha()), "Provider association identity or outcome is invalid");
                checkState(associations.putIfAbsent(association.sha(), association.pullRequest()) == null, "Provider associations contain a duplicate commit");
            }
        }
        catch (RuntimeException failure) {
            due.forEach(sha -> {
                Optional<CommitEnrichment> cached = Optional.ofNullable(existing.get(sha));
                items.put(sha, item(sha, cached, true, true, hasAssociationObservation(cached)));
            });
            gaps.add("The selected association lookup failed or returned inconsistent identities; prior checked times were retained and no new negatives were saved.");
            log.info("Selected commit association lookup failed for repository {}: {}", repositoryId, failure.getClass().getSimpleName());
            return;
        }
        Instant checkedAt = Instant.now(clock);
        List<CommitEnrichment> updates = new ArrayList<>();
        for (String sha : due) {
            if (response.failedShas().contains(sha)) {
                Optional<CommitEnrichment> cached = Optional.ofNullable(existing.get(sha));
                items.put(sha, item(sha, cached, true, true, hasAssociationObservation(cached)));
                gaps.add("Commit " + sha + " could not be checked with the provider; its prior checked time was retained.");
                continue;
            }
            Optional<PullRequestAssociation> association = Optional.ofNullable(associations.get(sha));
            CommitEnrichment observed = new CommitEnrichment(repositoryId, sha, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    association.map(PullRequestAssociation::number), association.map(PullRequestAssociation::title).filter(value -> !value.isBlank()),
                    association.map(PullRequestAssociation::url).filter(value -> !value.isBlank()), Optional.empty(), Optional.of(checkedAt), checkedAt);
            updates.add(observed);
            items.put(sha, item(sha, Optional.of(observed), true, false, false));
        }
        if (updates.isEmpty()) {
            return;
        }
        try {
            enrichment.upsertCommits(ImmutableList.copyOf(updates));
            List<String> savedShas = updates.stream().map(CommitEnrichment::sha).collect(toImmutableList());
            Map<String, CommitEnrichment> saved = stored(repositoryId, savedShas);
            checkState(savedShas.stream().allMatch(sha -> Optional.ofNullable(saved.get(sha)).flatMap(CommitEnrichment::associationCheckedAt)
                            .filter(value -> !value.isBefore(checkedAt)).isPresent()),
                    "Saved association observations could not be confirmed");
            savedShas.forEach(sha -> items.put(sha, item(sha, Optional.of(saved.get(sha)), true, false, true)));
        }
        catch (RuntimeException failure) {
            gaps.add("Selected association results were observed, but their local save or reread could not be confirmed; returned observations are marked unpersisted.");
            log.info("Selected commit association persistence unconfirmed for repository {}: {}", repositoryId, failure.getClass().getSimpleName());
        }
    }

    private static Item item(String sha, Optional<CommitEnrichment> value, boolean attempted, boolean failed, boolean persisted)
    {
        return new Item(sha, value.flatMap(CommitEnrichment::pullRequestNumber), value.flatMap(CommitEnrichment::pullRequestTitle),
                value.flatMap(CommitEnrichment::pullRequestUrl), value.flatMap(CommitEnrichment::associationCheckedAt), attempted, failed, persisted);
    }

    private static boolean hasAssociationObservation(Optional<CommitEnrichment> value)
    {
        return value.flatMap(CommitEnrichment::pullRequestNumber).isPresent() || value.flatMap(CommitEnrichment::associationCheckedAt).isPresent();
    }

    public record Read(List<Item> items, List<String> gaps)
    {
        public Read
        {
            items = ImmutableList.copyOf(items);
            gaps = ImmutableList.copyOf(gaps);
        }
    }

    /**
     * A positive association, prior or new checked negative, or unchecked commit; failed lookups retain prior checkedAt.
     * Persisted means the returned association observation was read from storage, not merely that an avatar row exists.
     * A new observation is confirmed only when its reread checked time is at least this lookup's check time.
     */
    public record Item(
            String sha,
            Optional<Integer> pullRequestNumber,
            Optional<String> pullRequestTitle,
            Optional<String> pullRequestUrl,
            Optional<Instant> checkedAt,
            boolean lookupAttempted,
            boolean lookupFailed,
            boolean persisted)
    {
        public Item
        {
            checkSha(sha, "sha");
            requireNonNull(pullRequestNumber, "pullRequestNumber is null");
            requireNonNull(pullRequestTitle, "pullRequestTitle is null");
            requireNonNull(pullRequestUrl, "pullRequestUrl is null");
            requireNonNull(checkedAt, "checkedAt is null");
            checkArgument(!lookupFailed || lookupAttempted, "A failed lookup must have been attempted");
            checkArgument(pullRequestNumber.isPresent() || (pullRequestTitle.isEmpty() && pullRequestUrl.isEmpty()), "PR metadata requires its number");
        }
    }
}
