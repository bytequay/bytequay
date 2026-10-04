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

import com.bytequay.app.context.ContextBuildService;
import com.bytequay.app.context.ContextSpecCheck;
import com.bytequay.app.context.ContextSpecCheckService;
import com.bytequay.app.context.ContextSpecModule;
import com.bytequay.app.context.ContextText;
import com.bytequay.core.domain.Goal;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.store.GoalRecommendationStore;
import com.bytequay.core.store.GoalStore;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/**
 * The spec checks a goal's agent recommends. Start runs the existing check with the module, pages and reproduction count the card names; when
 * the check ends, the card settles and the agent hears what the check found. The modules the agent may name are read once per monitored head.
 */
@Component
public class GoalSpecChecks
{
    static final int MAX_MODULES = 15;
    private static final Logger log = LoggerFactory.getLogger(GoalSpecChecks.class);

    private final GoalStore goals;
    private final GoalRecommendationStore recommendations;
    private final ObjectProvider<WorkItemService> work;
    private final ObjectProvider<ContextSpecCheckService> checks;
    private final ObjectProvider<ContextBuildService> builds;
    private final ObjectProvider<GoalConversationService> conversations;
    private final Clock clock;
    private final Map<String, Modules> modules = new ConcurrentHashMap<>();
    private final Object writes = new Object();

    private record Modules(String sha, List<ContextSpecModule> found) {}

