import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { access, mkdir, mkdtemp, readFile, readdir, rename, rm, open, stat, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { Readable, Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';

export function assertSupportedPlatform(platform = process.platform, arch = process.arch) {
  if (platform !== 'win32' || arch !== 'x64') {
    throw new Error(`Windows x64 is required; received ${platform}/${arch}`);
  }
}

function run(executable, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], ...options });
    let output = '';
    for (const stream of [child.stdout, child.stderr]) {
      stream.on('data', chunk => { output = (output + chunk).slice(-131072); });
    }
    child.on('error', reject);
    child.on('close', code => {
      if (code === 0) resolve(output);
      else reject(new Error(`${executable} exited with ${code}: ${output.trim()}`));
    });
  });
}

export async function usableJavaHome(home) {
  if (!home) return false;
  try {
    const executable = join(home, 'bin', 'java.exe');
    await access(executable);
    const output = await run(executable, ['-XshowSettings:properties', '-version'], { timeout: 10000 });
    return /^\s*java\.version\s*=\s*21(?:\.|\s|$)/m.test(output)
      && /^\s*os\.arch\s*=\s*(amd64|x86_64)\s*$/m.test(output);
  } catch {
    return false;
  }
}

export async function downloadVerified(runtime, destination, fetchImpl = fetch) {
  const response = await fetchImpl(runtime.url, { signal: AbortSignal.timeout(300000) });
  if (!response.ok || !response.body) throw new Error(`Java download failed: HTTP ${response.status}`);
  const hash = createHash('sha256');
  let bytes = 0;
  const verifier = new Transform({
    transform(chunk, encoding, callback) {
      bytes += chunk.length;
      if (bytes > runtime.size) return callback(new Error('Java download exceeds the expected size'));
      hash.update(chunk);
      callback(null, chunk);
    },
  });
  await pipeline(Readable.fromWeb(response.body), verifier, createWriteStream(destination, { flags: 'wx' }));
  if (bytes !== runtime.size || hash.digest('hex') !== runtime.sha256) throw new Error('Java download failed SHA-256 or size verification');
}

async function extractRuntime(archive, destination) {
  // Environment variables carry paths so PowerShell does not interpret their contents as code.
  const systemRoot = process.env.SystemRoot || 'C:\\Windows';
  await run(join(systemRoot, 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe'), [
    '-NoProfile', '-NonInteractive', '-Command',
    "$ErrorActionPreference = 'Stop'; Add-Type -AssemblyName System.IO.Compression.FileSystem; [IO.Compression.ZipFile]::ExtractToDirectory($env:CXCLEAR_JAVA_ZIP, $env:CXCLEAR_JAVA_DEST)",
  ], {
    timeout: 120000,
    env: { ...process.env, CXCLEAR_JAVA_ZIP: archive, CXCLEAR_JAVA_DEST: destination },
  });
}

