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
package com.bytequay.integration.google;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Account-scoped Google operations and the user's bounded, in-memory selections. */
public interface GoogleWorkspace
{
    GoogleSnapshot.Status status();

    Optional<GoogleSnapshot> snapshot();

    GoogleSnapshot.Status select(String selected);

    List<String> chatCommand(String selected);

    GoogleSnapshot.Status install();

    GoogleSnapshot.Status connect();

    GoogleSnapshot.Status connect(boolean add);

    GoogleSnapshot.Status refreshMail(String query, String pageToken);

    GoogleSnapshot.Status refreshCalendar(String calendarId, String timeMin, String timeMax);

    List<GoogleBusyTime> busyTimes(String accountId, Instant from, Instant to);

    GoogleSnapshot.Status agentAccess(boolean enabled);

    GoogleSnapshot.Status disconnect();

    GoogleSnapshot.Status cancel();

    GoogleSnapshot.Status archive(String selected, String messageId);

    GoogleSnapshot.Status send(String selected, String to, String subject, String body, String replyId);
}
