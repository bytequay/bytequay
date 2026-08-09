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
package com.bytequay.app.service.localpr;

import com.bytequay.app.domain.PR;
import com.bytequay.app.repository.sqlite.SqlitePRStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPrDuplicateReconciler
{
    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    private final SqlitePRStore store = mock(SqlitePRStore.class);
    private final PRService prService = mock(PRService.class);
    private final PrDuplicateReconciler reconciler = new PrDuplicateReconciler(store, prService);

    @Test
    void repairsHalfPushedReposThenFoldsEveryDuplicatePair()
    {
        PR halfPushed = new PR(
                "half", "task-x", "dev/x", "main", "T", "", PR.STATUS_REMOTE_DRAFTED, NOW,
                NOW, 30, "https://github.com/chenjian2664/ByteQuay/pull/30", null, null, null,
                PR.ORIGIN_TASK, /* repo */ null, null, null, null, null);
        when(store.findPushedTaskPrsMissingRepo()).thenReturn(List.of(halfPushed));
        when(store.findTaskPrIdsWithExternalTwin()).thenReturn(List.of("task-a", "task-b"));

        reconciler.reconcileOnStartup();

        // The repo is recovered from the URL and written back.
        verify(store).setRepo("half", "chenjian2664/ByteQuay");
        // Every duplicate pair is folded through the shared reconcile path.
        verify(prService).foldExternalTwinIntoTask("task-a");
        verify(prService).foldExternalTwinIntoTask("task-b");
    }

    @Test
    void isANoOpOnACleanDatabase()
    {
        when(store.findPushedTaskPrsMissingRepo()).thenReturn(List.of());
        when(store.findTaskPrIdsWithExternalTwin()).thenReturn(List.of());

        reconciler.reconcileOnStartup();

        verify(store, never()).setRepo(any(), any());
        verify(prService, never()).foldExternalTwinIntoTask(any());
    }

    @Test
    void repoFromUrlParsesOwnerAndRepoOnly()
    {
        assertThat(PrDuplicateReconciler.repoFromUrl(
                "https://github.com/chenjian2664/ByteQuay/pull/30"))
                .isEqualTo("chenjian2664/ByteQuay");
        assertThat(PrDuplicateReconciler.repoFromUrl(null)).isNull();
        assertThat(PrDuplicateReconciler.repoFromUrl("https://example.com/nope")).isNull();
    }
}
