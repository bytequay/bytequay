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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record GitHubUserRepoItem(
        long id,
        String name,
        @JsonProperty("full_name") String fullName,
        Owner owner,
        String description,
        boolean fork,
        String visibility,
        Permissions permissions,
        @JsonProperty("default_branch") String defaultBranch,
        @JsonProperty("clone_url") String cloneUrl,
        Repository parent,
        Repository source)
{
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Owner(String login) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Permissions(Boolean push) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Repository(
            long id,
            String name,
            Owner owner,
            @JsonProperty("default_branch") String defaultBranch,
            @JsonProperty("clone_url") String cloneUrl) {}
}
