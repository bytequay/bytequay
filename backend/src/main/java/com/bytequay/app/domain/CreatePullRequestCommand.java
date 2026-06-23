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

import java.util.Optional;

/**
 * Parameters for opening a new pull request.
 * Maps to the request body of POST /repos/{owner}/{repo}/pulls.
 *
 * @param head                 branch (or SHA) containing the changes, in the form "owner:branch" (required)
 * @param base                 branch the changes should be merged into (required)
 * @param title                title of the pull request (required)
 * @param body                 body text of the pull request; empty omits the field
 * @param draft                true to create as a draft pull request; empty defaults to false
 * @param maintainerCanModify  true to allow maintainers to push to the head branch; empty defaults to true
 */
public record CreatePullRequestCommand(
        String head,
        String base,
        String title,
        Optional<String> body,
        Optional<Boolean> draft,
        Optional<Boolean> maintainerCanModify)
{
    public static CreatePullRequestCommand of(String head, String base, String title)
    {
        return new CreatePullRequestCommand(head, base, title, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static CreatePullRequestCommand draft(String head, String base, String title)
    {
        return new CreatePullRequestCommand(head, base, title, Optional.empty(), Optional.of(true), Optional.empty());
    }

    public static CreatePullRequestCommand draft(String head, String base, String title, String body)
    {
        return new CreatePullRequestCommand(
                head,
                base,
                title,
                body == null || body.isBlank() ? Optional.empty() : Optional.of(body),
                Optional.of(true),
                Optional.empty());
    }
}
