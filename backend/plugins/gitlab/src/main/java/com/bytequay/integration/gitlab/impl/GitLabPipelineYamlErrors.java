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

import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.integration.gitlab.impl.GitLabMergeRequestResponse.Pipeline;

import java.util.Optional;
import java.util.OptionalInt;

import static java.util.Objects.requireNonNull;

/** Older releases put a pipeline's configuration errors in its REST representation and give no other failure reason. */
final class GitLabPipelineYamlErrors
        implements GitLabPipelineFailures
{
    private final GitLabHttpClient http;

    GitLabPipelineYamlErrors(GitLabHttpClient http)
    {
        this.http = requireNonNull(http, "http is null");
    }

    @Override
    public Optional<String> configurationErrors(HostingTarget target, OptionalInt mergeRequest, Pipeline pipeline)
    {
        // The pipeline is read again because lists and merge requests may leave the errors out.
        Pipeline current = http.get(target.access(), pipeline.path(target.repository()), Optional.empty(), Pipeline.class).body().orElseThrow();
        return Optional.ofNullable(current.yamlErrors()).filter(errors -> !errors.isBlank());
    }

    @Override
    public Optional<String> reason(HostingTarget target, int mergeRequest, long pipelineId)
    {
        return Optional.empty();
    }
}
