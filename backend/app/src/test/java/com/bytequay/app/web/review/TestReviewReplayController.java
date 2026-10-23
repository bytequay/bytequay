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
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestReviewReplayController
{
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final ReviewReplayService.ReplayView VIEW = new ReviewReplayService.ReplayView("replay-1", 31201, "Add a property",
            "https://github.com/owner/repository/pull/31201", "a".repeat(40), Optional.empty(), NOW, "bo", 1, NOW,
            ImmutableList.of(new ReviewReplayService.SideView(true, "review-with", "DONE", Optional.empty(), false, ImmutableList.of(
                    new ReviewReplayService.FindingView("finding-1", "F-1", "Missing documentation", Optional.of("Medium"), Optional.of("plugin/x/A.java"),
                            Optional.of("RIGHT"), Optional.of(15), Optional.of(16))))),
            ImmutableList.of(new ReviewReplayService.CommentView("note-1", "bo", "plugin/x/A.java", "RIGHT", Optional.of(14), Optional.empty(), true,
                    "Please document it.", "https://github.com/owner/repository/pull/31201#discussion_r1", NOW,
                    ImmutableList.of(new ReviewReplayService.Mark(true, ImmutableList.of("finding-1"), true, false)))));

    private final ReviewReplayService replays = mock(ReviewReplayService.class);
    private final MockMvc mvc = standaloneSetup(new ReviewReplayController(replays)).build();

    @Test
    void startsListsMarksAndDeletesReplays()
            throws Exception
    {
        when(replays.start("workspace", "owner/repository", 31201)).thenReturn(VIEW);
        mvc.perform(post("/api/agent-review/replays").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceId\":\"workspace\",\"repo\":\"owner/repository\",\"number\":31201}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sides[0].findings[0].startLine").value(15))
                .andExpect(jsonPath("$.comments[0].marks[0].findings[0]").value("finding-1"))
                .andExpect(jsonPath("$.comments[0].onReplayedHead").value(true));

        when(replays.list("workspace")).thenReturn(ImmutableList.of(VIEW));
        mvc.perform(get("/api/agent-review/replays").param("workspaceId", "workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replays[0].reviewer").value("bo"));

        when(replays.mark("workspace", "replay-1", "note-1", false, true)).thenReturn(VIEW);
        mvc.perform(put("/api/agent-review/replays/replay-1/marks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceId\":\"workspace\",\"commentId\":\"note-1\",\"memory\":false,\"raised\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("replay-1"));

        mvc.perform(delete("/api/agent-review/replays/replay-1").param("workspaceId", "workspace"))
                .andExpect(status().isNoContent());
        verify(replays).delete("workspace", "replay-1");
    }
}
