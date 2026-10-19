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
package com.bytequay.app.service.epic;

import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.service.ai.TextOnlyHelper;
import com.bytequay.app.service.epic.EpicDrafts.Draft;
import com.bytequay.app.service.epic.EpicDrafts.Message;
import com.bytequay.app.service.epic.EpicDrafts.Reply;
import com.bytequay.app.service.epic.EpicDrafts.Role;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestEpicDrafts
{
    private static final AgentLaneId LANE = new AgentLaneId("codex");
    private static final Message ASK = new Message(Role.USER, "Cache Iceberg catalog metadata", Optional.empty());

    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
    private final AgentDriver driver = mock(AgentDriver.class);
    private final TextOnlyHelper helper = mock(TextOnlyHelper.class);
    private final EpicDrafts drafts = new EpicDrafts(models, drivers, helper);

    @BeforeEach
    void setUp()
    {
        when(models.agentModel(AgentModelType.DEVELOPMENT, Optional.of("workspace")))
                .thenReturn(new AgentModelSetting(AgentModelType.DEVELOPMENT, true, LANE.value(), "Codex", "model", "Model", Optional.empty()));
        when(drivers.require(LANE)).thenReturn(driver);
        when(driver.capabilities()).thenReturn(AgentCapabilities.none());
        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.READY, Optional.empty(), "Ready", AgentCapabilities.none()));
    }

    @Test
    void theModelReadsTheWholeConversationWithTheDraftItShowed()
    {
        Draft shown = new Draft("Cache catalog metadata", "Cache table metadata per catalog.", "");
        when(helper.answer(any(), anyString(), eq(""), anyString(), any(Duration.class), startsWith("epic-draft:"))).thenReturn("""
                ```json
                {"message": " Creating it now. ", "draft": {"title": " Cache catalog metadata ", "description": "Cache table metadata per catalog.", "context": null},
                 "create": true}
                ```""");
        Reply reply = drafts.answer("workspace", ImmutableList.of(ASK, new Message(Role.AGENT, "Here is a draft. Create it?", Optional.of(shown)),
                new Message(Role.USER, "yes, create it", Optional.empty())));
        assertThat(reply).isEqualTo(new Reply("Creating it now.", Optional.of(shown), true));
        verify(helper).answer(any(), argThat(prompt -> prompt.contains("<conversation>") && prompt.contains("\"role\":\"AGENT\"")
                && prompt.contains("\"title\":\"Cache catalog metadata\"") && prompt.contains("yes, create it")), eq(""), anyString(), any(Duration.class), anyString());
    }

    @Test
    void theConversationEndsWithTheUsersMessage()
    {
        assertThatThrownBy(() -> drafts.answer("workspace", ImmutableList.of(ASK, new Message(Role.AGENT, "What is out of scope?", Optional.empty()))))
                .hasMessageContaining("must end with your message");
        assertThatThrownBy(() -> drafts.answer("workspace", ImmutableList.of())).hasMessageContaining("1 to 60 messages");
        verifyNoInteractions(helper);
    }

    @Test
    void proseIsAReplyWithoutADraftAndAnythingElseIsUnreadable()
    {
        assertThat(EpicDrafts.parse(" What should stay out of scope? ")).isEqualTo(new Reply("What should stay out of scope?", Optional.empty(), false));
        assertThat(EpicDrafts.parse("{\"message\": \"Which catalogs?\", \"draft\": null, \"create\": false}"))
                .isEqualTo(new Reply("Which catalogs?", Optional.empty(), false));
        assertThatThrownBy(() -> EpicDrafts.parse("{\"draft\": null}")).isInstanceOf(AgentUnavailableException.class);
        assertThatThrownBy(() -> EpicDrafts.parse("{\"message\": \"Draft\", \"draft\": {\"title\": \" \"}}")).isInstanceOf(AgentUnavailableException.class);
        assertThatThrownBy(() -> EpicDrafts.parse(" ")).isInstanceOf(AgentUnavailableException.class);
    }
}
