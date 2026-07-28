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
package com.bytequay.app.developmentflow;

import com.bytequay.app.developmentflow.trunk.TrunkLifecycle;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.junit.jupiter.api.Test;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.INVALID_STATE;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestTrunkManager
{
    @Test
    void appliesExplicitCommandsAndDeduplicatesByCommandId()
    {
        CommandTestSupport.Trunks store = new CommandTestSupport.Trunks();
        store.put(new TrunkManager.State("trunk", TrunkLifecycle.ACTIVE, 0));
        CommandTestSupport.CountingTransactionManager transactions =
                new CommandTestSupport.CountingTransactionManager();
        TrunkManager manager = new TrunkManager(
                new TaskCommandExecutor(transactions), store);

        TrunkManager.Command idle = new TrunkManager.Command("idle", "user", "trunk", 0);
        assertThat(manager.markIdle(idle).disposition())
                .isEqualTo(CommandResult.Disposition.APPLIED);
        assertThat(manager.markIdle(idle).disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThatThrownBy(() -> manager.markIdle(
                new TrunkManager.Command("idle", "user", "trunk", 99)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                CommandRejectedException.Reason.COMMAND_ID_CONFLICT));
        assertThatThrownBy(() -> manager.archive(idle))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                CommandRejectedException.Reason.COMMAND_ID_CONFLICT));

        assertThat(manager.activate(
                new TrunkManager.Command("active", "user", "trunk", 1)).state())
                .extracting(TrunkManager.State::lifecycle, TrunkManager.State::version)
                .containsExactly(TrunkLifecycle.ACTIVE, 2L);
        assertThat(manager.archive(
                new TrunkManager.Command("archive", "user", "trunk", 2)).state())
                .extracting(TrunkManager.State::lifecycle, TrunkManager.State::version)
                .containsExactly(TrunkLifecycle.ARCHIVED, 3L);
        assertThat(transactions.begins()).isEqualTo(6);
        assertThat(transactions.commits()).isEqualTo(4);
    }

    @Test
    void rejectsIllegalAndStaleCommands()
    {
        CommandTestSupport.Trunks store = new CommandTestSupport.Trunks();
        store.put(new TrunkManager.State("trunk", TrunkLifecycle.ACTIVE, 4));
        TrunkManager manager = new TrunkManager(CommandTestSupport.executor(), store);

        assertThatThrownBy(() -> manager.activate(
                new TrunkManager.Command("already-active", "user", "trunk", 4)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(INVALID_STATE));
        assertThatThrownBy(() -> manager.markIdle(
                new TrunkManager.Command("stale", "user", "trunk", 3)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(STALE_VERSION));
    }
}
