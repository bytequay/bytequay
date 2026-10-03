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
package com.bytequay.core.domain;

import java.time.Instant;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * What the user agreed a goal means, accepted from one of the agent's proposals.
 *
 * @param startingKnowledge what the user said they already know, which shapes the course; empty when they start from nothing
 * @param constraints time, scope and the way the user wants to work; empty when they named none
 * @param exchangeId the Goal exchange whose proposal was accepted
 */
public record GoalDefinition(String successDefinition, String startingKnowledge, String constraints, String exchangeId, Instant acceptedAt)
{
    public GoalDefinition
    {
        checkText(successDefinition, "successDefinition", GoalExchange.MAX_FIELD);
        requireNonNull(startingKnowledge, "startingKnowledge is null");
        requireNonNull(constraints, "constraints is null");
        checkText(exchangeId, "exchangeId");
        requireNonNull(acceptedAt, "acceptedAt is null");
    }
}
