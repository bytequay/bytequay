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
package com.bytequay.app.service.recent;

import com.bytequay.core.domain.RecentItemType;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** One resolved item, independent of the remote API that supplied it. */
public record RecentItem(
        RecentItemType type,
        String itemKey,
        Optional<Integer> number,
        String title,
        Optional<String> repo,
        Optional<String> workspaceId,
        Optional<String> htmlUrl,
        boolean mine,
        Optional<Long> prId,
        Optional<String> subtitle)
{
    public RecentItem
    {
        requireNonNull(type, "type is null");
        requireNonNull(itemKey, "itemKey is null");
        requireNonNull(number, "number is null");
        requireNonNull(title, "title is null");
        requireNonNull(repo, "repo is null");
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(htmlUrl, "htmlUrl is null");
        requireNonNull(prId, "prId is null");
        requireNonNull(subtitle, "subtitle is null");
    }
}
