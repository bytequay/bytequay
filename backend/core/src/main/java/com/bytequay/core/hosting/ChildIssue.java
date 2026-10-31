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
package com.bytequay.core.hosting;

import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * An issue its provider lists as a child of another, such as a GitHub sub-issue or a GitLab child task.
 *
 * @param id the provider's id of the issue
 * @param repository the path of the repository the child lives in, which may differ from its parent's
 */
public record ChildIssue(long id, int number, String title, boolean closed, Optional<String> url, String repository)
{
    public ChildIssue
    {
        checkArgument(id > 0, "id must be positive");
        checkArgument(number > 0, "number must be positive");
        requireNonNull(title, "title is null");
        requireNonNull(url, "url is null");
        checkText(repository, "repository");
    }
}
