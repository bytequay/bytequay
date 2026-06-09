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
package com.bytequay.app.beans.ds4;

import java.util.List;

/**
 * Wire shape for {@code GET /api/ds4/metrics}. Memory + KV disk are
 * sampled from {@code ps} of the ds4 process; throughput, latency,
 * requests-today, and the recent-requests log come from
 * {@code Ds4Instrumentation} on the local DeepSeek RestClient.
 *
 * <p>In v1 the request log only carries calls ByteQuay made — the
 * UI must label this clearly ("ByteQuay calls only") because the
 * server is shared with external clients (the front-door proxy that
 * would also capture their traffic is a documented follow-up).
 */
public record Ds4MetricsDto(
        Memory memory,
        Throughput throughput,
        Latency latency,
        KvOnDisk kvOnDisk,
        RequestsToday requestsToday,
        List<MemorySample> memorySpark30m,
        List<RecentRequest> recentRequests)
{
    public record Memory(
            long weightsBytes,
            long kvCacheBytes,
            long freeBytes,
            long ceilingBytes,
            double pct)
    {
    }

    public record Throughput(double currentTps, double avg1mTps, double peakTodayTps)
    {
    }

    public record Latency(long firstTokenMs, long avg1mMs)
    {
    }

    public record KvOnDisk(long usedBytes, long budgetBytes, double pct)
    {
    }

    public record RequestsToday(long count, long tokensIn, long tokensOut)
    {
    }

    public record MemorySample(long atMs, long bytes)
    {
    }

    public record RecentRequest(
            long tsMs,
            String workspaceId,
            String caller,
            String route,
            long tokensIn,
            long tokensOut,
            double tps,
            String status)
    {
    }

    public static Ds4MetricsDto empty()
    {
        return new Ds4MetricsDto(
                new Memory(0L, 0L, 0L, 0L, 0.0),
                new Throughput(0.0, 0.0, 0.0),
                new Latency(0L, 0L),
                new KvOnDisk(0L, 0L, 0.0),
                new RequestsToday(0L, 0L, 0L),
                List.of(),
                List.of());
    }
}
