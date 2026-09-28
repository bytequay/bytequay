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
package com.bytequay.core.store;

import com.bytequay.core.domain.Goal;
import com.bytequay.core.domain.GoalSource;

import java.util.List;
import java.util.Optional;

public interface GoalStore
{
    /** The workspace's goals, oldest first. */
    List<Goal> list(String workspaceId);

    Optional<Goal> find(String goalId);

    /** Every goal seeded by the issue or Jira ticket, in any workspace. */
    List<Goal> findBySource(GoalSource source);

    /** Inserts the goal or replaces the stored one with the same id. */
    void save(Goal goal);

    void delete(String goalId);
}
