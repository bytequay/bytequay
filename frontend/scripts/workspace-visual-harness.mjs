import { app, BrowserWindow, nativeImage } from 'electron';
import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

const FRAME_IDS = [
  '1c',
  '2a', '2b',
  '3a', '3b', '3c', '3d', '3e', '3f', '3g', '3h', '3i', '3j',
  '4a', '4b', '4c', '4d', '4e', '4f',
  '5a', '5b', '5c', '5d', '5e', '5f',
  '6a', '6b', '6c', '6d',
];
const MAX_ANTI_ALIAS_COMPONENT_DIMENSION = 256;
const MAX_ANTI_ALIAS_COMPONENT_PIXELS = 4096;

function argumentsByName(argv) {
  const values = new Map();
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith('--')) continue;
    const next = argv[index + 1];
    values.set(token.slice(2), next?.startsWith('--') ? 'true' : next ?? 'true');
    if (next !== undefined && !next.startsWith('--')) index += 1;
  }
  return values;
}

function required(values, name) {
  const value = values.get(name);
  if (value === undefined || value === 'true') {
    throw new Error(`--${name} is required`);
  }
  return value;
}

async function createWindow() {
  const window = new BrowserWindow({
    show: false,
    width: 1440,
    height: 880,
    useContentSize: true,
    backgroundColor: '#e9ebee',
    webPreferences: {
      backgroundThrottling: false,
      sandbox: true,
    },
  });
  window.setContentSize(1440, 880);
  window.webContents.on('console-message', (_event, level, message) => {
    if (level >= 2) process.stderr.write(`renderer: ${message}\n`);
  });
  return window;
}

async function loadPage(window, url) {
  const domReady = new Promise((resolve, reject) => {
    const timeout = setTimeout(
      () => reject(new Error(`timed out loading ${url}`)),
      15000,
    );
    window.webContents.once('dom-ready', () => {
      clearTimeout(timeout);
      resolve();
    });
  });
  void window.loadURL(url).catch(() => {});
  await domReady;
}

async function waitForDesign(window) {
  await window.webContents.executeJavaScript(`
    new Promise((resolve, reject) => {
      const deadline = Date.now() + 15000;
      const poll = () => {
        if (document.querySelector('[data-screen-label]')) {
          resolve();
          return;
        }
        if (Date.now() >= deadline) {
          reject(new Error('design frames did not render'));
          return;
        }
        setTimeout(poll, 50);
      };
      poll();
    })
  `);
}

async function isolateDesignFrame(window, frameId) {
  return window.webContents.executeJavaScript(`
    (() => {
      const frameId = ${JSON.stringify(frameId)};
      const frames = Array.from(document.querySelectorAll('[data-screen-label]'));
      const target = frames.find((element) => {
        const label = element.getAttribute('data-screen-label') || '';
        return label === frameId || label.startsWith(frameId + ' ');
      });
      if (!target) {
        throw new Error('missing design frame ' + frameId);
      }
      const label = target.getAttribute('data-screen-label') || frameId;
      document.documentElement.style.cssText =
        'margin:0!important;padding:0!important;width:1440px!important;height:880px!important;overflow:hidden!important;background:#e9ebee!important;';
      document.body.style.cssText =
        'margin:0!important;padding:0!important;width:1440px!important;height:880px!important;overflow:hidden!important;background:#e9ebee!important;';
      document.body.replaceChildren(target);
      target.style.position = 'fixed';
      target.style.inset = '0 auto auto 0';
      target.style.margin = '0';
      target.style.transform = 'none';
      const freeze = document.createElement('style');
      freeze.textContent =
        '*,*::before,*::after{animation:none!important;transition:none!important;caret-color:transparent!important}';
      document.head.appendChild(freeze);
      return label;
    })()
  `);
}

async function captureWindow(window, outputPath) {
  await window.webContents.executeJavaScript(
    'document.fonts && document.fonts.ready ? document.fonts.ready : Promise.resolve()',
  );
  await new Promise((resolve) => setTimeout(resolve, 80));
  const image = await window.webContents.capturePage({
    x: 0,
    y: 0,
    width: 1440,
    height: 880,
  });
  await fs.mkdir(path.dirname(outputPath), { recursive: true });
  await fs.writeFile(outputPath, image.toPNG());
}

