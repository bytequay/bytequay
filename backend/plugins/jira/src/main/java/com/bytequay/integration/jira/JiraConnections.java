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
package com.bytequay.integration.jira;

import java.util.List;

/** Saved Jira accounts and explicit connection changes; credentials stay behind the storage port. */
public interface JiraConnections
{
    boolean configured();

    List<JiraDtos.Connection> list();

    JiraDtos.Connection get(String id);

    JiraDtos.Connection connect();

    JiraDtos.Connection connectToken(String site, String email, String token);

    JiraDtos.Connection replaceToken(String id, String site, String email, String token);

    JiraDtos.Connection reconnect(String id);

    JiraDtos.Connection refresh(String id);

    void disconnect(String id);
}
