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
package com.bytequay.agent.core;

/** How far a development task's delivery goes. The scale is cumulative: each level includes every level before it. */
public enum AutomationLevel
{
    COMMIT,
    DRAFT_PR,
    CI_FIX,
    REVIEW_FIX,
    MERGE;

    public boolean includes(AutomationLevel step)
    {
        return compareTo(step) >= 0;
    }
}