async function waitForProduction(window) {
  await window.webContents.executeJavaScript(`
    new Promise((resolve, reject) => {
      const deadline = Date.now() + 15000;
      const poll = () => {
        if (document.querySelector('[data-workspace-visual-ready="true"]')) {
          resolve();
          return;
        }
        if (Date.now() >= deadline) {
          reject(new Error('production fixture did not become ready'));
          return;
        }
        setTimeout(poll, 50);
      };
      poll();
    })
  `);
  await window.webContents.executeJavaScript(`
    (() => {
      const freeze = document.createElement('style');
      freeze.textContent =
        '*,*::before,*::after{animation:none!important;transition:none!important;caret-color:transparent!important}';
      document.head.appendChild(freeze);
    })()
  `);
}

async function extractReferences(values) {
  const source = path.resolve(required(values, 'source'));
  const output = path.resolve(required(values, 'out'));
  const selected = values.has('frames')
    ? required(values, 'frames').split(',').map((value) => value.trim()).filter(Boolean)
    : FRAME_IDS;
  const manifest = [];
  const window = await createWindow();
  try {
    for (const frameId of selected) {
      await loadPage(window, pathToFileURL(source).href);
      await waitForDesign(window);
      const label = await isolateDesignFrame(window, frameId);
      const filename = `${frameId}.png`;
      await captureWindow(window, path.join(output, filename));
      manifest.push({ id: frameId, label, filename, width: 1440, height: 880 });
      process.stdout.write(`captured ${frameId}: ${label}\n`);
    }
  }
  finally {
    window.destroy();
  }

  await fs.writeFile(
    path.join(output, 'frames.json'),
    `${JSON.stringify({ source: path.basename(source), frames: manifest }, null, 2)}\n`,
  );
}

async function inspectReferences(values) {
  const source = path.resolve(required(values, 'source'));
  const window = await createWindow();
  try {
    await loadPage(window, pathToFileURL(source).href);
    await waitForDesign(window);
    const frames = await window.webContents.executeJavaScript(`
      Array.from(document.querySelectorAll('[data-screen-label]')).map((element) => {
        const rect = element.getBoundingClientRect();
        const style = getComputedStyle(element);
        return {
          label: element.getAttribute('data-screen-label'),
          tag: element.tagName.toLowerCase(),
          width: rect.width,
          height: rect.height,
          background: style.background,
          border: style.border,
          borderRadius: style.borderRadius,
          boxShadow: style.boxShadow,
        };
      })
    `);
    process.stdout.write(`${JSON.stringify(frames, null, 2)}\n`);
  }
  finally {
    window.destroy();
  }
}

async function measureReference(values) {
  const source = path.resolve(required(values, 'source'));
  const frameId = required(values, 'frame');
  const contains = required(values, 'contains');
  const window = await createWindow();
  try {
    await loadPage(window, pathToFileURL(source).href);
    await waitForDesign(window);
    const measurements = await window.webContents.executeJavaScript(`
      (() => {
        const frameId = ${JSON.stringify(frameId)};
        const needle = ${JSON.stringify(contains)}.toLowerCase();
        const target = Array.from(document.querySelectorAll('[data-screen-label]')).find((element) => {
          const label = element.getAttribute('data-screen-label') || '';
          return label === frameId || label.startsWith(frameId + ' ');
        });
        if (!target) throw new Error('missing design frame ' + frameId);
        const matches = Array.from(target.querySelectorAll('*')).filter((element) =>
          (element.textContent || '').trim().toLowerCase().includes(needle));
        const selected = new Set([target]);
        for (const match of matches.slice(0, 12)) {
          let current = match;
          while (current && target.contains(current)) {
            selected.add(current);
            if (current === target) break;
            current = current.parentElement;
          }
        }
        const all = [target, ...target.querySelectorAll('*')];
        return all.filter((element) => selected.has(element)).map((element) => {
          const rect = element.getBoundingClientRect();
          const style = getComputedStyle(element);
          const directText = Array.from(element.childNodes)
            .filter((node) => node.nodeType === Node.TEXT_NODE)
            .map((node) => node.textContent || '')
            .join(' ')
            .replace(/\\s+/g, ' ')
            .trim();
          return {
            depth: (() => {
              let depth = 0;
              let current = element;
              while (current !== target && current.parentElement) {
                depth += 1;
                current = current.parentElement;
              }
              return depth;
            })(),
            tag: element.tagName.toLowerCase(),
            directText,
            rect: {
              x: rect.x, y: rect.y, width: rect.width, height: rect.height,
            },
            display: style.display,
            position: style.position,
            padding: style.padding,
            margin: style.margin,
            gap: style.gap,
            gridTemplateColumns: style.gridTemplateColumns,
            font: style.font,
            color: style.color,
            background: style.background,
            border: style.border,
            borderRadius: style.borderRadius,
          };
        });
      })()
    `);
    process.stdout.write(`${JSON.stringify(measurements, null, 2)}\n`);
  }
  finally {
    window.destroy();
  }
}

