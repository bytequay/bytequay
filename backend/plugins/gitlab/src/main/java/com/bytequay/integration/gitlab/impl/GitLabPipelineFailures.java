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

/** How a GitLab release explains a pipeline that failed before any job. */
interface GitLabPipelineFailures
{
    /**
     * The configuration errors of a failed pipeline, which CI Autofix repairs as one job's log.
     *
     * @param mergeRequest the request whose head pipeline it is, or empty for a branch pipeline
     */
    Optional<String> configurationErrors(HostingTarget target, OptionalInt mergeRequest, Pipeline pipeline);

    /** GitLab's own words for why the merge request's head pipeline failed, when it reports them. */
    Optional<String> reason(HostingTarget target, int mergeRequest, long pipelineId);
}
