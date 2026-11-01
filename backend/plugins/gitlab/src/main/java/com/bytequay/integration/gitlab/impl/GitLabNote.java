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

import java.util.List;
import java.util.regex.Pattern;

/** A note created only by quick actions has no ID and reports its command results in the summary. */
@JsonIgnoreProperties(ignoreUnknown = true)
record GitLabNote(Long id, String body, GitLabUser author, @JsonProperty("created_at") String createdAt, boolean system, boolean resolved,
        GitLabDiffPosition position, List<String> summary)
{
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MARKUP = Pattern.compile("\\*\\*|\\{[+-]|[+-]\\}|`");

    /** A system note is GitLab Markdown with inline HTML and project-relative links, so the timeline shows its first line as plain text. */
    String timelineBody()
    {
        if (!system || body == null) {
            return body;
        }
        String line = body.strip().lines().findFirst().orElse("");
        line = TAG.matcher(LINK.matcher(line).replaceAll("$1")).replaceAll("");
        return MARKUP.matcher(line).replaceAll("").replaceAll("\\s+", " ").strip();
    }
}
