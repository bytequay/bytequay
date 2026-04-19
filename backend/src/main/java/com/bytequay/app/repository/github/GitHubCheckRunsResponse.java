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

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubCheckRunsResponse(
        @JsonProperty("total_count") int totalCount,
        @JsonProperty("check_runs") List<CheckRun> checkRuns)
{
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CheckRun(
            String name,
            String status,
            String conclusion,
            @JsonProperty("html_url") String htmlUrl) {}
}
