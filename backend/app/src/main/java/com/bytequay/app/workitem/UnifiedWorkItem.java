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
package com.bytequay.app.workitem;

import com.bytequay.app.service.goal.WorkItemView;
import com.bytequay.core.domain.GoalItem;

import java.time.Instant;
import java.util.Optional;

/** A local item or a cached external request, with the same identity once development or publication links them. */
public record UnifiedWorkItem(String id, Source source, String title, String description, String status, boolean closed,
        Instant updatedAt, Optional<GoalItem> reference, Optional<WorkItemView> work)
{
    public enum Source { LOCAL, GITHUB, JIRA }
}
