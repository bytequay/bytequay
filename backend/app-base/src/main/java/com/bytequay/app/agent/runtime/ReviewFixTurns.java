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
package com.bytequay.app.agent.runtime;

import com.bytequay.agent.core.AgentTurn;

/** Turns the review fix service starts. Their message carries reviewers' words, not the user's: it authorizes no work and scopes no CI repair. */
public final class ReviewFixTurns
{
    public static final String KEY_PREFIX = "review-fix:";

    private ReviewFixTurns() {}

    public static boolean isFixTurn(AgentTurn turn)
    {
        return turn.idempotencyKey().startsWith(KEY_PREFIX);
    }
}
