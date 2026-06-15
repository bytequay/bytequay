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
package com.bytequay.app.domain;

/**
 * Who drove a task-phase transition — recorded on each
 * {@code task_phase_event} row. {@link #AGENT} and {@link #SCHEDULER}
 * are the "auto" actors the consecutive-auto-push cap guards against;
 * {@link #HUMAN} resets that cap.
 */
public enum Actor
{
    AGENT,
    HUMAN,
    WEBHOOK,
    SCHEDULER;

    /** True for actors that count toward the consecutive-auto-push cap
     *  (i.e. not a human-initiated action). */
    public boolean isAuto()
    {
        return this == AGENT || this == SCHEDULER;
    }
}