    public GoalSpecChecks(GoalStore goals, GoalRecommendationStore recommendations, ObjectProvider<WorkItemService> work, ObjectProvider<ContextSpecCheckService> checks,
            ObjectProvider<ContextBuildService> builds, ObjectProvider<GoalConversationService> conversations, Clock clock)
    {
        this.goals = requireNonNull(goals, "goals is null");
        this.recommendations = requireNonNull(recommendations, "recommendations is null");
        this.work = requireNonNull(work, "work is null");
        this.checks = requireNonNull(checks, "checks is null");
        this.builds = requireNonNull(builds, "builds is null");
        this.conversations = requireNonNull(conversations, "conversations is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Start on a spec-check card: the check runs; starting it again changes nothing. */
    public GoalRecommendation start(String workspaceId, String goalId, String recommendationId)
    {
        GoalRecommendation card = recommendations.find(recommendationId).filter(found -> found.goalId().equals(goalId) && found.workspaceId().equals(workspaceId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found"));
        if (card.state() == GoalRecommendation.State.STARTED) {
            return card;
        }
        if (card.state() != GoalRecommendation.State.SHOWN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This card was already finished or dismissed.");
        }
        ContextSpecCheckService.Started started = checks.getObject().start(workspaceId, new ContextSpecCheckService.StartRequest("goal-" + card.id(),
                builds.getObject().get(workspaceId).buildRequestSequence(), card.card().module().orElseThrow(), card.card().pages(),
                card.card().reproductions().orElseThrow()));
        synchronized (writes) {
            GoalRecommendation checking = recommendations.find(recommendationId).orElseThrow().checking(started.check().id(), clock.instant());
            recommendations.save(checking);
            return checking;
        }
    }

    /** A check ended: the card that began it settles, a failed or stopped one showing again for a retry, and its goal's agent hears what it found. */
    public void ended(String workspaceId, ContextSpecCheck check)
    {
        try {
            for (Goal goal : goals.list(workspaceId)) {
                Optional<GoalRecommendation> card;
                synchronized (writes) {
                    card = recommendations.list(goal.id()).stream()
                            .filter(found -> found.state() == GoalRecommendation.State.STARTED && found.checkId().filter(check.id()::equals).isPresent()).findFirst();
                    card.ifPresent(found -> recommendations.save(found.settled(check.state() == ContextSpecCheck.State.DONE, clock.instant())));
                }
                card.ifPresent(found -> conversations.getObject().tell(workspaceId, goal.id(), "check:" + check.id(), summary(check)));
            }
        }
        catch (RuntimeException failure) {
            // The check keeps its report; the agent reads the card's state with its next request.
            log.warn("The end of spec check {} was not told to its Goal", check.id(), failure);
        }
    }

    /**
     * The gaps the goal's last finished spec check reproduced and nobody dismissed, each with the Goal work it became. A later check of the
     * same module keeps the earlier one's findings, so its report stands in when the goal's own check was replaced.
     */
    public List<GoalGap> gaps(String workspaceId, String goalId)
    {
        Optional<GoalRecommendation> last = recommendations.list(goalId).stream()
                .filter(card -> card.card().kind() == GoalRecommendation.Kind.SPEC_CHECK && card.state() == GoalRecommendation.State.DONE && card.checkId().isPresent())
                .max(Comparator.comparing(GoalRecommendation::updatedAt));
        if (last.isEmpty()) {
            return ImmutableList.of();
        }
        String checkId = last.get().checkId().orElseThrow();
        String module = last.get().card().module().orElseThrow();
        List<ContextSpecCheck> reports = checks.getObject().checks(workspaceId);
        Optional<ContextSpecCheck> check = reports.stream().filter(found -> found.id().equals(checkId)).findFirst()
                .or(() -> reports.stream().filter(found -> found.directory().equals(module)).findFirst());
        if (check.isEmpty()) {
            return ImmutableList.of();
        }
        Map<String, WorkItemView> items = work.getObject().list(workspaceId).stream().collect(toImmutableMap(WorkItemView::id, item -> item));
        return check.get().candidates().stream()
                .filter(candidate -> candidate.status() == ContextSpecCheck.Status.CONFIRMED && candidate.dismissed().isEmpty())
                .map(candidate -> {
                    Optional<WorkItemView> item = Optional.ofNullable(items.get(gapItem(module, candidate.id())));
                    return new GoalGap(check.get().id(), candidate.id(), module, candidate.title(), candidate.rule(), candidate.specUrl(), candidate.test(),
                            candidate.draft(), item.map(WorkItemView::id), item.map(WorkItemView::state));
                })
                .collect(toImmutableList());
    }

    /**
     * Adds a reproduced gap to the goal as Develop work under merged delivery, done once its reproduction test passes and the fix merges.
     * Adding it again changes nothing; the draft issue stays the user's to file.
     */
    public WorkItemView adopt(String workspaceId, String goalId, String candidateId)
    {
        GoalGap gap = gaps(workspaceId, goalId).stream().filter(found -> found.candidateId().equals(candidateId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gap not found"));
        String title = gap.draft().map(ContextSpecCheck.Draft::title).orElse(gap.title());
        String criteria = gap.test().map(test -> "The reproduction test " + test.path() + " passes (" + test.command() + "), and the fix that makes it pass merges.")
                .orElse("The gap is fixed, tested and merged.") + "\nSpecification: " + gap.specUrl();
        return work.getObject().create(workspaceId, gapItem(gap.module(), candidateId), Optional.of(goalId), ContextText.excerpt(title, 500), WorkItem.Kind.DEVELOP,
                criteria, WorkItem.CompletionRule.MERGED_DELIVERY);
    }

    /** One work item per gap, whichever check of its module reported it. */
    private static String gapItem(String module, String candidateId)
    {
        return UUID.nameUUIDFromBytes(("gap:" + module + ":" + candidateId).getBytes(UTF_8)).toString();
    }

    /** The modules a spec check may name, the ones whose own code cites a page in the most sections first. */
    public List<ContextSpecModule> modules(String workspaceId)
    {
        String sha = builds.getObject().baseline(workspaceId).map(ContextBuildService.Baseline::sha).orElse("");
        Modules known = modules.get(workspaceId);
        if (known != null && known.sha().equals(sha)) {
            return known.found();
        }
        List<ContextSpecModule> found = checks.getObject().modules(workspaceId).stream().limit(MAX_MODULES).collect(toImmutableList());
        modules.put(workspaceId, new Modules(sha, found));
        return found;
    }

    static String summary(ContextSpecCheck check)
    {
        String what = switch (check.state()) {
            case DONE -> "finished";
            case STOPPED -> "was stopped";
            case FAILED -> "failed" + check.detail().map(detail -> " (" + detail + ")").orElse("");
            case CHECKING, REPRODUCING -> "is still running";
        };
        List<ContextSpecCheck.Candidate> gaps = check.candidates().stream()
                .filter(candidate -> candidate.status() == ContextSpecCheck.Status.CONFIRMED && candidate.dismissed().isEmpty()).collect(toImmutableList());
        List<ContextSpecCheck.Candidate> reported = check.candidates().stream()
                .filter(candidate -> candidate.status() == ContextSpecCheck.Status.ALREADY_REPORTED).collect(toImmutableList());
        StringBuilder text = new StringBuilder("The spec check of " + check.directory() + " " + what + ". ");
        text.append(gaps.isEmpty() ? "It reproduced no gap." : "It reproduced " + gaps.size() + (gaps.size() == 1 ? " gap: " : " gaps: ")
                + gaps.stream().map(ContextSpecCheck.Candidate::title).collect(joining("; ")) + ".");
        if (!reported.isEmpty()) {
            text.append(" Already reported: ").append(reported.stream().map(candidate -> candidate.duplicate()
                    .map(duplicate -> "#" + duplicate.number()).orElse(candidate.title())).collect(joining(", "))).append('.');
        }
        return text.toString();
    }
}
