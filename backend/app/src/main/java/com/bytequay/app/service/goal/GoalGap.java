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

import com.bytequay.app.context.ContextSpecCheck;
import com.bytequay.core.domain.WorkItem;

import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * A gap the goal's last spec check reproduced with a failing test and nobody dismissed, and the Goal work it became, if the user added it.
 *
 * @param draft the issue the check drafted, which only the user files
 * @param workItemId the Goal work it became; until then the gap is still open
 */
public record GoalGap(String checkId, String candidateId, String module, String title, String rule, String specUrl, Optional<ContextSpecCheck.Test> test,
        Optional<ContextSpecCheck.Draft> draft, Optional<String> workItemId, Optional<WorkItem.State> state)
{
    public GoalGap
    {
        checkText(checkId, "checkId");
        checkText(candidateId, "candidateId");
        checkText(module, "module");
        checkText(title, "title");
        requireNonNull(rule, "rule is null");
        requireNonNull(specUrl, "specUrl is null");
        requireNonNull(test, "test is null");
        requireNonNull(draft, "draft is null");
        requireNonNull(workItemId, "workItemId is null");
        requireNonNull(state, "state is null");
    }

    /** Neither done nor dropped: while one is open, the goal's agent recommends no other spec check. */
    public boolean open()
    {
        return state.filter(value -> value == WorkItem.State.DONE || value == WorkItem.State.DROPPED).isEmpty();
    }
}
