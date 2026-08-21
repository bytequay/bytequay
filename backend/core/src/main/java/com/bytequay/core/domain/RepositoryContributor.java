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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A locally cached GitHub account associated with a monitored repository. */
public record RepositoryContributor(long id, String login, String avatarUrl, boolean bot)
{
    public RepositoryContributor
    {
        checkArgument(id > 0, "id must be positive");
        requireNonNull(login, "login is null");
        requireNonNull(avatarUrl, "avatarUrl is null");
        checkArgument(!login.isBlank(), "login is blank");
    }
}
