// Starts the packaged app on a COPY of the real catalog (./data/patchnotes.mv.db, built by running the app once with a Steam
// API key) so that screenshots show real covers, ratings and genres. Only sign-in is faked (see ../fake-steam.mjs): nobody's
// real account is involved, and the copy is replaced on every run (and deleted when the app stops cleanly), so the real database is never touched.
// Patch notes are the real ones: Steam's news API needs no key, and Roblox, Minecraft and Riot are read from their publishers.
import { spawn } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..');
const source = join(root, 'data', 'patchnotes.mv.db');
if (!existsSync(source)) {
  console.error(`No catalog at ${source}. Run the app once with STEAM_API_KEY set, let it import, then try again.`);
  process.exit(1);
}
const jar = readdirSync(join(root, 'target')).find((name) => /^patch-notes-aggregator-.*\.jar$/.test(name) && !name.endsWith('.original'));
if (!jar) {
  console.error('No jar in target/. Build it first: ./mvnw -DskipTests package');
  process.exit(1);
}

// One fixed folder, replaced on every run: the test runner stops this launcher abruptly (on Windows without letting it clean up),
// so a fresh temporary folder per run would leave a 200 MB copy behind each time. This way at most one copy ever exists.
const copyDir = join(tmpdir(), 'patchnotes-screenshots');
rmSync(copyDir, { recursive: true, force: true });
mkdirSync(copyDir, { recursive: true });
copyFileSync(source, join(copyDir, 'patchnotes.mv.db'));

const PORT = process.env.SCREENSHOT_APP_PORT ?? '8090';
const STEAM = `http://localhost:${process.env.FAKE_STEAM_PORT ?? 9099}`;
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'java') : 'java';

const child = spawn(
  java,
  [
    '-jar', join(root, 'target', jar),
    `--server.port=${PORT}`,
    `--app.security.steam.public-base-url=http://localhost:${PORT}`,
    `--app.security.steam.openid-endpoint=${STEAM}/openid/login`,
    '--app.security.jwt.secret=screenshots-only-secret-screenshots-only-secret',
    `--spring.datasource.url=jdbc:h2:file:${copyDir.replaceAll('\\', '/')}/patchnotes;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE`,
    // no Steam key and no background catalog jobs: nothing here should call Steam's store or profile APIs
    '--app.steam.api-key=',
    '--app.catalog.sync.enabled=false',
    '--app.catalog.metadata.enabled=false',
  ],
  { stdio: 'inherit' },
);

function cleanUp() {
  rmSync(copyDir, { recursive: true, force: true });
}
child.on('exit', (code) => {
  cleanUp();
  process.exit(code ?? 0);
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill());
