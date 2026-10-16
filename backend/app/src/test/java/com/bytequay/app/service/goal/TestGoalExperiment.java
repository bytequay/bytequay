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
package com.bytequay.app.service.goal;

import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.web.GoalController;
import com.bytequay.core.store.GoalExchangeStore;
import com.bytequay.core.store.GoalRecommendationStore;
import com.bytequay.core.store.GoalTopicStore;
import com.bytequay.core.store.WorkspaceStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TestGoalExperiment
{
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(GoalController.class, GoalConversationService.class, GoalDiscovery.class)
            .withBean(GoalService.class, () -> mock(GoalService.class))
            .withBean(WorkItemService.class, () -> mock(WorkItemService.class))
            .withBean(GoalExchangeStore.class, () -> mock(GoalExchangeStore.class))
            .withBean(GoalRecommendationStore.class, () -> mock(GoalRecommendationStore.class))
            .withBean(WorkspaceStore.class, () -> mock(WorkspaceStore.class))
            .withBean(GoalCandidates.class, () -> mock(GoalCandidates.class))
            .withBean(GoalQuestions.class, () -> mock(GoalQuestions.class))
            .withBean(GoalTopicStore.class, () -> mock(GoalTopicStore.class))
            .withBean(ModelSettingsService.class, () -> mock(ModelSettingsService.class))
            .withBean(GoalPlanner.class, () -> mock(GoalPlanner.class))
            .withBean(GoalPages.class, () -> mock(GoalPages.class))
            .withBean(AppSettingsService.class, () -> mock(AppSettingsService.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(GoalResearchService.class, () -> mock(GoalResearchService.class))
            .withBean(GoalLessonService.class, () -> mock(GoalLessonService.class))
            .withBean(GoalSpecChecks.class, () -> mock(GoalSpecChecks.class));

    @Test
    void disablesGoalEndpointsAndGuidanceByDefault()
    {
        context.run(application -> assertThat(application)
                .doesNotHaveBean(GoalController.class)
                .doesNotHaveBean(GoalConversationService.class)
                .doesNotHaveBean(GoalDiscovery.class));
        context.withPropertyValues("bytequay.experiments.goals=false").run(application -> assertThat(application)
                .doesNotHaveBean(GoalController.class)
                .doesNotHaveBean(GoalConversationService.class)
                .doesNotHaveBean(GoalDiscovery.class));
    }

    @Test
    void enablesGoalsForDevelopment()
    {
        context.withPropertyValues("bytequay.experiments.goals=true").run(application -> assertThat(application)
                .hasSingleBean(GoalController.class)
                .hasSingleBean(GoalConversationService.class)
                .hasSingleBean(GoalDiscovery.class));
    }
}