async function captureProduction(values) {
  const url = required(values, 'url');
  const output = path.resolve(required(values, 'out'));
  const window = await createWindow();
  try {
    await loadPage(window, url);
    await waitForProduction(window);
    await captureWindow(window, output);
  }
  finally {
    window.destroy();
  }
}

async function captureProductionSuite(values) {
  const urlTemplate = required(values, 'url-template');
  const output = path.resolve(required(values, 'out'));
  const selected = values.has('frames')
    ? required(values, 'frames').split(',').map((value) => value.trim()).filter(Boolean)
    : FRAME_IDS;
  const window = await createWindow();
  try {
    for (const frameId of selected) {
      const url = urlTemplate.replace('{frame}', encodeURIComponent(frameId));
      await loadPage(window, url);
      await waitForProduction(window);
      await captureWindow(window, path.join(output, `${frameId}.png`));
      process.stdout.write(`captured production ${frameId}\n`);
    }
  }
  finally {
    window.destroy();
  }
}

async function measureProduction(values) {
  const url = required(values, 'url');
  const contains = required(values, 'contains');
  const window = await createWindow();
  try {
    await loadPage(window, url);
    await window.webContents.executeJavaScript(`
      new Promise((resolve, reject) => {
        const deadline = Date.now() + 15000;
        const poll = () => {
          if (document.querySelector('[data-workspace-visual-ready="true"]')) return resolve();
          if (Date.now() >= deadline) return reject(new Error('production fixture did not become ready'));
          setTimeout(poll, 50);
        };
        poll();
      })
    `);
    const measurements = await window.webContents.executeJavaScript(`
      (() => {
        const needle = ${JSON.stringify(contains)}.toLowerCase();
        const target = document.querySelector('.workspace-visual-canvas');
        if (!target) throw new Error('missing production visual canvas');
        const matches = Array.from(target.querySelectorAll('*')).filter((element) =>
          (element.textContent || '').trim().toLowerCase().includes(needle));
        const selected = new Set([target]);
        for (const match of matches.slice(0, 12)) {
          let current = match;
          while (current && target.contains(current)) {
            selected.add(current);
            if (current === target) break;
            current = current.parentElement;
          }
        }
        const all = [target, ...target.querySelectorAll('*')];
        return all.filter((element) => selected.has(element)).map((element) => {
          const rect = element.getBoundingClientRect();
          const style = getComputedStyle(element);
          const directText = Array.from(element.childNodes)
            .filter((node) => node.nodeType === Node.TEXT_NODE)
            .map((node) => node.textContent || '')
            .join(' ')
            .replace(/\\s+/g, ' ')
            .trim();
          return {
            tag: element.tagName.toLowerCase(),
            classes: element.className,
            directText,
            rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
            display: style.display,
            padding: style.padding,
            margin: style.margin,
            gap: style.gap,
            font: style.font,
            color: style.color,
            background: style.background,
            border: style.border,
            borderRadius: style.borderRadius,
          };
        });
      })()
    `);
    process.stdout.write(`${JSON.stringify(measurements, null, 2)}\n`);
  }
  finally {
    window.destroy();
  }
}

