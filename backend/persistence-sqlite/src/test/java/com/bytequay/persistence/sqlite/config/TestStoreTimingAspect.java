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
package com.bytequay.persistence.sqlite.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bytequay.persistence.sqlite.repository.PrCheckRunJpaRepository;
import com.bytequay.persistence.sqlite.repository.PrDetailJpaRepository;
import com.bytequay.persistence.sqlite.repository.PrReviewJpaRepository;
import com.bytequay.persistence.sqlite.repository.PrTimelineJpaRepository;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.bytequay.persistence.sqlite.store.SqlitePrDetailStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.Optional;

import static ch.qos.logback.classic.Level.WARN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The pointcut is the part that fails silently — a typo there means the warning
 * simply never fires — so these drive the real expression against the real store
 * class rather than a stand-in.
 */
class TestStoreTimingAspect
{
    private static final long SLOW_CALL_MILLIS = 5;

    private final ListAppender<ILoggingEvent> warnings = new ListAppender<>();
    private final Logger aspectLog = (Logger) LoggerFactory.getLogger(StoreTimingAspect.class);

    @BeforeEach
    void captureAspectLog()
    {
        warnings.start();
        aspectLog.addAppender(warnings);
    }

    @AfterEach
    void releaseAspectLog()
    {
        aspectLog.detachAppender(warnings);
    }

    @Test
    void testNamesTheStoreMethodWhenACallExceedsTheThreshold()
    {
        storeThatTakes(SLOW_CALL_MILLIS, 1).find(1L);

        assertThat(warnings.list)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(WARN);
                    assertThat(event.getFormattedMessage()).contains("SqlitePrDetailStore.find");
                });
    }

    @Test
    void testStaysSilentWhenTheCallIsFasterThanTheThreshold()
    {
        storeThatTakes(SLOW_CALL_MILLIS, 1_000).find(1L);

        assertThat(warnings.list).isEmpty();
    }

    /** The real store behind the real aspect, with only its repositories faked. */
    private static SqlitePrDetailStore storeThatTakes(long callMillis, long thresholdMillis)
    {
        PrDetailJpaRepository detailRepo = mock(PrDetailJpaRepository.class);
        when(detailRepo.findById(anyLong())).thenAnswer(invocation -> {
            Thread.sleep(callMillis);
            return Optional.empty();
        });
        AspectJProxyFactory factory = new AspectJProxyFactory(new SqlitePrDetailStore(
                detailRepo,
                mock(PrReviewJpaRepository.class),
                mock(PrTimelineJpaRepository.class),
                mock(PrCheckRunJpaRepository.class),
                mock(PullRequestJpaRepository.class)));
        factory.setProxyTargetClass(true);
        factory.addAspect(new StoreTimingAspect(thresholdMillis));
        return factory.getProxy();
    }
}
