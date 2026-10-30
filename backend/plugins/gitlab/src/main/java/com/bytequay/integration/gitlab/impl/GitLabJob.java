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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Optional;

/** A pipeline job, trigger job (bridge) or commit status. */
@JsonIgnoreProperties(ignoreUnknown = true)
record GitLabJob(long id, String name, String status, @JsonProperty("web_url") String webUrl, @JsonProperty("started_at") String startedAt,
        @JsonProperty("finished_at") String finishedAt, @JsonProperty("allow_failure") boolean allowFailure, @JsonProperty("failure_reason") String failureReason)
{
    /** The shared check conclusion of a job or pipeline status, empty while it can still run; a blocking manual job holds its pipeline. */
    static Optional<String> conclusion(String status, boolean allowFailure)
    {
        return Optional.ofNullable(switch (status) {
            case "success" -> "success";
            case "failed" -> allowFailure ? "neutral" : "failure";
            case "manual" -> allowFailure ? "skipped" : null;
            case "canceled" -> "cancelled";
            case "skipped" -> "skipped";
            default -> null;
        });
    }

    /** The shared check status: queued, in_progress or completed. */
    static String state(String status, boolean allowFailure)
    {
        return conclusion(status, allowFailure).isPresent() ? "completed" : "running".equals(status) ? "in_progress" : "queued";
    }
}