async function compareImagePair({
  referencePath,
  actualPath,
  diffPath,
  channelTolerance,
  allowedPixelRatio,
  antiAliasChannelTolerance,
  allowedAntiAliasPixelRatio,
}) {
  const reference = nativeImage.createFromPath(referencePath);
  const actual = nativeImage.createFromPath(actualPath);
  const referenceSize = reference.getSize();
  const actualSize = actual.getSize();
  if (referenceSize.width !== actualSize.width || referenceSize.height !== actualSize.height) {
    throw new Error(
      `image dimensions differ: ${referenceSize.width}x${referenceSize.height} vs `
      + `${actualSize.width}x${actualSize.height}`,
    );
  }

  const expected = reference.toBitmap();
  const received = actual.toBitmap();
  const diff = Buffer.alloc(expected.length);
  const width = referenceSize.width;
  const height = referenceSize.height;
  const totalPixels = width * height;
  const classifications = new Uint8Array(totalPixels);
  let rawChangedPixels = 0;
  for (let offset = 0; offset < expected.length; offset += 4) {
    const pixel = offset / 4;
    const x = pixel % width;
    const y = Math.floor(pixel / width);
    const delta = channelDelta(expected, offset, received, offset);
    const rawChanged = delta > channelTolerance;
    const antiAliased = rawChanged
      && delta <= antiAliasChannelTolerance
      && (
        isAntiAliasedEdge(expected, received, x, y, width, height)
        || isAntiAliasedEdge(received, expected, x, y, width, height)
        || isEdgeCoverageDifference(
          expected,
          received,
          x,
          y,
          width,
          height,
          channelTolerance,
        )
      );
    if (!rawChanged) continue;
    rawChangedPixels += 1;
    if (antiAliased) {
      classifications[pixel] = 1;
    }
    else if (
      delta <= antiAliasChannelTolerance
      && (
        isNearRasterEdge(expected, x, y, width, height, channelTolerance)
        || isNearRasterEdge(received, x, y, width, height, channelTolerance)
      )
    ) {
      classifications[pixel] = 3;
    }
    else {
      classifications[pixel] = 2;
    }
  }

  const componentReport = resolveRasterEdgeComponents(classifications, width, height);
  let antiAliasedPixels = 0;
  let changedPixels = 0;
  for (let pixel = 0; pixel < totalPixels; pixel += 1) {
    const offset = pixel * 4;
    if (classifications[pixel] === 1) {
      antiAliasedPixels += 1;
      diff[offset] = 255;
      diff[offset + 1] = 180;
      diff[offset + 2] = 0;
      diff[offset + 3] = 255;
    }
    else if (classifications[pixel] === 2) {
      changedPixels += 1;
      diff[offset] = 180;
      diff[offset + 1] = 0;
      diff[offset + 2] = 255;
      diff[offset + 3] = 255;
    }
    else {
      const shade = Math.round(
        (expected[offset] + expected[offset + 1] + expected[offset + 2]) / 3,
      );
      diff[offset] = shade;
      diff[offset + 1] = shade;
      diff[offset + 2] = shade;
      diff[offset + 3] = 70;
    }
  }

  await fs.mkdir(path.dirname(diffPath), { recursive: true });
  await fs.writeFile(
    diffPath,
    nativeImage.createFromBitmap(diff, {
      width: referenceSize.width,
      height: referenceSize.height,
      scaleFactor: 1,
    }).toPNG(),
  );

  const rawChangedPixelRatio = rawChangedPixels / totalPixels;
  const antiAliasedPixelRatio = antiAliasedPixels / totalPixels;
  const changedPixelRatio = changedPixels / totalPixels;
  const report = {
    reference: referencePath,
    actual: actualPath,
    rawChangedPixels,
    rawChangedPixelRatio,
    antiAliasedPixels,
    antiAliasedPixelRatio,
    changedPixels,
    totalPixels,
    changedPixelRatio,
    channelTolerance,
    allowedPixelRatio,
    antiAliasChannelTolerance,
    allowedAntiAliasPixelRatio,
    antiAliasComponents: componentReport,
    passed: changedPixelRatio <= allowedPixelRatio
      && antiAliasedPixelRatio <= allowedAntiAliasPixelRatio,
  };
  return report;
}

