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
import type { ForgeConfig } from '@electron-forge/shared-types';
import { MakerSquirrel } from '@electron-forge/maker-squirrel';
import { MakerZIP } from '@electron-forge/maker-zip';
import { MakerDeb } from '@electron-forge/maker-deb';
import { MakerRpm } from '@electron-forge/maker-rpm';
import { MakerDMG } from '@electron-forge/maker-dmg';
import { VitePlugin } from '@electron-forge/plugin-vite';
import { FusesPlugin } from '@electron-forge/plugin-fuses';
import { FuseV1Options, FuseVersion } from '@electron/fuses';
import { execSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join } from 'node:path';

// Path to the Spring Boot uber-jar produced by `mvn package`. Bundled
// into the packaged app via packagerConfig.extraResource and spawned
// at runtime by frontend/src/backendProcess.ts. Repo root is one level
// up from this config (frontend/forge.config.ts).
const BACKEND_JAR = join(__dirname, '..', 'backend', 'target', 'bytequay-backend.jar');

const config: ForgeConfig = {
  packagerConfig: {
    asar: true,
    // Product name shown in Finder, the menu bar, About box, and the
    // dock — without this Electron defaults to its own name.
    name: 'ByteQuay',
    // App icon embedded in the .app bundle. Extension is omitted on
    // purpose — electron-packager appends the right one per platform
    // (.icns on macOS, .ico on Windows, .png on Linux), so the same
    // base path works for all targets. /build/ is the conventional
    // Electron output dir and ships a Mac iconset, a Windows .ico,
    // and a fallback .png — one source for everything.
    icon: '../build/icon',
    // Ship the backend JAR alongside the renderer. extraResource
    // copies the file into Contents/Resources/ on macOS (the same
    // path process.resourcesPath resolves to at runtime).
    extraResource: [BACKEND_JAR],
  },
  hooks: {
    // Build the Spring Boot JAR before Forge tries to copy it via
    // extraResource. Skips Maven's gates here (`-DskipTests`) because
    // CI already runs the full `mvn verify` — this hook just produces
    // the artifact for packaging. Idempotent: if the JAR already
    // exists, do nothing.
    generateAssets: async () => {
      if (existsSync(BACKEND_JAR)) {
        return;
      }
      // eslint-disable-next-line no-console
      console.log('[forge] backend JAR missing — running mvn package…');
      execSync('mvn -B -q -DskipTests package', {
        cwd: join(__dirname, '..', 'backend'),
        stdio: 'inherit',
      });
    },
  },
  rebuildConfig: {},
  makers: [
    new MakerSquirrel({}),
    new MakerZIP({}, ['darwin']),
    // Native macOS installer disk image. ULFO is the modern read-only
    // compressed format — smaller download than UDZO and faster to
    // mount on Apple Silicon.
    new MakerDMG({ format: 'ULFO' }, ['darwin']),
    new MakerRpm({}),
    new MakerDeb({}),
  ],
  plugins: [
    new VitePlugin({
      // `build` can specify multiple entry builds, which can be Main process, Preload scripts, Worker process, etc.
      // If you are familiar with Vite configuration, it will look really familiar.
      build: [
        {
          // `entry` is just an alias for `build.lib.entry` in the corresponding file of `config`.
          entry: 'src/main.ts',
          config: 'vite.main.config.ts',
          target: 'main',
        },
        {
          entry: 'src/preload.ts',
          config: 'vite.preload.config.ts',
          target: 'preload',
        },
      ],
      renderer: [
        {
          name: 'main_window',
          config: 'vite.renderer.config.ts',
        },
      ],
    }),
    // Fuses are used to enable/disable various Electron functionality
    // at package time, before code signing the application
    new FusesPlugin({
      version: FuseVersion.V1,
      [FuseV1Options.RunAsNode]: false,
      [FuseV1Options.EnableCookieEncryption]: true,
      [FuseV1Options.EnableNodeOptionsEnvironmentVariable]: false,
      [FuseV1Options.EnableNodeCliInspectArguments]: false,
      [FuseV1Options.EnableEmbeddedAsarIntegrityValidation]: true,
      [FuseV1Options.OnlyLoadAppFromAsar]: true,
    }),
  ],
};

export default config;
