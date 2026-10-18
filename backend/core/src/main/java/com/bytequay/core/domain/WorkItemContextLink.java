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

/** A contextual reference, retaining its title when the target is no longer cached; it owns no source or execution attempt. */
public record WorkItemContextLink(String targetId, String title)
{
    public WorkItemContextLink
    {
        checkText(targetId, "targetId", 200);
        checkText(title, "title", 500);
    }
}
