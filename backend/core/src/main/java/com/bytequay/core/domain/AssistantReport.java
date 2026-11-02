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

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * What a task that a conversation started or sent words to raised since, written back into that conversation once: a decision it waits
 * on, a result to use, or why it could not finish.
 *
 * @param noticeId the task's notice it reports, which the panel draws as that notice's own card while the notice lasts
 * @param target the task, which the report opens once its notice is gone
 */
public record AssistantReport(State state, String noticeId, AgendaTarget target)
{
    public enum State
    {
        DECISION, READY, FAILED
    }

    public AssistantReport
    {
        requireNonNull(state, "state is null");
        checkText(noticeId, "noticeId");
        requireNonNull(target, "target is null");
    }
}
