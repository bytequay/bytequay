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

import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record UserRepo(
        long id,
        String owner,
        String name,
        String fullName,
        Optional<String> description,
        boolean fork,
        String defaultBranch,
        String cloneUrl,
        Optional<Upstream> upstream)
{
    public UserRepo
    {
        requireNonNull(description, "description is null");
        requireNonNull(upstream, "upstream is null");
    }

    public record Upstream(
            long id,
            String owner,
            String name,
            String defaultBranch,
            String cloneUrl)
    {}
}
