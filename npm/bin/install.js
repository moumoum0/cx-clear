import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installJava, assertSupportedPlatform } from '../lib/java.js';

try {
  assertSupportedPlatform();
  const root = dirname(dirname(fileURLToPath(import.meta.url)));
  await installJava({ root });
} catch (error) {
  console.error(`cxclear: Java setup failed: ${error.message}`);
  process.exitCode = 1;
}
