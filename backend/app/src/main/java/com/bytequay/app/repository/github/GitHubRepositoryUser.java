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
package com.bytequay.app.repository.github;

import static com.bytequay.app.utils.StringInputUtil.requireText;
import static com.google.common.base.Preconditions.checkArgument;

/** GitHub-hosted identity returned by a repository people-picker endpoint. */
public record GitHubRepositoryUser(long id, String login, String avatarUrl, boolean bot)
{
    public GitHubRepositoryUser
    {
        checkArgument(id > 0, "id is not positive");
        requireText(login, "login");
        requireText(avatarUrl, "avatarUrl");
    }
}
