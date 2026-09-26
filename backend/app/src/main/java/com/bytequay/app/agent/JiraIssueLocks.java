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
package com.bytequay.app.agent;

import com.google.common.util.concurrent.Striped;

import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

/** Orders local Goal changes and source launch admission across workspaces and Jira connections. */
public final class JiraIssueLocks
{
    private static final Striped<Lock> LOCKS = Striped.lock(64);

    private JiraIssueLocks() {}

    public static <T> T withLock(String cloudId, String issueId, Supplier<T> action)
    {
        Lock lock = LOCKS.get(cloudId.length() + ":" + cloudId + ":" + issueId);
        lock.lock();
        try {
            return action.get();
        }
        finally {
            lock.unlock();
        }
    }
}
