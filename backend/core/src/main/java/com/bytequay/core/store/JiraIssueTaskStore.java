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

import com.bytequay.core.domain.JiraIssueTask;

import java.util.Optional;

public interface JiraIssueTaskStore
{
    /** The ticket's current attempt; an attempt another one replaced is found only by its task. */
    Optional<JiraIssueTask> findBySource(String cloudId, String issueId);

    Optional<JiraIssueTask> findByTaskId(String taskId);

    void insert(JiraIssueTask task);

    /** Moves an attempt off its ticket, so the next attempt becomes the ticket's task; the retired one keeps its source. */
    void retire(String taskId);
}
