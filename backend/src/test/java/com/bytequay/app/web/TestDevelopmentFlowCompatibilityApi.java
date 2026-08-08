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
package com.bytequay.app.web;

import com.bytequay.app.beans.stage.StageDto;
import com.bytequay.app.beans.stage.TaskBrainViewData;
import com.bytequay.app.beans.trace.LinkedActivePr;
import com.bytequay.app.beans.trace.MilestoneSummary;
import com.bytequay.app.beans.trace.NextPossible;
import com.bytequay.app.beans.trace.TaskTraceResponse;
import com.bytequay.app.beans.trace.TraceEvent;
import com.bytequay.app.developmentflow.compatibility.V2PrTimelineProjection;
import com.bytequay.app.developmentflow.stage.ManualPrValidationRuntime;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.localpr.PRPublishService;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.localpr.PRSyncService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.InvestigationReviewService;
import com.bytequay.app.service.stage.PlanStageService;
import com.bytequay.app.service.stage.StageDetailServiceImpl;
import com.bytequay.app.service.stage.StageServiceImpl;
import com.bytequay.app.service.stage.StageSteeringServiceImpl;
import com.bytequay.app.service.threads.TaskTraceService;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Wire-level baseline consumed by the current frontend while LEGACY Tasks
 * drain. V2 adapters may change their source records, but not these response
 * shapes or legacy labels without a coordinated frontend migration.
 */
