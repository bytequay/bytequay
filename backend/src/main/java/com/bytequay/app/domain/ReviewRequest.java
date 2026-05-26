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
package com.bytequay.app.domain;

/**
 * Inputs an {@link com.bytequay.app.service.ai.LlmReviewer} needs to draft a review.
 * The {@code diff} is the unified patch text for the whole PR; implementations
 * are expected to keep total input within model context limits.
 *
 * @param skillContext extra system-prompt content from a matching
 *                     rubric skill (see {@link Skill}). May be null when
 *                     no row targets the repo.
 */
public record ReviewRequest(
        String repo,
        int number,
        String title,
        String body,
        String headSha,
        String diff,
        String skillContext)
{
    public ReviewRequest(String repo, int number, String title, String body, String headSha, String diff)
    {
        this(repo, number, title, body, headSha, diff, null);
    }
}
