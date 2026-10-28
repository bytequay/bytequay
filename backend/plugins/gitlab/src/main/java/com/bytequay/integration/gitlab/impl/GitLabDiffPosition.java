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
package com.bytequay.integration.gitlab.impl;

import com.bytequay.core.domain.PrTimelinePayload;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
record GitLabDiffPosition(@JsonProperty("position_type") String type, @JsonProperty("base_sha") String baseSha, @JsonProperty("start_sha") String startSha,
        @JsonProperty("head_sha") String headSha, @JsonProperty("old_path") String oldPath, @JsonProperty("new_path") String newPath,
        @JsonProperty("old_line") Integer oldLine, @JsonProperty("new_line") Integer newLine)
{
    void applyTo(PrTimelinePayload.Builder payload, String currentHead, String currentStart)
    {
        boolean outdated = headSha == null || !headSha.equals(currentHead) || startSha == null || !startSha.equals(currentStart);
        Integer line = newLine == null ? oldLine : newLine;
        payload.path(newLine == null ? oldPath : newPath).line(outdated ? null : line).originalLine(line)
                .side(newLine == null ? "LEFT" : "RIGHT").outdated(outdated).commitSha(headSha);
    }
}
