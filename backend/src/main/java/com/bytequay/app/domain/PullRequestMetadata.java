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

import java.util.List;

import static java.util.Objects.requireNonNull;

/** Labels and assignees read from GitHub's issue-shaped pull-request payload. */
public record PullRequestMetadata(List<Label> labels, List<String> assignees)
{
    public PullRequestMetadata
    {
        labels = List.copyOf(requireNonNull(labels, "labels is null"));
        assignees = List.copyOf(requireNonNull(assignees, "assignees is null"));
    }

    public record Label(String name, String color) {}
}
