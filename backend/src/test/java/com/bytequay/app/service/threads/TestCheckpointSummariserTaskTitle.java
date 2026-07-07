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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.CredentialService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CheckpointSummariser#summariseTaskTitle} is best-effort — unlike
 * the class's other Anthropic-calling methods, it must swallow failures
 * rather than throw, since a title-polish failure must never block task
 * creation. This pins that contract for the cheapest failure to trigger:
 * no Anthropic credential configured at all.
 */
class TestCheckpointSummariserTaskTitle
{
    @Test
    void returnsNullWhenNoCredentialConfigured()
    {
        CredentialService credentials = Mockito.mock(CredentialService.class);
        Mockito.when(credentials.getSecret(CredentialType.AI, "anthropic")).thenReturn(Optional.empty());
        CheckpointSummariser summariser = new CheckpointSummariser(
                Mockito.mock(RestClient.class),
                credentials,
                Mockito.mock(ThreadStore.class),
                new ObjectMapper());

        assertThat(summariser.summariseTaskTitle("some long task description")).isNull();
    }
}
