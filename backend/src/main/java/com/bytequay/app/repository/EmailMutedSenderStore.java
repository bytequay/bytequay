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
package com.bytequay.app.repository;

import java.time.Instant;
import java.util.List;

/**
 * Persistence boundary for the per-account email mute list. Keeps the
 * JPA entity package-private inside {@code repository.sqlite} the same
 * way {@link EmailMessageStore} does for its rows.
 */
public interface EmailMutedSenderStore
{
    /** Adds a mute. Idempotent — re-muting refreshes the timestamp. */
    void mute(String accountEmail, String senderEmail, Instant mutedAt);

    /** Removes a mute. No-op when the row doesn't exist. */
    void unmute(String accountEmail, String senderEmail);

    /** Returns the muted addresses for an account, undefined order. */
    List<String> listMuted(String accountEmail);
}
