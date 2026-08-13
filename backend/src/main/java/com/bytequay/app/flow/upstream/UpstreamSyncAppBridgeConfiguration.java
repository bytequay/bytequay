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
package com.bytequay.app.flow.upstream;

import com.bytequay.app.domain.PR;
import com.bytequay.app.flow.timeline.TaskViews;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.PrResult;
import com.bytequay.app.service.localpr.PRSyncService;
import com.bytequay.app.service.workspaces.SyncRunStream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

import static java.lang.Math.toIntExact;

/**
 * The two places synchronization reaches outside the flow's own tables: what
 * the provider says about a pull request, and where a live turn's output goes.
 *
 * <p>Both are behind ports the flow owns, and both are wired here rather than
 * beside the run's composition — so a context that runs the flow does not have
 * to bring the app's pull-request cache or its stream with it.
 */
@Configuration(proxyBeanMethods = false)
public class UpstreamSyncAppBridgeConfiguration
{
    @Bean
    public UpstreamSyncClosureObserver newFlowUpstreamSyncClosureObserver(
            UpstreamSync upstreamSync,
            TaskViews taskViews,
            PRSyncService pullRequests)
    {
        return new UpstreamSyncClosureObserver(
                upstreamSync, taskViews,
                (repositoryId, prNumber) -> pullRequests
                        .syncExternalPR(repositoryId, toIntExact(prNumber))
                        .map(PR::status)
                        .flatMap(UpstreamSyncAppBridgeConfiguration::endedAs));
    }

    @Bean
    public RunLinePublisher newFlowUpstreamSyncRunLines(SyncRunStream stream)
    {
        return stream::publish;
    }

    /**
     * Anything else — open, draft, or a state this app does not model — is not
     * an ending, and an unread pull request is not one either.
     */
    private static Optional<PrResult> endedAs(String status)
    {
        if (PR.STATUS_MERGED.equals(status)) {
            return Optional.of(PrResult.MERGED);
        }
        return PR.STATUS_CLOSED.equals(status)
                ? Optional.of(PrResult.CLOSED) : Optional.empty();
    }
}