function channelDelta(first, firstOffset, second, secondOffset) {
  return Math.max(
    Math.abs(first[firstOffset] - second[secondOffset]),
    Math.abs(first[firstOffset + 1] - second[secondOffset + 1]),
    Math.abs(first[firstOffset + 2] - second[secondOffset + 2]),
    Math.abs(first[firstOffset + 3] - second[secondOffset + 3]),
  );
}

function luminance(bitmap, offset) {
  const alpha = bitmap[offset + 3] / 255;
  const value = (
    (0.114 * bitmap[offset])
    + (0.587 * bitmap[offset + 1])
    + (0.299 * bitmap[offset + 2])
  );
  return (value * alpha) + (255 * (1 - alpha));
}

function hasManyEqualSiblings(bitmap, x, y, width, height) {
  const center = ((y * width) + x) * 4;
  let equalSiblings = 0;
  for (let siblingY = Math.max(0, y - 1); siblingY <= Math.min(height - 1, y + 1); siblingY += 1) {
    for (let siblingX = Math.max(0, x - 1); siblingX <= Math.min(width - 1, x + 1); siblingX += 1) {
      if (siblingX === x && siblingY === y) continue;
      const sibling = ((siblingY * width) + siblingX) * 4;
      if (channelDelta(bitmap, center, bitmap, sibling) === 0) {
        equalSiblings += 1;
        if (equalSiblings >= 3) return true;
      }
    }
  }
  return false;
}

function isAntiAliasedEdge(bitmap, other, x, y, width, height) {
  if (x === 0 || y === 0 || x === width - 1 || y === height - 1) return false;
  const center = ((y * width) + x) * 4;
  const centerLuminance = luminance(bitmap, center);
  let equalNeighbors = 0;
  let darkestDelta = 0;
  let darkestX = x;
  let darkestY = y;
  let lightestDelta = 0;
  let lightestX = x;
  let lightestY = y;

  for (let neighborY = y - 1; neighborY <= y + 1; neighborY += 1) {
    for (let neighborX = x - 1; neighborX <= x + 1; neighborX += 1) {
      if (neighborX === x && neighborY === y) continue;
      const neighbor = ((neighborY * width) + neighborX) * 4;
      if (channelDelta(bitmap, center, bitmap, neighbor) === 0) {
        equalNeighbors += 1;
        if (equalNeighbors > 2) return false;
        continue;
      }
      const delta = luminance(bitmap, neighbor) - centerLuminance;
      if (delta < darkestDelta) {
        darkestDelta = delta;
        darkestX = neighborX;
        darkestY = neighborY;
      }
      if (delta > lightestDelta) {
        lightestDelta = delta;
        lightestX = neighborX;
        lightestY = neighborY;
      }
    }
  }

  if (darkestDelta === 0 || lightestDelta === 0) return false;
  return (
    hasManyEqualSiblings(bitmap, darkestX, darkestY, width, height)
    && hasManyEqualSiblings(other, darkestX, darkestY, width, height)
  ) || (
    hasManyEqualSiblings(bitmap, lightestX, lightestY, width, height)
    && hasManyEqualSiblings(other, lightestX, lightestY, width, height)
  );
}

