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

import com.bytequay.app.jira.JiraService;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.GoalRecommendationStore;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraDtos;
import com.bytequay.integration.jira.JiraIssue;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * What a goal's agent may recommend as practice: the workspace's open issues and the Jira tickets mapped to it, read from the local stores,
 * without sources that already have work, a task or a card the user dismissed. The ones sharing the most words with the goal come first, and
 * the coverage says how much of each source was read, so the agent never claims it searched everything. The one source a card names is read
 * again from GitHub or Jira before the card floats.
 */
@Component
public class GoalCandidates
{
    private static final Logger log = LoggerFactory.getLogger(GoalCandidates.class);
    static final int MAX_CANDIDATES = 100;
    private static final Set<String> URGENT = ImmutableSet.of("highest", "high");
    // ponytail: the 500 open issues updated last; read further pages once a workspace's backlog outgrows them.
    private static final int ISSUE_PAGES = 5;
    private static final int PAGE_SIZE = 100;
    private static final String HOST = "github.com";
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{4,}");
    private static final Set<String> COMMON = ImmutableSet.of("about", "after", "also", "able", "because", "been", "being", "could", "does", "each", "from", "have",
            "into", "just", "know", "knows", "learn", "make", "more", "most", "only", "over", "should", "some", "than", "that", "their", "them", "then", "there",
            "these", "they", "this", "through", "understand", "user", "want", "well", "what", "when", "which", "while", "with", "work", "would", "your");

    /**
     * One open issue or mapped Jira ticket.
     *
     * @param id an issue's number after a hash, such as #31252, or a ticket's key, such as TRINO-12
     * @param openedAt when an issue was opened; the cached tickets do not say
     */
    public record Candidate(String id, String title, List<String> labels, Optional<String> status, Optional<String> priority, Instant updatedAt,
            Optional<Instant> openedAt) {}

    /** What reading a practice source again found: why it is no longer open for practice, or a doubt its card should carry. */
    public record Read(Optional<String> unready, Optional<String> doubt)
    {
        public static final Read NOTHING = new Read(Optional.empty(), Optional.empty());

        static Read unready(String reason)
        {
            return new Read(Optional.of(reason), Optional.empty());
        }
    }

    /**
     * How much of each source the candidates were chosen from.
     *
     * @param issuesRead the open issues read, the ones updated last
     * @param issuesOpen the open issues the workspace holds
     * @param ticketsRead the cached Jira tickets mapped to the workspace
     */
    public record Coverage(int issuesRead, long issuesOpen, Optional<Instant> issuesSyncedAt, int ticketsRead) {}

    public record Pool(List<Candidate> candidates, Coverage coverage)
    {
        public boolean has(String id)
        {
            return candidates.stream().anyMatch(candidate -> candidate.id().equals(id));
        }
    }

    private final WorkspaceStore workspaces;
    private final RepositoryIssueStore issues;
    private final IssueDevelopmentStore development;
    private final WorkItemStore items;
    private final GoalRecommendationStore recommendations;
    private final ObjectProvider<JiraService> jira;
    private final GitHubPullRequestReadClient gitHub;
    private final CredentialService credentials;

    public GoalCandidates(WorkspaceStore workspaces, RepositoryIssueStore issues, IssueDevelopmentStore development, WorkItemStore items,
            GoalRecommendationStore recommendations, ObjectProvider<JiraService> jira, GitHubPullRequestReadClient gitHub, CredentialService credentials)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.development = requireNonNull(development, "development is null");
        this.items = requireNonNull(items, "items is null");
        this.recommendations = requireNonNull(recommendations, "recommendations is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
    }