class TestDevelopmentFlowCompatibilityApi
{
    private static final V2PrTimelineProjection IDENTITY_TIMELINE =
            new V2PrTimelineProjection()
            {
                @Override
                public List<PRTimelineEntry> project(
                        PR pr, List<PRTimelineEntry> stored)
                {
                    return stored;
                }

                @Override
                public List<PRCheck> remoteChecks(PR pr)
                {
                    return List.of();
                }
            };

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void brainAndStageRailKeepTheirCurrentShape()
            throws Exception
    {
        List<StageDto> rail = List.of(
                stage("plan-1", "PLAN_STAGE", "CLOSED", "2026-07-28T01:00:00Z", "2026-07-28T01:05:00Z"),
                stage("local-1", "DEVELOPMENT_STAGE", "CLOSED", "2026-07-28T01:05:00Z", "2026-07-28T02:00:00Z"),
                stage("remote-1", "REMOTE_DEVELOPMENT_STAGE", "OPEN", "2026-07-28T02:00:00Z", null),
                stage("cleanup-1", "CLEANUP_STAGE", "OPEN", "2026-07-28T03:00:00Z", null));
        StageServiceImpl stages = mock(StageServiceImpl.class);
        when(stages.getStages("task-1")).thenReturn(rail);
        when(stages.getBrain("task-1")).thenReturn(brain(rail));
        MockMvc mvc = standaloneSetup(stageController(stages)).build();

        JsonNode stageResponse = body(mvc.perform(get("/api/tasks/task-1/stages"))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode expectedRail = mapper.readTree("""
                [
                  {
                    "id":"plan-1","taskId":"task-1","type":"PLAN_STAGE","state":"CLOSED",
                    "openedAt":"2026-07-28T01:00:00Z","closedAt":"2026-07-28T01:05:00Z",
                    "callerStageId":null,"summary":"Plan ready","loopIteration":0
                  },
                  {
                    "id":"local-1","taskId":"task-1","type":"DEVELOPMENT_STAGE","state":"CLOSED",
                    "openedAt":"2026-07-28T01:05:00Z","closedAt":"2026-07-28T02:00:00Z",
                    "callerStageId":null,"summary":"Local work complete","loopIteration":0
                  },
                  {
                    "id":"remote-1","taskId":"task-1","type":"REMOTE_DEVELOPMENT_STAGE","state":"OPEN",
                    "openedAt":"2026-07-28T02:00:00Z","closedAt":null,
                    "callerStageId":null,"summary":"Waiting for CI","loopIteration":0
                  },
                  {
                    "id":"cleanup-1","taskId":"task-1","type":"CLEANUP_STAGE","state":"OPEN",
                    "openedAt":"2026-07-28T03:00:00Z","closedAt":null,
                    "callerStageId":null,"summary":"Cleanup pending","loopIteration":0
                  }
                ]
                """);
        assertThat(stageResponse).isEqualTo(expectedRail);

        JsonNode brainResponse = body(mvc.perform(get("/api/tasks/task-1/brain"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(brainResponse.path("stages")).isEqualTo(expectedRail);
        assertThat(brainResponse.path("task")).isEqualTo(mapper.readTree("""
                {
                  "id":"task-1","title":"Fix queue race","taskNumber":7,
                  "branch":"feature/queue-race","repoFullName":"acme/widget","prNumber":42,"prDraft":true,
                  "currentPhase":"NEEDS_ATTENTION","statusLabel":"ci fix attempts exhausted (5/5)",
                  "agentRuntime":"CLI","agentModel":"gpt-5","paused":true,"terminal":false
                }
                """));
    }

    @Test
    void taskTraceKeepsLegacyPhaseAndMilestoneFields()
            throws Exception
    {
        TaskTraceService traces = mock(TaskTraceService.class);
        when(traces.trace("task-1")).thenReturn(Optional.of(new TaskTraceResponse(
                "task-1",
                "PUSHED_AWAITING_CI",
                "WAIT_ON_PR",
                List.of(new TraceEvent(
                        1, "AWAITING_PUSH", "PUSHED_AWAITING_CI", "PUSH", "WAIT_ON_PR",
                        "AGENT", "push_approved", "2026-07-28T02:00:00Z", "Wait CI")),
                List.of(new MilestoneSummary("WAIT_ON_PR", "Wait on PR", 1, true, false, 5)),
                List.of(new NextPossible("AWAITING_READY", "Mark ready", "on CI green, still draft")),
                new LinkedActivePr(42, "PENDING", true, 0, 0, 1, List.of("octocat")))));
        MockMvc mvc = standaloneSetup(new TaskTraceController(traces)).build();

        JsonNode response = body(mvc.perform(get("/api/tasks/task-1/trace"))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(response).isEqualTo(mapper.readTree("""
                {
                  "taskId":"task-1",
                  "currentPhase":"PUSHED_AWAITING_CI",
                  "currentMilestone":"WAIT_ON_PR",
                  "events":[{
                    "n":1,"fromPhase":"AWAITING_PUSH","toPhase":"PUSHED_AWAITING_CI",
                    "fromMilestone":"PUSH","toMilestone":"WAIT_ON_PR","actor":"AGENT",
                    "reason":"push_approved","transitionedAt":"2026-07-28T02:00:00Z","label":"Wait CI"
                  }],
                  "milestoneSummary":[{
                    "milestone":"WAIT_ON_PR","label":"Wait on PR","visits":1,"active":true,
                    "skipped":false,"position":5
                  }],
                  "nextPossible":[{
                    "trigger":"AWAITING_READY","label":"Mark ready","cond":"on CI green, still draft"
                  }],
                  "linkedActivePr":{
                    "prNumber":42,"ciStatus":"PENDING","draft":true,"approvalCount":0,
                    "changesRequestedCount":0,"pendingReviewerCount":1,"requestedReviewers":["octocat"]
                  }
                }
                """));
    }

    @Test
    void timelineKeepsLocalPrivacyAndStructuredPayloadFields()
            throws Exception
    {
        Instant createdAt = Instant.parse("2026-07-28T02:30:00Z");
        PRService prs = mock(PRService.class);
        when(prs.findById("pr-1")).thenReturn(Optional.of(mock(PR.class)));
        when(prs.timeline("pr-1")).thenReturn(List.of(new PRTimelineEntry(
                "event-1", "pr-1", PRTimelineEntry.TYPE_REVIEW,
                PRTimelineEntry.ACTOR_BRAIN, true, null, createdAt,
                "{\"verdict\":\"changes_requested\",\"openFindings\":2}", null)));
        PRController controller = new PRController(
                prs, mock(PRPublishService.class), mock(PRSyncService.class), mock(TaskStore.class),
                mapper, mock(ManualPrValidationRuntime.class), mock(PullRequestService.class),
                mock(InvestigationReviewService.class), IDENTITY_TIMELINE);
        MockMvc mvc = standaloneSetup(controller).build();

        JsonNode response = body(mvc.perform(get("/api/prs/pr-1/timeline"))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(response).isEqualTo(mapper.readTree("""
                [{
                  "id":"event-1","localPrId":"pr-1","eventType":"review","actor":"brain",
                  "isLocalOnly":true,"strippedOnPushAt":null,"createdAt":%d,
                  "payload":{"verdict":"changes_requested","openFindings":2},"remoteEventId":null
                }]
                """.formatted(createdAt.toEpochMilli())));
    }

    private JsonNode body(MvcResult result)
            throws Exception
    {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private static StageController stageController(StageServiceImpl service)
    {
        return new StageController(
                service,
                mock(StageDetailServiceImpl.class),
                mock(StageSteeringServiceImpl.class),
                mock(PlanStageService.class),
                mock(StageStore.class),
                mock(TaskStore.class),
                mock(ThreadStore.class),
                mock(WorkModelResolver.class));
    }

    private static StageDto stage(
            String id, String type, String state, String openedAt, String closedAt)
    {
        String summary = switch (type) {
            case "PLAN_STAGE" -> "Plan ready";
            case "DEVELOPMENT_STAGE" -> "Local work complete";
            case "REMOTE_DEVELOPMENT_STAGE" -> "Waiting for CI";
            case "CLEANUP_STAGE" -> "Cleanup pending";
            default -> throw new IllegalArgumentException(type);
        };
        return new StageDto(
                id, "task-1", type, state, openedAt, closedAt,
                null, summary, 0);
    }

    private static TaskBrainViewData brain(List<StageDto> rail)
    {
        return new TaskBrainViewData(
                new TaskBrainViewData.BrainTask(
                        "task-1", "Fix queue race", 7, "feature/queue-race", "acme/widget",
                        42, true, "NEEDS_ATTENTION", "ci fix attempts exhausted (5/5)",
                        "CLI", "gpt-5", true, false),
                new TaskBrainViewData.Aggregate(0, 0, 0, 0, 0, 0, 0, 0, null),
                rail,
                List.of(),
                "brain-1",
                List.of(),
                new TaskBrainViewData.RightRail(
                        null, null, null, List.of(), false, null, null, null),
                new TaskBrainViewData.Scrubbers(List.of(), List.of()),
                List.of(),
                null,
                null,
                List.of(),
                null);
    }
}
