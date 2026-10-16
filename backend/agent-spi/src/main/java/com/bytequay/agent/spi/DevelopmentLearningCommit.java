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
package com.bytequay.agent.spi;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;

/** One actual commit explained by a development task's summary refinement. */
public record DevelopmentLearningCommit(String sha, String title, String summary)
{
    public static final int MAXIMUM_TITLE_LENGTH = 500;
    public static final int MAXIMUM_SUMMARY_LENGTH = 4_000;

    public DevelopmentLearningCommit
    {
        checkText(sha, "sha");
        checkArgument(sha.matches("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})"), "sha must be a full commit identifier");
        checkText(title, "title");
        checkArgument(title.length() <= MAXIMUM_TITLE_LENGTH, "title is too long");
        checkText(summary, "summary");
        checkArgument(summary.length() <= MAXIMUM_SUMMARY_LENGTH, "summary is too long");
    }
}
