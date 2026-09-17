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

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;

/** A semantic task title and its independently worded, Git-safe branch. */
public record TaskNames(String title, String branch)
{
    public TaskNames
    {
        checkNotBlank(title, "title");
        checkNotBlank(branch, "branch");
        checkArgument(title.equals(TaskTitles.generated(title)), "title must be compact plain text");
        checkArgument(branch.length() <= 64 && TaskBranches.isCleanName(branch), "branch must be a short hyphenated name");
    }
}
