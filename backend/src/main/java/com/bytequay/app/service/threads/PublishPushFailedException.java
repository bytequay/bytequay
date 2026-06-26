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
package com.bytequay.app.service.threads;

/**
 * Thrown when a publish's {@code git push} step fails — i.e. the branch never
 * reached the remote. Distinct from an ordinary failure so the approve gate
 * can release its resolution claim back to {@code UNREAD} for a clean retry
 * instead of pinning it {@code RESOLVING}: a failed push, unlike a failure
 * after the push succeeds, leaves no remote state, so re-approving can't
 * double-publish.
 */
public class PublishPushFailedException extends RuntimeException
{
    public PublishPushFailedException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
