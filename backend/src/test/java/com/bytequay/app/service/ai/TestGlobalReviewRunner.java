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
package com.bytequay.app.service.ai;

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.domain.ReviewOutput;
import com.bytequay.app.domain.ReviewRequest;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.local.ds4.Ds4LifecycleService;
import com.bytequay.app.service.review.CliReviewRunner;
import com.bytequay.app.service.settings.AiDefaultsService;
import com.bytequay.app.service.settings.AiDefaultsService.AiDefaults;
import com.bytequay.app.service.threads.AgentScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGlobalReviewRunner
{
    private static final String JSON = """
            {"summary":"Looks focused","comments":[{"file":"src/App.java","line":7,"severity":"critical","body":"Check this branch."}]}
            """;

    @Test
    void usesConfiguredCliThroughSchedulerAndParsesReview()
            throws Exception
    {
        AiDefaultsService defaults = defaults("cli:codex");
        CliReviewRunner cli = mock(CliReviewRunner.class);
        AgentScheduler scheduler = mock(AgentScheduler.class);
        when(scheduler.invokeCli(any())).thenAnswer(invocation ->
                ((Callable<?>) invocation.getArgument(0)).call());
        when(cli.runWithSchedulerCapacity(
                eq(CliReviewRunner.Provider.CODEX), anyString(), isNull(), any(Path.class), isNull()))
                .thenReturn(new CliReviewRunner.Result(JSON, null, 0));

        GlobalReviewRunner runner = runner(defaults, mock(CredentialService.class),
                mock(Ds4LifecycleService.class), mock(TurnRunner.class), cli, scheduler);

        ReviewOutput output = runner.review(request());

        assertThat(output.providerId()).isEqualTo("codex");
        assertThat(output.summary()).isEqualTo("Looks focused");
        assertThat(output.comments()).singleElement().satisfies(comment -> {
            assertThat(comment.file()).isEqualTo("src/App.java");
            assertThat(comment.line()).isEqualTo(7);
            assertThat(comment.severity()).isEqualTo("critical");
        });
        verify(cli).runWithSchedulerCapacity(
                eq(CliReviewRunner.Provider.CODEX),
                contains("Unified diff"),
                isNull(), any(Path.class), isNull());
    }

    @Test
    void usesConfiguredApiAccountThroughApiSchedulerLane()
            throws Exception
    {
        AiDefaultsService defaults = defaults("api:openai:work");
        CredentialService credentials = mock(CredentialService.class);
        TurnRunner turns = mock(TurnRunner.class);
        AgentScheduler scheduler = mock(AgentScheduler.class);
        when(credentials.getSecret(CredentialType.AI, "openai", "work"))
                .thenReturn(Optional.of("secret"));
        when(scheduler.invokeAll(any())).thenAnswer(invocation -> {
            List<Callable<?>> calls = invocation.getArgument(0);
            return List.of(calls.getFirst().call());
        });
        when(turns.runTurn(any(), any(), any())).thenReturn(new TurnResult(
                JSON, 100, 20, 1, 1, TurnResult.End.COMPLETED));

        GlobalReviewRunner runner = runner(defaults, credentials,
                mock(Ds4LifecycleService.class), turns, mock(CliReviewRunner.class), scheduler);

        ReviewOutput output = runner.review(request());

        assertThat(output.providerId()).isEqualTo("openai");
        assertThat(output.modelName()).isEqualTo("gpt-5");
        ArgumentCaptor<TurnSpec> spec = ArgumentCaptor.forClass(TurnSpec.class);
        verify(turns).runTurn(spec.capture(), any(), any());
        assertThat(spec.getValue().url()).isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(spec.getValue().authToken()).isEqualTo("secret");
    }

    private static GlobalReviewRunner runner(
            AiDefaultsService defaults,
            CredentialService credentials,
            Ds4LifecycleService ds4,
            TurnRunner turns,
            CliReviewRunner cli,
            AgentScheduler scheduler)
    {
        return new GlobalReviewRunner(
                defaults, credentials, ds4, turns, cli, scheduler, new ObjectMapper());
    }

    private static AiDefaultsService defaults(String globalReview)
    {
        AiDefaultsService defaults = mock(AiDefaultsService.class);
        when(defaults.get()).thenReturn(new AiDefaults(
                "cli:claude-code", "cli:claude-code", "cli:claude-code", globalReview,
                "cli:codex", "cli:claude-code", "cli:claude-code"));
        return defaults;
    }

    private static ReviewRequest request()
    {
        return new ReviewRequest(
                "owner/repo", 7, "Title", "Body", "abc123", "@@ diff", "Quick-review scope");
    }
}
