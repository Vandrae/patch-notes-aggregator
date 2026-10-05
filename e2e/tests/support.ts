import { expect, type Page } from '@playwright/test';

export const STEAM = `http://localhost:${process.env.FAKE_STEAM_PORT ?? 9099}`;

let nextSteamId = 76561198000001000n;

/** A SteamID nobody has used in this run, so each test starts with its own empty account. */
export function newSteamId(): string {
  return (nextSteamId++).toString();
}

export async function fakeSteamCalls(): Promise<{ kind: string; [key: string]: unknown }[]> {
  return (await fetch(`${STEAM}/__calls`)).json();
}

/** Signs in through the real flow: the app's login route, the (fake) Steam page, and back to the app's callback. */
export async function signIn(page: Page, steamId: string = newSteamId(), start = '/login'): Promise<string> {
  await fetch(`${STEAM}/__user`, { method: 'POST', body: steamId });
  await page.goto(start);
  await page.getByRole('link', { name: /sign in through steam/i }).click();
  await expect(page.getByRole('heading', { name: 'Your patch notes' })).toBeVisible();
  return steamId;
}
