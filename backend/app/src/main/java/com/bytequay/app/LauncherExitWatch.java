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
package com.bytequay.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stops a packaged backend when the desktop app that launched it is gone. A Force Quit or crash skips the app's SIGTERM, and a
 * backend left running keeps backend.lock, so every later launch would fail.
 */
final class LauncherExitWatch
{
    private static final Logger log = LoggerFactory.getLogger(LauncherExitWatch.class);

    private LauncherExitWatch() {}

    static void watchLauncher()
    {
        long launcherPid = Long.getLong("bytequay.launcher-pid", 0);
        if (launcherPid == 0) {
            return;
        }
        exitWith(launcherPid, () -> {
            log.info("The desktop app (pid {}) exited; stopping the backend", launcherPid);
            System.exit(0);
        });
    }

    // ponytail: the JDK polls a non-child process every few seconds, so the backend can outlive the app by up to ~5s.
    static void exitWith(long launcherPid, Runnable exit)
    {
        ProcessHandle.of(launcherPid).ifPresentOrElse(launcher -> launcher.onExit().thenRun(exit), exit);
    }
}
