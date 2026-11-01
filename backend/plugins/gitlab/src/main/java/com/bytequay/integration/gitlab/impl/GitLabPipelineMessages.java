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

import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.integration.gitlab.impl.GitLabMergeRequestResponse.Pipeline;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/** Current releases explain a pipeline that failed before any job only in GraphQL; REST leaves its configuration errors empty. */
final class GitLabPipelineMessages
        implements GitLabPipelineFailures
{
    // ponytail: GitLab's own words for a configuration error (Ci::PipelinePresenter), since GraphQL names the reason only in text;
    // a changed wording turns repairs into reports until this is updated.
    private static final String CONFIGURATION_ERROR = "The pipeline failed due to an error on the CI/CD configuration file.";
    private static final String HEAD_PIPELINE = """
            query($path: ID!, $iid: String!) {
              project(fullPath: $path) {
                mergeRequest(iid: $iid) {
                  headPipeline { id failureReason errorMessages { nodes { content } } }
                }
              }
            }
            """;
    private static final String BRANCH_PIPELINE = """
            query($path: ID!, $iid: ID!) {
              project(fullPath: $path) {
                pipeline(iid: $iid) { id failureReason errorMessages { nodes { content } } }
              }
            }
            """;

    private final GitLabHttpClient http;

    GitLabPipelineMessages(GitLabHttpClient http)
    {
        this.http = requireNonNull(http, "http is null");
    }

    @Override
    public Optional<String> configurationErrors(HostingTarget target, OptionalInt mergeRequest, Pipeline pipeline)
    {
        return failure(target, mergeRequest, pipeline.id(), Optional.ofNullable(pipeline.iid()))
                .filter(failed -> CONFIGURATION_ERROR.equals(failed.failureReason()))
                .map(failed -> Optional.ofNullable(failed.errorMessages()).map(Messages::nodes).orElse(List.of()).stream()
                        .map(Message::content)
                        .filter(content -> content != null && !content.isBlank())
                        .collect(joining("\n")))
                .filter(errors -> !errors.isBlank());
    }

    @Override
    public Optional<String> reason(HostingTarget target, int mergeRequest, long pipelineId)
    {
        return failure(target, OptionalInt.of(mergeRequest), pipelineId, Optional.empty())
                .map(FailedPipeline::failureReason)
                .filter(text -> text != null && !text.isBlank());
    }

    private Optional<FailedPipeline> failure(HostingTarget target, OptionalInt mergeRequest, long pipelineId, Optional<Long> pipelineIid)
    {
        if (mergeRequest.isEmpty() && pipelineIid.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> variables = ImmutableMap.of("path", target.repository().fullPath(),
                "iid", mergeRequest.isPresent() ? Integer.toString(mergeRequest.getAsInt()) : pipelineIid.orElseThrow().toString());
        try {
            Response response = http.graphql(target.access(), mergeRequest.isPresent() ? HEAD_PIPELINE : BRANCH_PIPELINE, variables, Response.class).body().orElseThrow();
            return Optional.ofNullable(response.data())
                    .map(Response.Data::project)
                    .flatMap(project -> Optional.ofNullable(project.mergeRequest()).map(Response.MergeRequest::headPipeline).or(() -> Optional.ofNullable(project.pipeline())))
                    .filter(failed -> ("gid://gitlab/Ci::Pipeline/" + pipelineId).equals(failed.id()));
        }
        catch (HostingFailure failure) {
            // The explanation only adds context; the run still links its pipeline.
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Response(Data data)
    {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(Project project) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Project(MergeRequest mergeRequest, FailedPipeline pipeline) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record MergeRequest(FailedPipeline headPipeline) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FailedPipeline(String id, String failureReason, Messages errorMessages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Messages(List<Message> nodes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Message(String content) {}
}
