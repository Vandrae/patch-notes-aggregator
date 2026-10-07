// Starts the packaged app (target/patch-notes-aggregator-*.jar, built by `./mvnw package`) for the browser tests:
// an in-memory database, the fake Steam for sign-in and news, and small rate limits so the limit itself can be tested.
import { spawn } from 'node:child_process';
import { readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(fileURLToPath(new URL('.', import.meta.url)), '..');
const targetDir = join(root, 'target');
const jar = readdirSync(targetDir).find((name) => /^patch-notes-aggregator-.*\.jar$/.test(name) && !name.endsWith('.original'));
if (!jar) {
  console.error(`No jar in ${targetDir}. Build it first: ./mvnw -DskipTests package`);
  process.exit(1);
}

const APP_PORT = process.env.E2E_APP_PORT ?? '8089';
const STEAM = `http://localhost:${process.env.FAKE_STEAM_PORT ?? 9099}`;
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'java') : 'java';

const child = spawn(
  java,
  [
    '-jar', join(targetDir, jar),
    `--server.port=${APP_PORT}`,
    `--app.security.steam.public-base-url=http://localhost:${APP_PORT}`,
    `--app.security.steam.openid-endpoint=${STEAM}/openid/login`,
    `--app.steam.base-url=${STEAM}`,
    '--app.security.jwt.secret=e2e-only-secret-e2e-only-secret-e2e-only',
    '--spring.datasource.url=jdbc:h2:mem:e2e;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1',
    // The non-Steam games (Roblox, Minecraft) read the fake publishers on the fake Steam's port, not the real internet. (A list set
    // here replaces the one in application.yml entirely, so every field the tests rely on, art included, is repeated.)
    '--app.custom.games[0].name=Roblox',
    '--app.custom.games[0].description=Test description for Roblox.',
    '--app.custom.games[0].popularity=20000000',
    '--app.custom.games[0].image=/art/roblox-cover.svg',
    '--app.custom.games[0].icon=/art/roblox-icon.svg',
    '--app.custom.games[0].sources[0].kind=RSS',
    `--app.custom.games[0].sources[0].url=${STEAM}/publisher/roblox.rss`,
    '--app.custom.games[1].name=Minecraft',
    '--app.custom.games[1].description=Test description for Minecraft.',
    '--app.custom.games[1].popularity=20000000',
    '--app.custom.games[1].image=/art/minecraft-cover.svg',
    '--app.custom.games[1].icon=/art/minecraft-icon.svg',
    '--app.custom.games[1].sources[0].kind=HELP_CENTER',
    `--app.custom.games[1].sources[0].url=${STEAM}/publisher/minecraft.json`,
    // Limits sized for this suite: it signs in about ten times from one address, then deliberately runs into the limit.
    // They refill slowly, so once the limit has been hit it stays hit for the rest of the run.
    '--app.security.rate-limit.login.burst=16', '--app.security.rate-limit.login.per-minute=1',
    '--app.security.rate-limit.callback.burst=40', '--app.security.rate-limit.callback.per-minute=1',
  ],
  { stdio: 'inherit' },
);
child.on('exit', (code) => process.exit(code ?? 0));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill());
