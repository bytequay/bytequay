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
package com.bytequay.app.service.local;

/**
 * Wraps a checked {@link java.io.IOException} from a git invocation so the
 * read surfaces that drive a git command stay one-liners instead of declaring
 * or swallowing the checked failure. The original IO error is always retained
 * as the cause.
 */
public class UncheckedGitException
        extends RuntimeException
{
    public UncheckedGitException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
