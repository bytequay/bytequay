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
package com.bytequay.app.service.pr;

import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.hosting.HostingFailure;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestPullRequestReadGuard
{
    private static final PullRequestRef REF = PullRequestRef.of("owner", "repo", 7);
    private final PullRequestReadGuard guard = new PullRequestReadGuard();

    @Test
    void changingAccountsRejectsThePreviousProjectionEvenWhenBothCredentialsRemainValid()
    {
        PullRequestReadGuard.Read first = guard.capture(REF, "account-a");
        PullRequestReadGuard.Read same = guard.capture(REF, "account-a");
        PullRequestReadGuard.Read unrelated = guard.capture(PullRequestRef.of("owner", "repo", 8), "account-a");
        assertThat(first.accept(() -> "same account")).isEqualTo("same account");

        PullRequestReadGuard.Read second = guard.capture(REF, "account-b");
        assertThatThrownBy(() -> first.accept(() -> "old account")).isInstanceOf(HostingFailure.class);
        assertThatThrownBy(() -> same.accept(() -> "old account")).isInstanceOf(HostingFailure.class);
        PullRequestReadGuard.Read returned = guard.capture(REF, "account-a");
        assertThatThrownBy(() -> first.accept(() -> "revived account")).isInstanceOf(HostingFailure.class);
        assertThatThrownBy(() -> second.accept(() -> "replaced account")).isInstanceOf(HostingFailure.class);
        assertThat(returned.accept(() -> "current account")).isEqualTo("current account");
        assertThat(unrelated.accept(() -> "other request")).isEqualTo("other request");
    }

    @Test
    void successfulAndFailedWritesInvalidateOnlyTheirRequest()
    {
        PullRequestReadGuard.Read before = guard.capture(REF);
        PullRequestReadGuard.Read other = guard.capture(PullRequestRef.of("owner", "repo", 8));
        guard.write(PullRequestRef.of("OWNER", "REPO", 7), () -> true);
        assertThatThrownBy(() -> before.accept(() -> "old")).isInstanceOf(HostingFailure.class);
        assertThat(other.accept(() -> "unrelated")).isEqualTo("unrelated");
        PullRequestReadGuard.Read beforeFailure = guard.capture(REF);
        IllegalStateException failure = new IllegalStateException("Response lost");
        assertThatThrownBy(() -> guard.write(REF, () -> {
            throw failure;
        })).isSameAs(failure);
        assertThatThrownBy(() -> beforeFailure.accept(() -> "old")).isInstanceOf(HostingFailure.class);
        assertThat(guard.capture(REF).accept(() -> "new")).isEqualTo("new");
    }

    @Test
    void writesInvalidateRepositoryAndGlobalDiscoveryWithoutBlockingOtherRequests()
    {
        PullRequestReadGuard.Read repository = guard.captureRepository(RepoRef.of("OWNER", "REPO"));
        PullRequestReadGuard.Read discovery = guard.captureDiscovery();
        PullRequestReadGuard.Read unrelated = guard.captureRepository(RepoRef.of("owner", "other"));
        guard.write(REF, () -> {
            assertThatThrownBy(() -> guard.captureRepository(RepoRef.of("owner", "repo"))).isInstanceOf(HostingFailure.class);
            assertThatThrownBy(guard::captureDiscovery).isInstanceOf(HostingFailure.class);
            assertThat(guard.write(PullRequestRef.of("owner", "repo", 8), () -> "other write")).isEqualTo("other write");
            return true;
        });

        assertThatThrownBy(() -> repository.accept(() -> "old list")).isInstanceOf(HostingFailure.class);
        assertThatThrownBy(() -> discovery.accept(() -> "old search")).isInstanceOf(HostingFailure.class);
        assertThat(unrelated.accept(() -> "other repository")).isEqualTo("other repository");
    }

    @Test
    void pendingWritesRejectOtherReadsWithoutHoldingTheirAcceptanceLock()
            throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var write = executor.submit(() -> guard.write(REF, () -> {
                assertThat(guard.capture(REF).accept(() -> "writer pre-read")).isEqualTo("writer pre-read");
                entered.countDown();
                try {
                    assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
                }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                return true;
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> guard.capture(REF)).isInstanceOf(HostingFailure.class);
                assertThatThrownBy(() -> guard.write(REF, () -> true)).isInstanceOf(HostingFailure.class);
            }
            finally {
                finish.countDown();
            }
            assertThat(write.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(guard.capture(REF).accept(() -> "current")).isEqualTo("current");
        }
    }
}
