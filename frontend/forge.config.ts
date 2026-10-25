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
import { MakerZIP } from '@electron-forge/maker-zip';
import { MakerDMG } from '@electron-forge/maker-dmg';
import { VitePlugin } from '@electron-forge/plugin-vite';
import { FusesPlugin } from '@electron-forge/plugin-fuses';
import { FuseV1Options, FuseVersion } from '@electron/fuses';
import { execFileSync, execSync } from 'node:child_process';
import { chmodSync, cpSync } from 'node:fs';
import { join } from 'node:path';

// Path to the Spring Boot uber-jar produced by `mvn package`. Bundled
// into the packaged app via packagerConfig.extraResource and spawned
// at runtime by frontend/src/backendProcess.ts. Repo root is one level
// up from this config (frontend/forge.config.ts).
const BACKEND_JAR = join(__dirname, '..', 'backend', 'app', 'target', 'bytequay-backend.jar');
const IDE_BUNDLE = join(__dirname, 'vendor', 'ide');
const JAVA_RUNTIME = join(__dirname, 'vendor', 'jre');
const LAB_RUNTIME = join(__dirname, 'vendor', 'lab');

const config: ForgeConfig = {
  packagerConfig: {
    // node-pty execs spawn-helper beside pty.node, which cannot run from inside the archive.
    asar: { unpack: '**/node_modules/node-pty/**' },
    // Product name shown in Finder, the menu bar, About box, and the
    // dock — without this Electron defaults to its own name.
    name: 'ByteQuay',
    // App icon embedded in the .app bundle. Extension is omitted on
    // purpose — electron-packager appends .icns on macOS.
    icon: '../build/icon',
    // Ship the backend JAR and runtimes alongside the renderer. extraResource
    // copies the file into Contents/Resources/ on macOS (the same
    // path process.resourcesPath resolves to at runtime).
    extraResource: [BACKEND_JAR, IDE_BUNDLE, JAVA_RUNTIME, LAB_RUNTIME],
  },
  hooks: {
    prePackage: async (_config, platform, arch) => {
      execFileSync(process.execPath, ['scripts/java-runtime.mjs', `${platform}-${arch}`], { cwd: join(__dirname, '..'), stdio: 'inherit' });
    },
    // The Vite plugin ships only its bundles, so the one runtime native module is copied in: its JS, license and
    // this platform's N-API prebuild, without sources or other platforms.
    packageAfterCopy: async (_config, buildPath, _electronVersion, platform, arch) => {
      const source = join(__dirname, 'node_modules', 'node-pty');
      const target = join(buildPath, 'node_modules', 'node-pty');
      const prebuild = join('prebuilds', `${platform}-${arch}`);
      for (const entry of ['package.json', 'LICENSE', 'lib', prebuild]) {
        cpSync(join(source, entry), join(target, entry), { recursive: true });
      }
      chmodSync(join(target, prebuild, 'spawn-helper'), 0o755);
    },
    // Clean first: another branch may have left an old JAR or deleted classes/resources in target.
    // CI runs the full backend gates separately, so packaging skips tests, Checkstyle and license checks.
    generateAssets: async (_config, platform, arch) => {
      const root = join(__dirname, '..');
      execFileSync(process.execPath, ['scripts/ide-bundle.mjs', `${platform}-${arch}`], { cwd: root, stdio: 'inherit' });
      execFileSync(process.execPath, ['scripts/lab-runtime.mjs', `${platform}-${arch}`], { cwd: root, stdio: 'inherit' });
      // eslint-disable-next-line no-console
      console.log('[forge] building backend JAR…');
      execSync('mvn -B -q -Dmaven.test.skip=true -Dcheckstyle.skip=true -Dlicense.skip=true clean package', {
        cwd: join(__dirname, '..', 'backend'),
        stdio: 'inherit',
      });
    },
  },
  // N-API prebuilds load in any Electron, so nothing is rebuilt against its headers.
  rebuildConfig: { ignoreModules: ['node-pty'] },
  makers: [
    new MakerZIP({}, ['darwin']),
    // LZMA (ULMO) makes a download about 13% smaller than LZFSE (ULFO). appdmg and macOS 14 accept it;
    // the maker's format type predates it.
    new MakerDMG({ format: 'ULMO' as 'ULFO' }, ['darwin']),
  ],
  plugins: [
    new VitePlugin({
      build: [
        {
          entry: 'src/main.ts',
          config: 'vite.main.config.ts',
          target: 'main',
        },
        {
          entry: 'src/preload.ts',
          config: 'vite.main.config.ts',
          target: 'preload',
        },
        { entry: 'src/idePreload.ts', config: 'vite.main.config.ts', target: 'preload' },
        { entry: 'src/noticePreload.ts', config: 'vite.main.config.ts', target: 'preload' },
      ],
      renderer: [
        { name: 'ide_window', config: 'vite.ide.config.ts' },
        {
          name: 'main_window',
          config: 'vite.renderer.config.ts',
        },
      ],
    }),
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
