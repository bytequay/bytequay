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

import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalSource;

import java.util.List;
import java.util.Optional;

/**
 * Workspace goals: local groupings of pull requests, issues, Jira tickets and tasks. Every read
 * comes from local stores and no change writes to GitHub or Jira or starts any work.
 */
public interface GoalService
{
    /** The workspace's goals, oldest first, each item read from the local store for its kind. */
    List<GoalView> list(String workspaceId);

    /** A goal with no items; it is pinned when the workspace has no pinned goal yet. */
    GoalView create(String workspaceId, String title, String description);

    /** Renames or re-describes the goal, and pins it when asked, which unpins the workspace's other goals. */
    GoalView update(String workspaceId, String goalId, Optional<String> title, Optional<String> description, boolean pin);

    /** Removes the goal; when it was pinned, the workspace's oldest remaining goal is pinned. */
    void delete(String workspaceId, String goalId);

    /** Adds an item ByteQuay has stored locally; adding one already in the goal changes nothing. */
    GoalView addItem(String workspaceId, String goalId, GoalItemKind kind, String key);

    GoalView removeItem(String workspaceId, String goalId, GoalItemKind kind, String key);

    /** Stored pull requests, issues, Jira tickets and tasks matching the query, for adding to a goal. */
    List<GoalItemView> candidates(String workspaceId, String query);

    /** Gives an issue or Jira ticket marked as a Goal its workspace goal, named after it, unless it has one. */
    void seed(String workspaceId, GoalSource source, String title);

    /** Removes the goals an issue or Jira ticket seeded, once it is no longer marked as a Goal. */
    void unseed(GoalSource source);
}
