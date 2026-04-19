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
 * Parameters for updating an existing pull request.
 * Every field is {@link Optional} because the GitHub PATCH endpoint only updates
 * the fields that are explicitly provided — {@code Optional.empty()} means
 * "leave this field unchanged", so the serializer must skip empty fields.
 * Maps to the request body of PATCH /repos/{owner}/{repo}/pulls/{pull_number}.
 *
 * @param title                new title, or empty to leave unchanged
 * @param body                 new description body, or empty to leave unchanged
 * @param state                "open" or "closed", or empty to leave unchanged
 * @param base                 new base branch name, or empty to leave unchanged
 * @param maintainerCanModify  whether to allow maintainers to push to the head branch, or empty to leave unchanged
 */
public record UpdatePullRequestCommand(
        Optional<String> title,
        Optional<String> body,
        Optional<String> state,
        Optional<String> base,
        Optional<Boolean> maintainerCanModify)
{
    public static UpdatePullRequestCommand close()
    {
        return new UpdatePullRequestCommand(
                Optional.empty(),
                Optional.empty(),
                Optional.of("closed"),
                Optional.empty(),
                Optional.empty());
    }

    public static UpdatePullRequestCommand reopen()
    {
        return new UpdatePullRequestCommand(
                Optional.empty(),
                Optional.empty(),
                Optional.of("open"),
                Optional.empty(),
                Optional.empty());
    }
}
