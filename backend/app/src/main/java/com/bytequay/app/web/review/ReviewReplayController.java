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
package com.bytequay.app.web.review;

import com.bytequay.app.review.ReviewReplayService;
import com.bytequay.app.utils.PullRequestRefUtil;
import com.bytequay.core.review.ReviewInvalidRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static java.util.Objects.requireNonNull;

/** Holdout replays of merged pull requests' reviews. Under the agent review routes, so only the desktop session can start one. */
@RestController
@RequestMapping("/api/agent-review/replays")
public class ReviewReplayController
{
    private final ReviewReplayService replays;

    public ReviewReplayController(ReviewReplayService replays)
    {
        this.replays = requireNonNull(replays, "replays is null");
    }

    @GetMapping
    public ReplaysResponse list(@RequestParam String workspaceId)
    {
        requireNotBlank(workspaceId, "workspaceId must not be blank");
        return new ReplaysResponse(replays.list(workspaceId));
    }

    @PostMapping
    public ReviewReplayService.ReplayView start(@RequestBody StartRequest request)
    {
        requireNotBlank(request.workspaceId(), "workspaceId must not be blank");
        requireNotBlank(request.repo(), "repo must not be blank");
        if (request.number() == null) {
            throw new ReviewInvalidRequestException("number is required");
        }
        PullRequestRefUtil.parseRef(request.repo(), request.number());
        return replays.start(request.workspaceId(), request.repo(), request.number());
    }

    @PutMapping("/{replayId}/marks")
    public ReviewReplayService.ReplayView mark(@PathVariable String replayId, @RequestBody MarkRequest request)
    {
        requireNotBlank(request.workspaceId(), "workspaceId must not be blank");
        requireNotBlank(request.commentId(), "commentId must not be blank");
        if (request.memory() == null || request.raised() == null) {
            throw new ReviewInvalidRequestException("memory and raised are required");
        }
        return replays.mark(request.workspaceId(), replayId, request.commentId(), request.memory(), request.raised());
    }

    @DeleteMapping("/{replayId}")
    public ResponseEntity<Void> delete(@PathVariable String replayId, @RequestParam String workspaceId)
    {
        requireNotBlank(workspaceId, "workspaceId must not be blank");
        replays.delete(workspaceId, replayId);
        return ResponseEntity.noContent().build();
    }

    public record ReplaysResponse(List<ReviewReplayService.ReplayView> replays) {}

    public record StartRequest(String workspaceId, String repo, Integer number) {}

    /**
     * The user's call on one comment for one side of a replay.
     *
     * @param memory the side with memory when true
     */
    public record MarkRequest(String workspaceId, String commentId, Boolean memory, Boolean raised) {}
}
