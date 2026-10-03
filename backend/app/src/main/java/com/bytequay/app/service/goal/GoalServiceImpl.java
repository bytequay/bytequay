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

import com.bytequay.core.domain.Goal;
import com.bytequay.core.domain.GoalCourse;
import com.bytequay.core.domain.GoalDefinition;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.GoalSource;
import com.bytequay.core.domain.GoalTopic;
import com.bytequay.core.store.GoalRecommendationStore;
import com.bytequay.core.store.GoalStore;
import com.bytequay.core.store.GoalTopicStore;
import com.bytequay.core.store.WorkspaceStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Service
public class GoalServiceImpl
        implements GoalService
{
    private final GoalStore goals;
    private final WorkspaceStore workspaces;
    private final WorkItemService work;
    private final GoalReferenceReader references;
    private final GoalRecommendationStore recommendations;
    private final GoalTopicStore topics;
    private final Clock clock;
    // ponytail: one lock for local Goal edits; split by workspace if edits ever contend.
    private final Object writes = new Object();

    public GoalServiceImpl(GoalStore goals, WorkspaceStore workspaces, WorkItemService work, GoalReferenceReader references, GoalRecommendationStore recommendations,
            GoalTopicStore topics, Clock clock)
    {
        this.goals = requireNonNull(goals, "goals is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.work = requireNonNull(work, "work is null");
        this.references = requireNonNull(references, "references is null");
        this.recommendations = requireNonNull(recommendations, "recommendations is null");
        this.topics = requireNonNull(topics, "topics is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public List<GoalView> list(String workspaceId)
    {
        requireWorkspace(workspaces, workspaceId);
        List<WorkItemView> items = work.list(workspaceId);
        return active(workspaceId).stream().map(goal -> view(goal, items, live(goal), topics.list(goal.id()))).collect(toImmutableList());
    }

    @Override
    public GoalView create(String workspaceId, String title, String description)
    {
        requireWorkspace(workspaces, workspaceId);
        synchronized (writes) {
            Instant now = clock.instant();
            Goal goal = new Goal(UUID.randomUUID().toString(), workspaceId, checkText(title, "title", 500).trim(), description.trim(),
                    unpinned(workspaceId), false, Optional.empty(), Optional.empty(), Optional.empty(), 0, Optional.empty(), now, now);
            goals.save(goal);
            return view(goal);
        }
    }

    @Override
    public GoalView update(String workspaceId, String goalId, Optional<String> title, Optional<String> description, boolean pin)
    {
        requireWorkspace(workspaces, workspaceId);
        synchronized (writes) {
            Goal goal = goal(workspaceId, goalId);
            Instant now = clock.instant();
            Goal updated = goal.withText(title.map(value -> checkText(value, "title", 500).trim()).orElse(goal.title()),
                    description.map(String::trim).orElse(goal.description()), now);
            if (pin && !goal.pinned()) {
                active(workspaceId).stream().filter(Goal::pinned).forEach(other -> goals.save(other.withPinned(false, now)));
                updated = updated.withPinned(true, now);
            }
            goals.save(updated);
            return view(updated);
        }
    }

    @Override
    public GoalView define(String workspaceId, String goalId, long expectedRevision, String title, String objective, GoalDefinition definition)
    {
        requireWorkspace(workspaces, workspaceId);
        synchronized (writes) {
            Goal goal = goal(workspaceId, goalId);
            if (goal.definition().map(GoalDefinition::exchangeId).filter(definition.exchangeId()::equals).isPresent()) {
                return view(goal);
            }
            if (goal.revision() != expectedRevision) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The goal changed after this was proposed. Ask again for an updated proposal.");
            }
            Goal defined = goal.withDefinition(checkText(title, "title", 500).trim(), objective.trim(), definition, clock.instant());
            goals.save(defined);
            return view(defined);
        }
    }

    @Override
    public GoalView planCourse(String workspaceId, String goalId, GoalCourse course)
    {
        requireWorkspace(workspaces, workspaceId);
        synchronized (writes) {
            Goal goal = goal(workspaceId, goalId);
            if (goal.course().map(GoalCourse::exchangeId).filter(course.exchangeId()::equals).isPresent()) {
                return view(goal);
            }
            if (goal.course().isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This goal already has a course; ask its agent to change it.");
            }
            Goal planned = goal.withCourse(course, clock.instant());
            goals.save(planned);
            return view(planned);
        }
    }

    @Override
    public void delete(String workspaceId, String goalId)
    {
        requireWorkspace(workspaces, workspaceId);
        synchronized (writes) {
            archive(goal(workspaceId, goalId));
        }
    }

    @Override
    public GoalView addItem(String workspaceId, String goalId, GoalItemKind kind, String key)
    {
        Goal goal = goal(workspaceId, goalId);
        work.attach(workspaceId, goalId, kind, key);
        return view(goal);
    }

    @Override
    public GoalView removeItem(String workspaceId, String goalId, GoalItemKind kind, String key)
    {
        Goal goal = goal(workspaceId, goalId);
        work.detach(workspaceId, goalId, kind, key);
        return view(goal);
    }

    @Override
    public List<GoalItemView> candidates(String workspaceId, String query)
    {
        return references.candidates(workspaceId, query);
    }

    @Override
    public void seed(String workspaceId, GoalSource source, String title)
    {
        synchronized (writes) {
            Optional<Goal> existing = goals.findBySource(source).stream().filter(goal -> goal.workspaceId().equals(workspaceId) && !goal.archived()).findFirst();
            if (existing.isPresent()) {
                // A goal that kept its work when the source was unmarked becomes the source's goal again.
                if (existing.orElseThrow().markedSource().isEmpty()) {
                    goals.save(existing.orElseThrow().withMarked(true, clock.instant()));
                }
                return;
            }
            Instant now = clock.instant();
            goals.save(new Goal(UUID.randomUUID().toString(), workspaceId, checkText(title, "title", 500).trim(), "", unpinned(workspaceId), false,
                    Optional.of(source), Optional.empty(), Optional.empty(), 0, Optional.empty(), now, now));
        }
    }

    @Override
    public void unseed(GoalSource source)
    {
        synchronized (writes) {
            for (Goal goal : goals.findBySource(source)) {
                if (goal.archived() || goal.markedSource().isEmpty()) {
                    continue;
                }
                // Only work needs keeping; an empty goal is archived, and marking the source again starts a fresh one.
                if (work.hasWork(goal.workspaceId(), goal.id())) {
                    goals.save(goal.withMarked(false, clock.instant()));
                }
                else {
                    archive(goal);
                }
            }
        }
    }

    private void archive(Goal goal)
    {
        goals.save(goal.archive(clock.instant()));
        if (goal.pinned()) {
            active(goal.workspaceId()).stream().findFirst().ifPresent(next -> goals.save(next.withPinned(true, clock.instant())));
        }
    }

    private List<Goal> active(String workspaceId)
    {
        return goals.list(workspaceId).stream().filter(goal -> !goal.archived()).collect(toImmutableList());
    }

    private boolean unpinned(String workspaceId)
    {
        return active(workspaceId).stream().noneMatch(Goal::pinned);
    }

    private Goal goal(String workspaceId, String goalId)
    {
        requireWorkspace(workspaces, workspaceId);
        return goals.find(goalId).filter(goal -> goal.workspaceId().equals(workspaceId) && !goal.archived())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Goal not found"));
    }

    private GoalView view(Goal goal)
    {
        return view(goal, work.list(goal.workspaceId()), live(goal), topics.list(goal.id()));
    }

    private List<GoalRecommendation> live(Goal goal)
    {
        return recommendations.list(goal.id()).stream()
                .filter(card -> card.state() == GoalRecommendation.State.SHOWN || card.state() == GoalRecommendation.State.STARTED)
                .sorted(Comparator.comparing(GoalRecommendation::createdAt).reversed()).collect(toImmutableList());
    }

    private static GoalView view(Goal goal, List<WorkItemView> items, List<GoalRecommendation> cards, List<GoalTopic> known)
    {
        return new GoalView(goal.id(), goal.workspaceId(), goal.title(), goal.description(), goal.pinned(), goal.markedSource(), goal.definition(), goal.revision(),
                goal.course(), items.stream().filter(item -> item.goalId().filter(goal.id()::equals).isPresent()).collect(toImmutableList()), cards, known,
                goal.createdAt(), goal.updatedAt());
    }
}
