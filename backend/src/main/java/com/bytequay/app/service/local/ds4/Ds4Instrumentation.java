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
package com.bytequay.app.service.local.ds4;

import com.bytequay.app.beans.ds4.Ds4MetricsDto;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded ring of per-call metrics ByteQuay's own DeepSeek calls
 * record when they target the local ds4 model variant. Powers the
 * Metrics tab's throughput / latency / recent-requests tiles in v1.
 *
 * <p>External clients' traffic is explicitly invisible to this
 * subsystem; the front-door proxy that would also capture it is a
 * documented follow-up. The Metrics page surfaces this with a
 * "ByteQuay calls only" pill so the user never thinks the table is
 * a full picture of what the server is doing.
 */
@Component
public class Ds4Instrumentation
{
    /** Soft cap on the in-memory ring. ~500 fits a busy day on a
     *  single-user instance without exhausting heap. */
    static final int RING_CAPACITY = 500;
    /** Window the "tps last 1m" tile rolls over. */
    static final long ROLLUP_WINDOW_MS = 60_000L;

    private final Deque<Sample> ring = new ArrayDeque<>(RING_CAPACITY);

    /** Record one completed local-ds4 chat call. Called from
     *  {@code DeepSeekReviewer} after the response body has come
     *  back; values are computed at the call site so the
     *  instrumentation layer stays a passive sink. */
    public synchronized void record(Sample sample)
    {
        if (ring.size() >= RING_CAPACITY) {
            ring.pollFirst();
        }
        ring.addLast(sample);
    }

    /** Snapshot of the ring transformed into the metrics DTO. */
    public synchronized Ds4MetricsDto snapshot(MemoryProbe memoryProbe)
    {
        long nowMs = System.currentTimeMillis();
        long todayStart = LocalDate.now(ZoneOffset.systemDefault())
                .atStartOfDay(ZoneOffset.systemDefault())
                .toInstant()
                .toEpochMilli();

        long count = 0;
        long tokensIn = 0;
        long tokensOut = 0;
        double sumTps = 0;
        double peakTps = 0;
        long sumFirstTokenMs = 0;
        long maxFirstTokenMs = 0;
        int firstTokenSamples = 0;
        for (Sample s : ring) {
            if (s.tsMs() >= todayStart) {
                count++;
                tokensIn += s.tokensIn();
                tokensOut += s.tokensOut();
            }
            if (s.tsMs() >= nowMs - ROLLUP_WINDOW_MS) {
                sumTps += s.tps();
                sumFirstTokenMs += s.firstTokenMs();
                firstTokenSamples++;
            }
            if (s.tps() > peakTps) {
                peakTps = s.tps();
            }
            if (s.firstTokenMs() > maxFirstTokenMs) {
                maxFirstTokenMs = s.firstTokenMs();
            }
        }
        double avg1mTps = firstTokenSamples == 0 ? 0.0 : sumTps / firstTokenSamples;
        long avg1mLatency = firstTokenSamples == 0 ? 0L : sumFirstTokenMs / firstTokenSamples;
        double currentTps = ring.isEmpty() ? 0.0 : ring.peekLast().tps();
        long currentFirstToken = ring.isEmpty() ? 0L : ring.peekLast().firstTokenMs();

        List<Ds4MetricsDto.RecentRequest> recent = new ArrayList<>(ring.size());
        for (Sample s : ring) {
            recent.add(new Ds4MetricsDto.RecentRequest(
                    s.tsMs(), s.workspaceId(), s.caller(), s.route(),
                    s.tokensIn(), s.tokensOut(), s.tps(), s.status()));
        }

        MemoryProbeResult mem = memoryProbe == null ? MemoryProbeResult.unknown() : memoryProbe.sample();
        return new Ds4MetricsDto(
                new Ds4MetricsDto.Memory(
                        mem.weightsBytes(), mem.kvCacheBytes(),
                        mem.freeBytes(), mem.ceilingBytes(), mem.pct()),
                new Ds4MetricsDto.Throughput(currentTps, avg1mTps, peakTps),
                new Ds4MetricsDto.Latency(currentFirstToken, avg1mLatency),
                new Ds4MetricsDto.KvOnDisk(mem.kvDiskUsedBytes(), mem.kvDiskBudgetBytes(), mem.kvDiskPct()),
                new Ds4MetricsDto.RequestsToday(count, tokensIn, tokensOut),
                /* memorySpark30m */ List.of(),
                recent);
    }

    /** One captured call. Builders fill in what they have; missing
     *  values default to zero so the rollups stay numeric without
     *  the call site having to know the schema. */
    public record Sample(
            long tsMs,
            String workspaceId,
            String caller,
            String route,
            long tokensIn,
            long tokensOut,
            double tps,
            long firstTokenMs,
            String status)
    {
        public static Sample of(String caller, String route, long tokensIn, long tokensOut,
                double tps, long firstTokenMs, String status)
        {
            return new Sample(Instant.now().toEpochMilli(),
                    /* workspaceId */ "",
                    caller, route, tokensIn, tokensOut, tps, firstTokenMs, status);
        }
    }

    /** Pluggable memory probe — production samples {@code ps} on the
     *  ds4 PID and computes pct against the lifecycle config's KV
     *  budget. Tests pass a fixed shape. */
    public interface MemoryProbe
    {
        MemoryProbeResult sample();
    }

    public record MemoryProbeResult(
            long weightsBytes,
            long kvCacheBytes,
            long freeBytes,
            long ceilingBytes,
            double pct,
            long kvDiskUsedBytes,
            long kvDiskBudgetBytes,
            double kvDiskPct)
    {
        public static MemoryProbeResult unknown()
        {
            return new MemoryProbeResult(0, 0, 0, 0, 0.0, 0, 0, 0.0);
        }
    }
}
