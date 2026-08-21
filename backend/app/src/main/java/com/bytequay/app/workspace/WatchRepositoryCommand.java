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
package com.bytequay.app.workspace;

import java.util.Optional;

/** Repository coordinates confirmed by the user plus an optional workspace display name. */
public record WatchRepositoryCommand(String owner, String repo, Optional<String> name)
{
    public WatchRepositoryCommand
    {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner is required");
        }
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("repo is required");
        }
        if (name == null) {
            throw new IllegalArgumentException("name is null");
        }
        name = name.filter(value -> !value.isBlank());
    }
}
