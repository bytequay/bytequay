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
package com.bytequay.app.team;

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record TeamMember(long githubId, String login, String name, String avatarUrl)
{
    public TeamMember
    {
        checkArgument(githubId > 0, "GitHub user ID must be positive");
        requireNotBlank(login, "GitHub login is required");
        requireNonNull(name, "name is null");
        requireNonNull(avatarUrl, "avatarUrl is null");
        checkArgument(login.length() <= 100 && name.length() <= 200 && avatarUrl.length() <= 2048, "GitHub profile fields are too long");
    }
}
