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

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Named result for one provider commit-avatar scan. */
public record CommitAvatarSnapshot(List<CommitAvatar> avatars, boolean succeeded)
{
    public CommitAvatarSnapshot
    {
        avatars = ImmutableList.copyOf(requireNonNull(avatars, "avatars is null"));
    }

    public record CommitAvatar(
            String sha,
            Optional<String> authorLogin,
            Optional<String> authorSourceUrl,
            Optional<String> committerLogin,
            Optional<String> committerSourceUrl)
    {
        public CommitAvatar
        {
            requireNonNull(sha, "sha is null");
            checkArgument(!sha.isBlank(), "sha is blank");
            requireNonNull(authorLogin, "authorLogin is null");
            requireNonNull(authorSourceUrl, "authorSourceUrl is null");
            requireNonNull(committerLogin, "committerLogin is null");
            requireNonNull(committerSourceUrl, "committerSourceUrl is null");
        }
    }
}