function isEdgeCoverageDifference(expected, received, x, y, width, height, tolerance) {
  const expectedCenter = ((y * width) + x) * 4;
  const receivedCenter = expectedCenter;
  const radius = 4;
  for (
    let neighborY = Math.max(0, y - radius);
    neighborY <= Math.min(height - 1, y + radius);
    neighborY += 1
  ) {
    for (
      let neighborX = Math.max(0, x - radius);
      neighborX <= Math.min(width - 1, x + radius);
      neighborX += 1
    ) {
      if (neighborX === x && neighborY === y) continue;
      const neighbor = ((neighborY * width) + neighborX) * 4;
      if (channelDelta(expected, neighbor, received, neighbor) > tolerance) continue;

      const expectedVector = [
        expected[expectedCenter] - expected[neighbor],
        expected[expectedCenter + 1] - expected[neighbor + 1],
        expected[expectedCenter + 2] - expected[neighbor + 2],
      ];
      const receivedVector = [
        received[receivedCenter] - received[neighbor],
        received[receivedCenter + 1] - received[neighbor + 1],
        received[receivedCenter + 2] - received[neighbor + 2],
      ];
      const expectedLengthSquared = expectedVector.reduce(
        (sum, component) => sum + (component * component),
        0,
      );
      const receivedLengthSquared = receivedVector.reduce(
        (sum, component) => sum + (component * component),
        0,
      );
      if (expectedLengthSquared < 64 || receivedLengthSquared < 64) continue;

      const dot = expectedVector.reduce(
        (sum, component, index) => sum + (component * receivedVector[index]),
        0,
      );
      if (dot <= 0) continue;
      const cosineSquared = (dot * dot) / (expectedLengthSquared * receivedLengthSquared);
      if (cosineSquared < 0.95 * 0.95) continue;

      const scale = dot / expectedLengthSquared;
      if (scale < 0.4 || scale > 2.5) continue;
      const residual = Math.max(
        ...expectedVector.map(
          (component, index) => Math.abs(receivedVector[index] - (component * scale)),
        ),
      );
      if (residual <= 12) return true;
    }
  }
  return false;
}

function isNearRasterEdge(bitmap, x, y, width, height, tolerance) {
  const center = ((y * width) + x) * 4;
  const radius = 4;
  for (
    let neighborY = Math.max(0, y - radius);
    neighborY <= Math.min(height - 1, y + radius);
    neighborY += 1
  ) {
    for (
      let neighborX = Math.max(0, x - radius);
      neighborX <= Math.min(width - 1, x + radius);
      neighborX += 1
    ) {
      if (neighborX === x && neighborY === y) continue;
      const neighbor = ((neighborY * width) + neighborX) * 4;
      if (channelDelta(bitmap, center, bitmap, neighbor) > tolerance) return true;
    }
  }
  return false;
}

function resolveRasterEdgeComponents(classifications, width, height) {
  let accepted = 0;
  let rejected = 0;
  let largestRejectedPixels = 0;
  for (let pixel = 0; pixel < classifications.length; pixel += 1) {
    if (classifications[pixel] !== 3) continue;
    const stack = [pixel];
    const component = [];
    classifications[pixel] = 4;
    let minimumX = pixel % width;
    let maximumX = minimumX;
    let minimumY = Math.floor(pixel / width);
    let maximumY = minimumY;
    while (stack.length > 0) {
      const current = stack.pop();
      component.push(current);
      const x = current % width;
      const y = Math.floor(current / width);
      minimumX = Math.min(minimumX, x);
      maximumX = Math.max(maximumX, x);
      minimumY = Math.min(minimumY, y);
      maximumY = Math.max(maximumY, y);
      for (
        let neighborY = Math.max(0, y - 1);
        neighborY <= Math.min(height - 1, y + 1);
        neighborY += 1
      ) {
        for (
          let neighborX = Math.max(0, x - 1);
          neighborX <= Math.min(width - 1, x + 1);
          neighborX += 1
        ) {
          const neighbor = (neighborY * width) + neighborX;
          if (classifications[neighbor] !== 3) continue;
          classifications[neighbor] = 4;
          stack.push(neighbor);
        }
      }
    }

    const componentWidth = maximumX - minimumX + 1;
    const componentHeight = maximumY - minimumY + 1;
    const isRasterArtifact = (
      componentWidth <= MAX_ANTI_ALIAS_COMPONENT_DIMENSION
      && componentHeight <= MAX_ANTI_ALIAS_COMPONENT_DIMENSION
      && component.length <= MAX_ANTI_ALIAS_COMPONENT_PIXELS
    );
    for (const member of component) classifications[member] = isRasterArtifact ? 1 : 2;
    if (isRasterArtifact) {
      accepted += 1;
    }
    else {
      rejected += 1;
      largestRejectedPixels = Math.max(largestRejectedPixels, component.length);
    }
  }
  return {
    accepted,
    rejected,
    largestRejectedPixels,
    maximumDimension: MAX_ANTI_ALIAS_COMPONENT_DIMENSION,
    maximumPixels: MAX_ANTI_ALIAS_COMPONENT_PIXELS,
  };
}

