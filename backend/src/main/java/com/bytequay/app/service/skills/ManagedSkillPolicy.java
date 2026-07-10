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
package com.bytequay.app.service.skills;

import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadTurn;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class ManagedSkillPolicy
{
    static final String PONYTAIL = "ponytail";
    static final String PONYTAIL_REVIEW = "ponytail-review";
    private static final String BRAIN_REVIEW_SOURCE = "brain-review";
    private static final Set<StageType> CODING_STAGE_TYPES = Set.of(
            StageType.DEVELOPMENT_STAGE,
            StageType.REMOTE_DEVELOPMENT_STAGE,
            StageType.CI_FIXING_STAGE,
            StageType.BRANCH_GUARD_STAGE);

    public List<String> skillNames(ThreadKind kind, ThreadTurn turn, StageType stageType)
    {
        if (kind == ThreadKind.BRAIN_AGENT
                && turn != null
                && turn.initiator() != null
                && BRAIN_REVIEW_SOURCE.equals(turn.initiator().source())) {
            return List.of(PONYTAIL_REVIEW);
        }
        if (stageType != null && CODING_STAGE_TYPES.contains(stageType)) {
            return List.of(PONYTAIL);
        }
        return List.of();
    }
}
