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
package com.bytequay.app.context;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Shows retained merged work and starts learning only after an explicit candidate selection. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/repository-context/candidates")
public class ContextMergedCandidateController
{
    private final ContextMergedCandidateService candidates;

    public ContextMergedCandidateController(ContextMergedCandidateService candidates)
    {
        this.candidates = requireNonNull(candidates, "candidates is null");
    }

    @GetMapping
    public ContextMergedCandidateService.View get(@PathVariable String workspaceId)
    {
        return candidates.get(workspaceId);
    }

    @PostMapping("/learn")
    public ContextBuildService.View learn(@PathVariable String workspaceId, @RequestBody LearnRequest request)
    {
        return candidates.learn(workspaceId, new ContextMergedCandidateService.LearnRequest(
                request.candidateId(), request.requestKey(), request.expectedRevision(), request.expectedBuildSequence()));
    }

    public record LearnRequest(String candidateId, String requestKey, Long expectedRevision, Long expectedBuildSequence)
    {
        public LearnRequest
        {
            checkArgument(expectedRevision != null && expectedRevision >= 0 && expectedBuildSequence != null && expectedBuildSequence >= 0,
                    "Notebook revision and build sequence are required");
            new ContextMergedCandidateService.LearnRequest(candidateId, requestKey, expectedRevision, expectedBuildSequence);
        }
    }
}
