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

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Warns when a store call is slow enough to be worth looking at. Always on: it
 * costs a nanoTime pair per call and says nothing until something is wrong, the
 * same shape as Hikari's {@code leak-detection-threshold} one layer down.
 *
 * <p>Elapsed method time bundles queueing for the single pooled connection
 * together with the query itself, so a read blocked behind a writer also trips
 * this. That is the intent — both are worth knowing about.
 */
@Aspect
@Component
class StoreTimingAspect
{
    private static final Logger log = LoggerFactory.getLogger(StoreTimingAspect.class);

    private final long slowCallMillis;

    /**
     * @param slowCallMillis default sits far above the measured baseline — store
     * calls run around 1-2ms, and the worst observed under heavy read/write
     * contention was 95ms — so normal use stays silent. Lower it to find
     * something specific; the tests drive it to 0.
     */
    StoreTimingAspect(@Value("${bytequay.datasource.slow-call-millis:250}") long slowCallMillis)
    {
        this.slowCallMillis = slowCallMillis;
    }

    @Around("execution(* com.bytequay.persistence.sqlite.store.*.*(..))")
    public Object warnWhenSlow(ProceedingJoinPoint point)
            throws Throwable
    {
        long start = System.nanoTime();
        try {
            return point.proceed();
        }
        finally {
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            if (elapsedMillis > slowCallMillis) {
                log.warn("slow store call: {} took {}ms", point.getSignature().toShortString(), elapsedMillis);
            }
        }
    }
}
