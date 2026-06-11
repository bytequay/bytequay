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
 * @param personaPrompt per-reviewer persona prompt — flows into the
 *                     system prompt above the JSON-output rules so the
 *                     reviewer adopts the voice. Null on a regular pass
 *                     where the panel runs without personas.
 */
public record ReviewRequest(
        String repo,
        int number,
        String title,
        String body,
        String headSha,
        String diff,
        String skillContext,
        String personaPrompt)
{
    public ReviewRequest(String repo, int number, String title, String body, String headSha, String diff)
    {
        this(repo, number, title, body, headSha, diff, null, null);
    }

    public ReviewRequest(String repo, int number, String title, String body, String headSha, String diff, String skillContext)
    {
        this(repo, number, title, body, headSha, diff, skillContext, null);
    }

    /** Returns a copy with {@code personaPrompt} overridden — used when
     *  the panel runs N personas against a single base request. */
    public ReviewRequest withPersonaPrompt(String prompt)
    {
        return new ReviewRequest(repo, number, title, body, headSha, diff, skillContext, prompt);
    }
}
