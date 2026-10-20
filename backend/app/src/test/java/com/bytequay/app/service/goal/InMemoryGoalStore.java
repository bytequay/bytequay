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
import com.bytequay.core.store.GoalStore;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

public final class InMemoryGoalStore
        implements GoalStore
{
    private final Map<String, Goal> goals = new LinkedHashMap<>();

    @Override
    public List<Goal> list(String workspaceId)
    {
        return goals.values().stream().filter(goal -> goal.workspaceId().equals(workspaceId))
                .sorted(Comparator.comparing(Goal::createdAt).thenComparing(Goal::id)).collect(toImmutableList());
    }

    @Override
    public Optional<Goal> find(String goalId)
    {
        return Optional.ofNullable(goals.get(goalId));
    }

    @Override
    public void save(Goal goal)
    {
        goals.put(goal.id(), goal);
    }
}
