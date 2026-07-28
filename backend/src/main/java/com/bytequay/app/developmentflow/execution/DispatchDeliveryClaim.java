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
package com.bytequay.app.developmentflow.execution;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

/** Capacity-free lease for one exact RESULT_PENDING ticket version. */
public record DispatchDeliveryClaim(
        String ticketId,
        long ticketVersion,
        String claimOwner,
        Instant claimedAt,
        Instant heartbeatAt,
        Instant expiresAt)
{
    public DispatchDeliveryClaim
    {
        requireNonNull(ticketId, "ticketId is null");
        requireNonNull(claimOwner, "claimOwner is null");
        requireNonNull(claimedAt, "claimedAt is null");
        requireNonNull(heartbeatAt, "heartbeatAt is null");
        requireNonNull(expiresAt, "expiresAt is null");
        if (ticketId.isBlank() || claimOwner.isBlank()) {
            throw new IllegalArgumentException(
                    "delivery claim identity must not be blank");
        }
        if (ticketVersion < 0) {
            throw new IllegalArgumentException("ticketVersion must be non-negative");
        }
        if (heartbeatAt.isBefore(claimedAt) || !expiresAt.isAfter(heartbeatAt)) {
            throw new IllegalArgumentException("delivery claim timestamps are invalid");
        }
    }

    public boolean isExpiredAt(Instant now)
    {
        return !expiresAt.isAfter(requireNonNull(now, "now is null"));
    }

    public boolean owns(DispatchTicket ticket)
    {
        requireNonNull(ticket, "ticket is null");
        return ticketId.equals(ticket.id())
                && ticketVersion == ticket.version()
                && ticket.state() == DispatchTicket.State.RESULT_PENDING;
    }
}