async function compareImages(values) {
  const report = await compareImagePair({
    referencePath: path.resolve(required(values, 'reference')),
    actualPath: path.resolve(required(values, 'actual')),
    diffPath: path.resolve(required(values, 'diff')),
    channelTolerance: Number(values.get('channel-tolerance') ?? '10'),
    allowedPixelRatio: Number(values.get('allowed-pixel-ratio') ?? '0'),
    antiAliasChannelTolerance: Number(values.get('anti-alias-channel-tolerance') ?? '255'),
    allowedAntiAliasPixelRatio: Number(values.get('allowed-anti-alias-pixel-ratio') ?? '0.02'),
  });
  process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
  if (!report.passed) process.exitCode = 1;
}

async function compareImageSuite(values) {
  const referenceDirectory = path.resolve(required(values, 'reference'));
  const actualDirectory = path.resolve(required(values, 'actual'));
  const diffDirectory = path.resolve(required(values, 'diff'));
  const channelTolerance = Number(values.get('channel-tolerance') ?? '10');
  const allowedPixelRatio = Number(values.get('allowed-pixel-ratio') ?? '0');
  const antiAliasChannelTolerance = Number(values.get('anti-alias-channel-tolerance') ?? '255');
  const allowedAntiAliasPixelRatio = Number(
    values.get('allowed-anti-alias-pixel-ratio') ?? '0.02',
  );
  const selected = values.has('frames')
    ? required(values, 'frames').split(',').map((value) => value.trim()).filter(Boolean)
    : FRAME_IDS;
  const reports = [];
  for (const frameId of selected) {
    const report = await compareImagePair({
      referencePath: path.join(referenceDirectory, `${frameId}.png`),
      actualPath: path.join(actualDirectory, `${frameId}.png`),
      diffPath: path.join(diffDirectory, `${frameId}.png`),
      channelTolerance,
      allowedPixelRatio,
      antiAliasChannelTolerance,
      allowedAntiAliasPixelRatio,
    });
    reports.push({ frame: frameId, ...report });
    process.stdout.write(
      `${frameId}: ${(report.changedPixelRatio * 100).toFixed(4)}% visible, `
      + `${(report.antiAliasedPixelRatio * 100).toFixed(4)}% edge AA `
      + `${report.passed ? 'PASS' : 'FAIL'}\n`,
    );
  }
  const failed = reports.filter((report) => !report.passed);
  await fs.mkdir(diffDirectory, { recursive: true });
  await fs.writeFile(
    path.join(diffDirectory, 'report.json'),
    `${JSON.stringify({ reports, passed: failed.length === 0 }, null, 2)}\n`,
  );
  if (failed.length > 0) process.exitCode = 1;
}

const [mode = '', ...argv] = process.argv.slice(2);
const values = argumentsByName(argv);

process.stdout.write(`workspace visual harness: ${mode || 'no mode'} ${JSON.stringify(argv)}\n`);
void app.whenReady().then(async () => {
  process.stdout.write('Electron ready\n');
  try {
    if (mode === 'references') {
      await extractReferences(values);
    }
    else if (mode === 'inspect') {
      await inspectReferences(values);
    }
    else if (mode === 'measure') {
      await measureReference(values);
    }
    else if (mode === 'capture') {
      await captureProduction(values);
    }
    else if (mode === 'capture-suite') {
      await captureProductionSuite(values);
    }
    else if (mode === 'measure-production') {
      await measureProduction(values);
    }
    else if (mode === 'compare') {
      await compareImages(values);
    }
    else if (mode === 'compare-suite') {
      await compareImageSuite(values);
    }
    else {
      throw new Error(
        'mode must be references, inspect, measure, capture, capture-suite, '
          + 'measure-production, compare, or compare-suite',
      );
    }
  }
  catch (error) {
    process.stderr.write(`${error instanceof Error ? error.stack : String(error)}\n`);
    process.exitCode = 1;
  }
  finally {
    app.exit(process.exitCode ?? 0);
  }
});
