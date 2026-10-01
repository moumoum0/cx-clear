import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, writeFile, rm, readdir, access } from 'node:fs/promises';
import { join } from 'node:path';
import { assertSupportedPlatform, downloadVerified, installJava, installRuntime, installedJava, recordPath, usableJavaHome } from '../lib/java.js';

const payload = Buffer.from('a pinned Java archive fixture');
const runtime = {
  id: 'test-java-21', url: 'https://example.com/java.zip', size: payload.length,
  sha256: createHash('sha256').update(payload).digest('hex'),
};

async function temporary(t) {
  const root = join(process.cwd(), 'build', 'npm-tests');
  await mkdir(root, { recursive: true });
  const directory = await mkdtemp(join(root, 'case-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  return directory;
}

test('only Windows x64 is supported', () => {
  assert.doesNotThrow(() => assertSupportedPlatform('win32', 'x64'));
  assert.throws(() => assertSupportedPlatform('linux', 'x64'), /Windows x64/);
  assert.throws(() => assertSupportedPlatform('win32', 'arm64'), /Windows x64/);
});

test('missing Java cannot be reused', async t => {
  assert.equal(await usableJavaHome(await temporary(t)), false);
});

test('verified download writes the exact archive', async t => {
  const destination = join(await temporary(t), 'java.zip');
  await downloadVerified(runtime, destination, async () => new Response(payload));
  assert.deepEqual(await readFile(destination), payload);
});

test('HTTP failures, truncated downloads, and checksum mismatches are rejected', async t => {
  const root = await temporary(t);
  await assert.rejects(downloadVerified(runtime, join(root, 'http.zip'), async () => new Response('', { status: 503 })), /HTTP 503/);
  await assert.rejects(downloadVerified(runtime, join(root, 'short.zip'), async () => new Response(payload.subarray(1))), /verification/);
  await assert.rejects(downloadVerified({ ...runtime, sha256: '0'.repeat(64) }, join(root, 'hash.zip'), async () => new Response(payload)), /verification/);
  await assert.rejects(downloadVerified(runtime, join(root, 'large.zip'), async () => new Response(Buffer.concat([payload, payload]))), /expected size/);
});

function fixture(cacheRoot, overrides = {}) {
  return {
    cacheRoot, runtime, log: () => {},
    probe: async home => {
      try { return await readFile(join(home, 'ready'), 'utf8') === 'java21-x64'; }
      catch { return false; }
    },
    download: async (_, archive) => writeFile(archive, payload),
    extract: async (_, destination) => {
      await mkdir(join(destination, 'jre', 'bin'), { recursive: true });
      await writeFile(join(destination, 'jre', 'ready'), 'java21-x64');
    },
    ...overrides,
  };
}

test('concurrent installs download once and reuse a complete runtime', async t => {
  const root = await temporary(t);
  let downloads = 0;
  const options = fixture(root, {
    download: async (_, archive) => {
      downloads++;
      await new Promise(resolve => setTimeout(resolve, 50));
      await writeFile(archive, payload);
    },
  });
  const homes = await Promise.all([installRuntime(options), installRuntime(options)]);
  assert.equal(homes[0], homes[1]);
  assert.equal(await installRuntime(options), homes[0]);
  assert.equal(downloads, 1);
  assert.deepEqual(await readdir(root), [runtime.id]);
});

test('failed download leaves no runtime or lock and can be retried', async t => {
  const root = await temporary(t);
  await assert.rejects(installRuntime(fixture(root, { download: async () => { throw new Error('offline'); } })), /offline/);
  assert.deepEqual(await readdir(root), []);
  const home = await installRuntime(fixture(root));
  await access(join(home, 'ready'));
});

test('invalid cached runtime is replaced only after successful verification', async t => {
  const root = await temporary(t);
  const home = join(root, runtime.id);
  await mkdir(home);
  await writeFile(join(home, 'ready'), 'invalid');
  await assert.rejects(installRuntime(fixture(root, { probe: async () => false })), /not Windows x64 Java 21/);
  assert.equal(await readFile(join(home, 'ready'), 'utf8'), 'invalid');
  await installRuntime(fixture(root));
  assert.equal(await readFile(join(home, 'ready'), 'utf8'), 'java21-x64');
});

test('runtime IDs cannot escape the cache directory', async t => {
  const root = await temporary(t);
  await assert.rejects(installRuntime(fixture(root, { runtime: { ...runtime, id: '..' } })), /metadata/);
});

test('a missing install record is repaired on launch', async t => {
  const root = await temporary(t);
  const env = { CXCLEAR_RUNTIME_DIR: root };
  const packageRoot = join(root, 'package');
  let repairs = 0;
  const repair = async () => {
    repairs++;
    return installJava({ root: packageRoot, env, resolve: async () => join(root, 'java') });
  };
  assert.equal(await installedJava({ root: packageRoot, env, probe: async () => true, repair }), join(root, 'java'));
  assert.equal(repairs, 1);
  const again = await installedJava({ root: packageRoot, env, probe: async () => true, repair });
  assert.equal(again, join(root, 'java'));
  assert.equal(repairs, 1);
});

test('a recorded Java that no longer runs is replaced', async t => {
  const root = await temporary(t);
  const env = { CXCLEAR_RUNTIME_DIR: root };
  const packageRoot = join(root, 'package');
  const stale = join(root, 'stale');
  const fresh = join(root, 'fresh');
  await installJava({ root: packageRoot, env, resolve: async () => stale });
  assert.equal(JSON.parse(await readFile(recordPath(packageRoot, env), 'utf8')).home, stale);
  const home = await installedJava({
    root: packageRoot, env,
    probe: async candidate => candidate === fresh,
    repair: () => installJava({ root: packageRoot, env, resolve: async () => fresh }),
  });
  assert.equal(home, fresh);
  assert.equal(JSON.parse(await readFile(recordPath(packageRoot, env), 'utf8')).home, fresh);
});

test('a corrupt install record is repaired', async t => {
  const root = await temporary(t);
  const env = { CXCLEAR_RUNTIME_DIR: root };
  const packageRoot = join(root, 'package');
  const record = recordPath(packageRoot, env);
  await mkdir(join(record, '..'), { recursive: true });
  await writeFile(record, '{');
  const home = await installedJava({
    root: packageRoot, env, probe: async () => true,
    repair: () => installJava({ root: packageRoot, env, resolve: async () => join(root, 'java') }),
  });
  assert.equal(home, join(root, 'java'));
  assert.equal(JSON.parse(await readFile(record, 'utf8')).home, home);
});
