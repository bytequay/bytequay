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
package com.bytequay.app.service;

import com.bytequay.app.domain.IssueOrigin;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.IssueOriginStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestIssueOriginService
{
    private static final RepoRef BYTEQUAY = RepoRef.of("bytequay", "bytequay");
    private static final RepoRef OTHER_REPO = RepoRef.of("acme", "widget");

    @Test
    void persistedOriginWinsAfterTheRemoteMarkerChanges()
    {
        IssueOriginStore store = mock(IssueOriginStore.class);
        when(store.find(41L)).thenReturn(Optional.of(IssueOrigin.QUALITY_SCAN));

        RepoIssue attributed = new IssueOriginService(store)
                .attribute(BYTEQUAY, issue("chenjian2664", IssueOrigin.USER));

        assertThat(attributed.origin()).isEqualTo(IssueOrigin.QUALITY_SCAN);
        verify(store, never()).saveIfAbsent(41L, 7, IssueOrigin.USER);
    }

    @Test
    void persistedQualityScanOriginWinsForAnyRepository()
    {
        IssueOriginStore store = mock(IssueOriginStore.class);
        when(store.find(41L)).thenReturn(Optional.of(IssueOrigin.QUALITY_SCAN));

        RepoIssue attributed = new IssueOriginService(store)
                .attribute(OTHER_REPO, issue("automation", IssueOrigin.USER));

        assertThat(attributed.origin()).isEqualTo(IssueOrigin.QUALITY_SCAN);
    }

    @Test
    void recordsCreatedQualityIssueBeforeAttributingIt()
    {
        IssueOriginStore store = mock(IssueOriginStore.class);
        RepoIssue created = issue("automation", IssueOrigin.USER);

        RepoIssue attributed = new IssueOriginService(store)
                .recordCreated(created, IssueOrigin.QUALITY_SCAN);

        assertThat(attributed.origin()).isEqualTo(IssueOrigin.QUALITY_SCAN);
        verify(store).saveIfAbsent(41L, 7, IssueOrigin.QUALITY_SCAN);
    }

    @Test
    void onlyTheTrustedAuthorCanSupplyAnAutomationMarker()
    {
        IssueOriginStore store = mock(IssueOriginStore.class);
        when(store.find(41L)).thenReturn(Optional.empty());

        RepoIssue attributed = new IssueOriginService(store)
                .attribute(BYTEQUAY, issue("external-reporter", IssueOrigin.QUALITY_SCAN));

        assertThat(attributed.origin()).isEqualTo(IssueOrigin.USER);
        verify(store).saveIfAbsent(41L, 7, IssueOrigin.USER);
    }

    @Test
    void omittedListBodiesStayUnknownUntilAFullIssueIsRead()
    {
        IssueOriginStore store = mock(IssueOriginStore.class);
        when(store.find(41L)).thenReturn(Optional.empty());

        RepoIssue attributed = new IssueOriginService(store)
                .attribute(BYTEQUAY, issue("chenjian2664", IssueOrigin.UNKNOWN));

        assertThat(attributed.origin()).isEqualTo(IssueOrigin.UNKNOWN);
        verify(store, never()).saveIfAbsent(41L, 7, IssueOrigin.UNKNOWN);
    }

    private static RepoIssue issue(String author, String origin)
    {
        return new RepoIssue(
                41L, 7, "Finding", author, "open",
                "https://github.com/bytequay/bytequay/issues/7",
                Instant.EPOCH, List.of(), 0, origin);
    }
}
