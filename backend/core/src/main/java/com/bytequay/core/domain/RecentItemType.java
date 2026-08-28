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

/** The kinds of thing a Recent touch can point at. */
public enum RecentItemType
{
    PULL_REQUEST,
    ISSUE,
    TASK,
    COMMIT;

    /** Stable prefix used to build a cross-type item key. */
    public String prefix()
    {
        return switch (this) {
            case PULL_REQUEST -> "pr";
            case ISSUE -> "issue";
            case TASK -> "task";
            case COMMIT -> "commit";
        };
    }
}