async function removeAbandonedLock(lock) {
  try {
    const contents = await readFile(lock, 'utf8');
    if (!contents) {
      if (Date.now() - (await stat(lock)).mtimeMs > 30000) await rm(lock, { force: true });
      return;
    }
    const pid = Number(contents);
    if (!Number.isSafeInteger(pid) || pid <= 0) throw new Error('Invalid Java cache lock');
    try {
      process.kill(pid, 0);
    } catch (error) {
      if (error.code === 'ESRCH') await rm(lock, { force: true });
    }
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
}

export async function installRuntime({ cacheRoot, runtime, probe = usableJavaHome, download = downloadVerified, extract = extractRuntime, log = message => console.error(message) }) {
  if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(runtime.id) || !/^[a-f0-9]{64}$/.test(runtime.sha256)
      || !runtime.url.startsWith('https://') || !Number.isSafeInteger(runtime.size) || runtime.size <= 0) {
    throw new Error('Invalid pinned Java runtime metadata');
  }
  await mkdir(cacheRoot, { recursive: true });
  const home = join(cacheRoot, runtime.id);
  const lock = join(cacheRoot, `${runtime.id}.lock`);
  const deadline = Date.now() + 600000;
  let handle;
  while (!handle) {
    if (await probe(home)) return home;
    try {
      handle = await open(lock, 'wx');
    } catch (error) {
      if (error.code !== 'EEXIST') throw error;
      await removeAbandonedLock(lock);
      if (Date.now() > deadline) throw new Error('Timed out waiting for the Java cache; retry cxclear');
      await new Promise(resolve => setTimeout(resolve, 250));
    }
  }
  let staging;
  try {
    await handle.writeFile(String(process.pid));
    if (await probe(home)) return home;
    staging = await mkdtemp(join(cacheRoot, `${runtime.id}-download-`));
    log(`cxclear: downloading Java 21 (${runtime.id}); this is only needed once`);
    const archive = join(staging, 'java.zip');
    await download(runtime, archive);
    const extracted = join(staging, 'extracted');
    await extract(archive, extracted);
    const roots = await readdir(extracted, { withFileTypes: true });
    const candidates = roots.filter(entry => entry.isDirectory());
    if (candidates.length !== 1) throw new Error('Unexpected Java archive layout');
    const candidate = join(extracted, candidates[0].name);
    if (!await probe(candidate)) throw new Error('Downloaded runtime is not Windows x64 Java 21');
    await rm(home, { recursive: true, force: true });
    await rename(candidate, home);
    return home;
  } finally {
    try {
      if (staging) await rm(staging, { recursive: true, force: true });
    } finally {
      await handle.close();
      await rm(lock, { force: true });
    }
  }
}

export async function ensureJava({ root, env = process.env }) {
  if (await usableJavaHome(env.JAVA_HOME)) return env.JAVA_HOME;
  for (const directory of (env.PATH || '').split(';').filter(Boolean)) {
    const java = join(directory.replace(/^"|"$/g, ''), 'java.exe');
    try {
      await access(java);
      const home = dirname(dirname(java));
      if (await usableJavaHome(home)) return home;
    } catch {}
  }
  const runtime = JSON.parse(await readFile(join(root, 'java-runtime.json'), 'utf8'));
  const cacheRoot = runtimeCacheRoot(env);
  return installRuntime({ cacheRoot, runtime });
}

function runtimeCacheRoot(env) {
  return env.CXCLEAR_RUNTIME_DIR
    || join(env.LOCALAPPDATA || join(homedir(), 'AppData', 'Local'), 'cxclear-java');
}

export function recordPath(root, env) {
  const key = createHash('sha256').update(root).digest('hex');
  return join(runtimeCacheRoot(env), 'installs', `${key}.json`);
}

async function replaceRecord(source, destination) {
  try {
    await rename(source, destination);
  } catch (error) {
    // Windows 上目标已存在时 rename 会 EPERM/EEXIST，清掉旧记录再换上。
    if (error.code !== 'EEXIST' && error.code !== 'EPERM') throw error;
    await rm(destination, { force: true });
    await rename(source, destination);
  }
}

export async function installJava({ root, env = process.env, resolve = ensureJava }) {
  const home = await resolve({ root, env });
  const record = recordPath(root, env);
  await mkdir(dirname(record), { recursive: true });
  const temporary = `${record}.${process.pid}.${Date.now()}.tmp`;
  let published = false;
  try {
    await writeFile(temporary, JSON.stringify({ home }) + '\n', { flag: 'wx' });
    await replaceRecord(temporary, record);
    published = true;
  } finally {
    if (!published) await rm(temporary, { force: true });
  }
  return home;
}

export async function installedJava({ root, env = process.env, probe = usableJavaHome, repair } = {}) {
  const record = recordPath(root, env);
  try {
    const { home } = JSON.parse(await readFile(record, 'utf8'));
    if (await probe(home)) return home;
  } catch (error) {
    // 没记录或记录损坏才重装；其它读失败照常抛出。
    if (error.code !== 'ENOENT' && !(error instanceof SyntaxError)) throw error;
  }
  // postinstall 被跳过，或记录里的 Java 缓存已经失效时，启动再走一遍安装。
  return (repair ?? (() => installJava({ root, env })))();
}
