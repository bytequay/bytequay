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

import com.bytequay.core.domain.GoalCourse;
import com.bytequay.core.domain.GoalDefinition;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalLesson;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Workspace goals: local groupings of accepted work items. Every read
 * comes from local stores and no change writes to GitHub or Jira or starts any work.
 */
public interface GoalService
{
    /** The workspace's goals, oldest first, each work item and its supporting links read locally. */
    List<GoalView> list(String workspaceId);

    /** A goal with no items; it is pinned when the workspace has no pinned goal yet. */
    GoalView create(String workspaceId, String title, String description);

    /** Renames or re-describes the goal, and pins it when asked, which unpins the workspace's other goals. */
    GoalView update(String workspaceId, String goalId, Optional<String> title, Optional<String> description, boolean pin);

    /** Pauses guidance, so the goal's agent hears no automatic events, or resumes it. */
    GoalView pause(String workspaceId, String goalId, boolean paused);

    /**
     * Saves a definition the user accepted, with the title and objective it proposed. Accepting the same proposal again changes nothing;
     * a proposal built before the goal's last change is refused.
     */
    GoalView define(String workspaceId, String goalId, long expectedRevision, String title, String objective, GoalDefinition definition);

    /**
     * Names the goal as its agent proposed, while the goal is still undefined and its title is one of the replaceable ones: the working title
     * its first words gave it, or a name the agent gave it before. A title the user chose stays, and the revision does not change.
     *
     * @return the goal under its new name, or empty when it kept its title
     */
    Optional<GoalView> name(String workspaceId, String goalId, String title, Set<String> replaceable);

    /** Saves the course the user accepted, in place of an earlier one it revises; accepting it again changes nothing. */
    GoalView planCourse(String workspaceId, String goalId, GoalCourse course);

    /** Saves the map drawn for the accepted course of that exchange; a course revised since, or one already mapped, keeps its own. */
    void mapCourse(String workspaceId, String goalId, String exchangeId, GoalLesson.Diagram map);

    /** Archives the goal and retains its work; the oldest active goal inherits its pin. */
    void delete(String workspaceId, String goalId);

    /** Adds an item ByteQuay has stored locally; adding one already in the goal changes nothing. */
    GoalView addItem(String workspaceId, String goalId, GoalItemKind kind, String key);

    GoalView removeItem(String workspaceId, String goalId, GoalItemKind kind, String key);

    /** Stored pull requests, issues, Jira tickets and tasks matching the query, for adding to a goal. */
    List<GoalItemView> candidates(String workspaceId, String query);
}