    /** The candidates nearest what the goal is about: its title, definition, topics and work. */
    public Pool pool(GoalView goal)
    {
        String workspaceId = goal.workspaceId();
        Workspace workspace = workspaces.findById(workspaceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));
        Set<String> dismissed = recommendations.list(goal.id()).stream()
                .filter(card -> card.state() == GoalRecommendation.State.DISMISSED && card.card().kind() == GoalRecommendation.Kind.WORK)
                .map(card -> card.card().issue().map(number -> "#" + number).or(() -> card.card().ticket().flatMap(this::key)).orElse(""))
                .collect(ImmutableSet.toImmutableSet());
        List<Candidate> eligible = new ArrayList<>();
        int read = 0;
        long open = 0;
        Optional<Instant> synced = Optional.empty();
        for (int page = 0; page < ISSUE_PAGES; page++) {
            RepositoryIssuePage found = issues.findPage(workspace.monitoredRepositoryId(), workspace.monitoredRepository(), page, PAGE_SIZE);
            read += found.issues().size();
            open = found.totalIssues();
            synced = found.syncedAt();
            for (RepositoryIssue issue : found.issues()) {
                String id = "#" + issue.number();
                if (!dismissed.contains(id) && workable(issue)) {
                    eligible.add(new Candidate(id, issue.title(), issue.labels(), Optional.empty(), Optional.empty(), issue.updatedAt(), Optional.of(issue.createdAt())));
                }
            }
            if (!found.hasMore()) {
                break;
            }
        }
        List<JiraIssue> tickets = tickets(workspaceId);
        for (JiraIssue ticket : tickets) {
            if (!dismissed.contains(ticket.key()) && workable(ticket)) {
                eligible.add(new Candidate(ticket.key(), ticket.title(), ticket.labels(), Optional.of(ticket.status().name()), ticket.priority().map(JiraDtos.Choice::name),
                        ticket.updatedAt(), Optional.empty()));
            }
        }
        Set<String> words = words(context(goal));
        Comparator<Candidate> nearest = Comparator.comparingLong((Candidate candidate) -> shared(words, candidate)).reversed()
                .thenComparing(Candidate::updatedAt, Comparator.reverseOrder());
        // Jira's own highest priorities, by its explicit field, are kept before the relevance cut; the agent still reads the nearest first.
        // ponytail: Jira's default scheme names; a configured mapping once a site renames them.
        Predicate<Candidate> urgent = candidate -> candidate.priority().filter(name -> URGENT.contains(name.toLowerCase(Locale.ROOT))).isPresent();
        List<Candidate> kept = Stream.concat(eligible.stream().filter(urgent).sorted(nearest), eligible.stream().filter(urgent.negate()).sorted(nearest))
                .limit(MAX_CANDIDATES).sorted(nearest).collect(toImmutableList());
        return new Pool(kept, new Coverage(read, open, synced, tickets.size()));
    }

    static String context(GoalView goal)
    {
        StringBuilder text = new StringBuilder(goal.title()).append(' ').append(goal.description());
        goal.definition().ifPresent(definition -> text.append(' ').append(definition.successDefinition()).append(' ').append(definition.startingKnowledge()));
        goal.topics().forEach(topic -> text.append(' ').append(topic.topic()));
        goal.items().forEach(item -> text.append(' ').append(item.title()));
        return text.toString();
    }

    /**
     * Reads the source a practice card names again. A closed issue, an issue fixed by a merged pull request linked to close it, or a ticket now
     * done, claimed or worked is no longer open for practice; an open linked pull request may fix the issue, which is a doubt for the user to
     * weigh, not a fix. When the read fails, the card still floats from the cached candidate and says it was not read again.
     *
     * @param card a practice card whose ticket is its stable reference
     */
    public Read read(GoalView goal, GoalRecommendation.Card card)
    {
        try {
            if (card.issue().isPresent()) {
                int number = card.issue().orElseThrow();
                Workspace workspace = workspaces.findById(goal.workspaceId()).orElseThrow();
                GitHubPullRequestReadClient.IssueLinks links = gitHub.fetchIssueLinks(credentials.resolveGitHubToken(goal.workspaceId()),
                        PullRequestRef.of(workspace.monitoredRepository().owner(), workspace.monitoredRepository().repo(), number));
                if (!links.open()) {
                    return Read.unready("Issue #" + number + " is closed.");
                }
                Optional<GitHubPullRequestReadClient.LinkedPull> merged = links.pulls().stream().filter(pull -> "MERGED".equals(pull.state())).findFirst();
                if (merged.isPresent()) {
                    return Read.unready("Issue #" + number + " was fixed by pull request #" + merged.orElseThrow().number() + ", merged.");
                }
                return new Read(Optional.empty(), links.pulls().stream().filter(pull -> "OPEN".equals(pull.state())).findFirst()
                        .map(pull -> "Pull request #" + pull.number() + " is open and linked to close issue #" + number + "; it may already fix it."));
            }
            String[] reference = card.ticket().orElseThrow().split(":", 3);
            JiraIssue ticket = jira.getObject().refreshIssue(reference[0], reference[1], reference[2]);
            return workable(ticket) ? Read.NOTHING : Read.unready("Ticket " + ticket.key() + " is no longer open for practice; it is " + ticket.status().name() + ".");
        }
        catch (RuntimeException failure) {
            log.debug("The practice source of goal {} was not read again: {}", goal.id(), failure.toString());
            return new Read(Optional.empty(), Optional.of(card.issue().map(number -> "Issue #" + number).orElse("The ticket")
                    + " could not be read again just now, so this card rests on ByteQuay's last sync of it."));
        }
    }

    /** The cached ticket with this key mapped to the workspace, if any. */
    public Optional<JiraIssue> ticket(String workspaceId, String key)
    {
        return tickets(workspaceId).stream().filter(ticket -> ticket.key().equalsIgnoreCase(key)).findFirst();
    }

    /** A ticket's stable reference, connection:cloud:issue, as Goal work links it. */
    public static String reference(JiraIssue ticket)
    {
        return ticket.connectionId() + ":" + ticket.cloudId() + ":" + ticket.id();
    }

    private Optional<String> key(String reference)
    {
        String[] parts = reference.split(":", 3);
        return parts.length < 3 ? Optional.empty() : jira.getObject().cachedIssues().stream()
                .filter(ticket -> ticket.cloudId().equals(parts[1]) && ticket.id().equals(parts[2])).map(JiraIssue::key).findFirst();
    }

    private List<JiraIssue> tickets(String workspaceId)
    {
        return jira.getObject().cachedIssues().stream().filter(ticket -> ticket.workspace().map(JiraDtos.WorkspaceLink::id).filter(workspaceId::equals).isPresent())
                .collect(toImmutableList());
    }

    private boolean workable(RepositoryIssue issue)
    {
        return items.findByIdentity(WorkItemLink.issueIdentity(HOST, issue.id())).isEmpty() && development.findTask(HOST, issue.id()).isEmpty()
                && development.findEpic(HOST, issue.id()).filter(marker -> marker.isEpic()).isEmpty();
    }

    private boolean workable(JiraIssue ticket)
    {
        return !ticket.isEpic() && !ticket.hasChildren() && ticket.task().isEmpty() && !"done".equals(ticket.status().category())
                && items.findByIdentity(WorkItemLink.jiraIdentity(ticket.cloudId(), ticket.id())).isEmpty();
    }

    static long shared(Set<String> words, Candidate candidate)
    {
        Set<String> own = words(candidate.title() + " " + String.join(" ", candidate.labels()));
        return own.stream().filter(words::contains).count();
    }

    static Set<String> words(String text)
    {
        ImmutableSet.Builder<String> found = ImmutableSet.builder();
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            if (!COMMON.contains(matcher.group())) {
                found.add(matcher.group());
            }
        }
        return found.build();
    }
}
