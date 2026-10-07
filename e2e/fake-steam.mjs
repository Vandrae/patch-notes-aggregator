// A stand-in for the parts of Steam the app talks to, so the browser tests can run offline and be repeatable:
//   - Steam's OpenID 2.0 sign-in page (it approves at once, as whichever SteamID the test chose) and its
//     check_authentication call, which, like the real one, only vouches for a response it issued, and only once
//   - the news feed (ISteamNews/GetNewsForApp) for the one seeded game
// It also has a few /__ routes for the tests: pick the signed-in user, read what the app asked, reset.
import http from 'node:http';
import { randomBytes } from 'node:crypto';

const PORT = Number(process.env.FAKE_STEAM_PORT ?? 9099);
const BASE = `http://localhost:${PORT}`;
const ENDPOINT = `${BASE}/openid/login`;
const OPENID_NS = 'http://specs.openid.net/auth/2.0';
const DRAGONWILDS = '1374490';

let steamId = '76561198000000042';
const issuedNonces = new Set(); // responses we handed out and have not vouched for yet
const calls = []; // what the app asked of us, for the tests to inspect

const hoursAgo = (h) => Math.floor(Date.now() / 1000) - h * 3600;

function news() {
  return {
    appnews: {
      appid: Number(DRAGONWILDS),
      newsitems: [
        {
          gid: '1844751498233777',
          title: '1.0.0.6 is now Live!',
          url: 'https://store.steampowered.com/news/app/1374490/view/e2e-patch',
          is_external_url: true,
          author: 'Mod Doom',
          contents: '[p]Fixed a crash when opening the e2e map.[/p][list][*]Wolves are less hungry[*]Bridges hold again[/list]',
          feedlabel: 'Community Announcements',
          date: hoursAgo(2),
          feedname: 'steam_community_announcements',
          feed_type: 1,
          appid: Number(DRAGONWILDS),
          tags: ['patchnotes'],
        },
        {
          // press coverage is not a patch note and must never reach anybody's feed
          gid: '1844751498232545',
          title: 'Our review roundup',
          url: 'https://example.test/press',
          is_external_url: true,
          author: 'Someone',
          contents: 'hi',
          feedlabel: 'PC Gamer',
          date: hoursAgo(5),
          feedname: 'PC Gamer',
          feed_type: 0,
          appid: Number(DRAGONWILDS),
        },
      ],
    },
  };
}

function idResponseUrl(returnTo) {
  const nonce = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z') + randomBytes(6).toString('hex');
  issuedNonces.add(nonce);
  const params = new URLSearchParams({
    'openid.ns': OPENID_NS,
    'openid.mode': 'id_res',
    'openid.op_endpoint': ENDPOINT,
    'openid.claimed_id': `https://steamcommunity.com/openid/id/${steamId}`,
    'openid.identity': `https://steamcommunity.com/openid/id/${steamId}`,
    'openid.return_to': returnTo,
    'openid.response_nonce': nonce,
    'openid.assoc_handle': '1234567890',
    'openid.signed': 'signed,op_endpoint,claimed_id,identity,return_to,response_nonce,assoc_handle',
    'openid.sig': 'fake-signature',
  });
  return `${returnTo}${returnTo.includes('?') ? '&' : '?'}${params}`;
}

function readBody(req) {
  return new Promise((resolve) => {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => resolve(body));
  });
}

function send(res, status, body, type = 'application/json') {
  res.writeHead(status, { 'Content-Type': type });
  res.end(typeof body === 'string' ? body : JSON.stringify(body));
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, BASE);

  if (url.pathname === '/__health') return send(res, 200, { ok: true });
  if (url.pathname === '/__calls') return send(res, 200, calls);
  if (url.pathname === '/__user' && req.method === 'POST') {
    steamId = (await readBody(req)).trim();
    return send(res, 200, { steamId });
  }
  if (url.pathname === '/__reset' && req.method === 'POST') {
    calls.length = 0;
    return send(res, 200, {});
  }

  if (url.pathname === '/openid/login' && req.method === 'GET') {
    const returnTo = url.searchParams.get('openid.return_to');
    if (url.searchParams.get('openid.mode') !== 'checkid_setup' || !returnTo) return send(res, 400, { error: 'not a sign-in request' });
    calls.push({ kind: 'sign-in-page' });
    res.writeHead(302, { Location: idResponseUrl(returnTo) });
    return res.end();
  }

  if (url.pathname === '/openid/login' && req.method === 'POST') {
    const form = new URLSearchParams(await readBody(req));
    const nonce = form.get('openid.response_nonce') ?? '';
    const valid = form.get('openid.mode') === 'check_authentication' && issuedNonces.delete(nonce); // once only
    calls.push({ kind: 'check-authentication', valid });
    return send(res, 200, `ns:${OPENID_NS}\nis_valid:${valid}\n`, 'text/plain');
  }

  // The publishers of games that are not on Steam, in the two shapes the app reads: Roblox's developer forum (an RSS feed)
  // and Minecraft's help centre (Zendesk's article list). Dates are relative to now so the notes always look recent.
  if (url.pathname === '/publisher/roblox.rss') {
    calls.push({ kind: 'publisher-rss' });
    const item = (n, hours) => `
      <item>
        <title>Release Notes for ${n}</title>
        <link>https://devforum.roblox.com/t/release-notes-for-${n}/${n}</link>
        <pubDate>${new Date(Date.now() - hours * 3600_000).toUTCString()}</pubDate>
        <guid isPermaLink="false">devforum.roblox.com-topic-${n}</guid>
        <description><![CDATA[<p>Hey everyone, release notes ${n} are here:</p><ul><li>Studio is faster to open</li><li>Fixed a physics crash</li></ul>]]></description>
      </item>`;
    return send(res, 200, `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"><channel><title>Release Notes - Developer Forum</title><link>https://devforum.roblox.com/c/updates/release-notes/62</link>${item(900, 3)}${item(899, 200)}</channel></rss>`,
      'application/rss+xml');
  }
  if (url.pathname === '/publisher/minecraft.json') {
    calls.push({ kind: 'publisher-help-center' });
    const article = (id, title, hours) => ({
      id, title, draft: false,
      html_url: `https://feedback.minecraft.net/hc/en-us/articles/${id}`,
      created_at: new Date(Date.now() - hours * 3600_000).toISOString(),
      body: `<p>${title}</p><h2>Fixes</h2><ul><li>Fixed the llama spitting at the wrong target</li><li>Fixed a crash in caves</li></ul>`,
    });
    return send(res, 200, { articles: [article(5001, 'Minecraft Java Edition - 99.1', 5), article(5000, 'Minecraft: Bedrock Edition 99.0 Hotfix Changelog', 90)] });
  }

  if (url.pathname === '/ISteamNews/GetNewsForApp/v2/') {
    const appid = url.searchParams.get('appid');
    calls.push({ kind: 'news', appid });
    // like Steam, an app with no news feed is a 403 with an empty object
    return appid === DRAGONWILDS ? send(res, 200, news()) : send(res, 403, {});
  }

  return send(res, 404, { error: `the fake Steam has no ${req.method} ${url.pathname}` });
});

server.listen(PORT, () => console.log(`fake Steam listening on ${BASE}`));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
