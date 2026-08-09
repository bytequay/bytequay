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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.domain.Workspace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the {@code work_model_json} round-trip on the workspace
 * row — covers both kinds (CLI / API), the null-override case, and an
 * overwrite flow so a programmer can't accidentally regress
 * deserialisation while reshaping the {@link WorkModel} record.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestSqliteWorkspaceStore
{
    @Autowired
    private SqliteWorkspaceStore store;

    @Test
    void roundtripsACliWorkModel()
    {
        Workspace saved = newWorkspace(new WorkModel(
                WorkModelKind.CLI, "claude-code", "claude-sonnet-4-6", null));
        store.saveWorkspace(saved);

        Optional<Workspace> got = store.findWorkspaceById(saved.id());
        assertThat(got).isPresent();
        assertThat(got.get().workModel()).isEqualTo(saved.workModel());
    }

    @Test
    void roundtripsAnApiWorkModelWithNamedAccount()
    {
        Workspace saved = newWorkspace(new WorkModel(
                WorkModelKind.API, "anthropic", null, "team"));
        store.saveWorkspace(saved);

        Workspace got = store.findWorkspaceById(saved.id()).orElseThrow();
        assertThat(got.workModel()).isNotNull();
        assertThat(got.workModel().kind()).isEqualTo(WorkModelKind.API);
        assertThat(got.workModel().agentOrProvider()).isEqualTo("anthropic");
        assertThat(got.workModel().model()).isNull();
        assertThat(got.workModel().account()).isEqualTo("team");
    }

    @Test
    void roundtripsANullOverride()
    {
        Workspace saved = newWorkspace(null);
        store.saveWorkspace(saved);

        Workspace got = store.findWorkspaceById(saved.id()).orElseThrow();
        assertThat(got.workModel()).isNull();
    }

    @Test
    void overwriteReplacesThePersistedJson()
    {
        Workspace initial = newWorkspace(new WorkModel(
                WorkModelKind.CLI, "codex", null, null));
        store.saveWorkspace(initial);

        Workspace replaced = new Workspace(
                initial.id(), initial.name(), initial.memoryMd(), initial.isScratch(),
                new WorkModel(WorkModelKind.API, "openai", "gpt-5", null),
                initial.createdAt(), Instant.now());
        store.saveWorkspace(replaced);

        Workspace got = store.findWorkspaceById(initial.id()).orElseThrow();
        assertThat(got.workModel()).isEqualTo(replaced.workModel());
    }

    private static Workspace newWorkspace(WorkModel workModel)
    {
        Instant now = Instant.parse("2026-05-27T12:00:00Z");
        return new Workspace(
                "ws-" + UUID.randomUUID().toString().substring(0, 8),
                "ByteQuay",
                /* memoryMd */ "",
                /* isScratch */ false,
                workModel,
                now,
                now);
    }
}
