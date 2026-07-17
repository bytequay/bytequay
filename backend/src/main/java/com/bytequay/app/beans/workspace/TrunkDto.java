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
package com.bytequay.app.beans.workspace;

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;

import java.util.Locale;

public record TrunkDto(
        String id,
        String workspaceId,
        String title,
        String kind,
        String status,
        String provider,
        String model,
        String prRef,
        long costUsdMilli,
        long tokensIn,
        long tokensOut,
        long createdAt,
        long updatedAt,
        Long endedAt)
{
    public static TrunkDto from(Thread thread)
    {
        return new TrunkDto(
                thread.id(),
                thread.workspaceId(),
                thread.title(),
                thread.flow() == ThreadFlow.REVIEW ? "review" : "dev",
                thread.status().name().toLowerCase(Locale.ROOT),
                thread.provider(),
                thread.model(),
                thread.prRef(),
                thread.costUsdMilli(),
                thread.tokensIn(),
                thread.tokensOut(),
                thread.createdAt().toEpochMilli(),
                thread.updatedAt().toEpochMilli(),
                thread.endedAt() == null ? null : thread.endedAt().toEpochMilli());
    }
}
