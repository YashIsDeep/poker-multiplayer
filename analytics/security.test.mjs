import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { request } from 'node:http';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { gzipSync } from 'node:zlib';
import { chromium } from '../client/node_modules/playwright/index.mjs';

const payload = '<svg/onload=alert()>';
const breakout = '\"><img src=x onerror=alert()><script>alert()</script>';

function hand(handId) {
  const event = { phase: payload, seat: payload, userId: payload, amount: payload,
    raiseTo: payload, stackAfter: payload, cards: [payload, breakout], board: [payload],
    winners: [{ seat: payload, amount: payload }], reason: payload, action: payload };
  return {
    roomId: payload, roomName: breakout, handId, completedAt: '2026-09-08T12:00:00Z',
    startedAt: payload, smallBlind: payload, bigBlind: payload, potAwarded: 25,
    completionReason: payload, board: [payload, breakout],
    players: [{ userId: payload, displayName: payload, seat: payload,
      holeCards: [payload, breakout], startingStack: payload, endingStack: payload,
      net: handId, won: 25, folded: false }],
    events: ['BLIND_POSTED', 'HOLE_CARD_DEALT', 'PLAYER_ACTED', 'UNCALLED_BET_REFUNDED',
      'STREET_DEALT', 'POT_AWARDED', 'HAND_COMPLETED', payload].map(type => ({ ...event, type })),
  };
}

function rawRequest(base, pathname, options = {}) {
  const url = new URL(pathname, base);
  return new Promise((resolve, reject) => {
    const req = request(url, options, res => {
      res.resume();
      res.once('end', () => resolve(res.statusCode));
    });
    req.once('error', reject);
    req.end();
  });
}

test('hostile archives remain text in filters, tables, charts, tooltips and hand detail', { timeout: 30_000 }, async (t) => {
  const directory = mkdtempSync(path.join(tmpdir(), 'poker-analytics-xss-'));
  t.after(() => {
    assert.equal(path.dirname(path.resolve(directory)), path.resolve(tmpdir()));
    rmSync(directory, { recursive: true, force: true });
  });
  writeFileSync(path.join(directory, 'hands-2026-09-08.jsonl.gz'),
    gzipSync([hand(1), hand(2)].map(value => JSON.stringify(value)).join('\n')));
  const child = spawn(process.execPath, ['analytics/server.mjs'], {
    cwd: new URL('..', import.meta.url), windowsHide: true,
    env: { ...process.env, HAND_ARCHIVE_DIR: directory, ANALYTICS_HOST: '127.0.0.1', ANALYTICS_PORT: '0' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  t.after(async () => {
    if (child.exitCode !== null) return;
    const exited = new Promise(resolve => child.once('exit', resolve));
    child.kill();
    await exited;
  });
  const base = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Analytics failed to start')), 10_000);
    child.once('error', reject);
    child.stdout.on('data', buffer => {
      const url = buffer.toString().match(/http:\/\/127\.0\.0\.1:\d+/)?.[0];
      if (url) { clearTimeout(timeout); resolve(url); }
    });
    child.stderr.on('data', buffer => reject(new Error(buffer.toString())));
  });
  const trustedHost = new URL(base).host;
  for (const endpoint of ['/api/hands', '/api/hand?roomId=missing&handId=1', '/api/stats']) {
    assert.equal(await rawRequest(base, endpoint, {
      headers: { Host: `audit-attacker.invalid:${new URL(base).port}`, Origin: `http://audit-attacker.invalid:${new URL(base).port}` },
    }), 421);
  }
  assert.equal(await rawRequest(base, '/api/hands', { setHost: false }), 400);
  assert.equal(await rawRequest(base, '/api/hands', { headers: { Host: `${trustedHost}:extra` } }), 421);
  assert.equal(await rawRequest(base, '/api/hands', { headers: { Host: `${trustedHost}#ignored` } }), 421);
  assert.equal(await rawRequest(base, '/api/hands', {
    headers: { Host: trustedHost, Origin: 'http://audit-attacker.invalid' },
  }), 403);
  assert.equal(await rawRequest(base, '/api/hands', {
    headers: { Host: trustedHost, 'Sec-Fetch-Site': 'cross-site' },
  }), 403);
  assert.equal(await rawRequest(base, '/api/hands', { headers: { Host: trustedHost } }), 200);
  const browser = await chromium.launch({ headless: true });
  t.after(() => browser.close());
  const page = await browser.newPage();
  const dialogs = [];
  const errors = [];
  page.on('dialog', dialog => { dialogs.push(dialog.message()); dialog.dismiss(); });
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(() => {
    window.cspViolations = [];
    document.addEventListener('securitypolicyviolation', event => window.cspViolations.push(event.violatedDirective));
  });
  const response = await page.goto(base);
  const policy = response.headers()['content-security-policy'];
  for (const directive of ["default-src 'none'", "script-src 'self'", "script-src-attr 'none'",
    "style-src 'self'", "connect-src 'self'", "frame-ancestors 'none'"]) assert.ok(policy.includes(directive));
  assert.equal(response.headers()['x-content-type-options'], 'nosniff');
  await page.locator('#hands-table tbody tr').first().waitFor();
  assert.equal(await page.locator('#hands-table tbody tr').count(), 2);
  assert.ok((await page.locator('#player-table').textContent()).includes(payload));
  assert.equal(await page.locator('#f-room option').last().textContent(), breakout);
  assert.equal(await page.locator('#f-player option').last().textContent(), payload);
  assert.ok((await page.locator('#net-legend').textContent()).includes(payload));
  await page.locator('#net-chart svg').dispatchEvent('mousemove', { clientX: 300, clientY: 250 });
  assert.ok((await page.locator('#net-tip').textContent()).includes(payload));
  await page.locator('#day-chart path[data-i]').first().dispatchEvent('mousemove', { clientX: 850, clientY: 250 });
  assert.ok((await page.locator('#day-tip').textContent()).includes('2026-09-08'));
  await page.locator('#hands-table tbody tr').first().click();
  await page.locator('#detail[open]').waitFor();
  assert.ok((await page.locator('#d-title').textContent()).includes(breakout));
  assert.equal(await page.locator('#d-body .events li').count(), 8);
  assert.ok((await page.locator('#d-body').textContent()).includes(payload));
  assert.equal(await page.locator('img, [onload], [onerror], svg script').count(), 0);
  assert.deepEqual(dialogs, []);
  assert.deepEqual(errors, []);
  assert.deepEqual(await page.evaluate(() => window.cspViolations), []);

  // Poison every scalar, including normally numeric/date/enum fields and nested
  // event fields. API interception permits malformed IDs to reach the detail UI.
  const poison = value => Array.isArray(value) ? value.map(poison)
    : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).map(([key, item]) => [key, poison(item)]))
    : payload;
  const poisoned = poison(hand(1));
  await page.route('**/api/**', route => {
    const endpoint = new URL(route.request().url()).pathname;
    const body = endpoint === '/api/hands' ? [poisoned, poisoned] : endpoint === '/api/hand' ? poisoned : {
      overview: poison({ hands: 2, rooms: 1, players: 1, totalPot: 10, lastAt: '2026', biggestPot: hand(1) }),
      players: [poison({ name: 'p', hands: 1, vpipPct: 1, pfrPct: 1, showdowns: 1, showdownWins: 1, won: 1, biggestPotWon: 1, net: 1 })],
      handsPerDay: [poison({ date: '2026-09-08', hands: 1, pot: 1 })],
    };
    return route.fulfill({ json: body });
  });
  await page.reload();
  await page.locator('#hands-table tbody tr').first().click();
  await page.locator('#detail[open]').waitFor();
  assert.ok((await page.locator('#d-title').textContent()).includes(payload));
  assert.ok((await page.locator('#d-body').textContent()).includes(payload));
  assert.equal(await page.locator('img, [onload], [onerror], svg script').count(), 0);
  assert.deepEqual(errors, []);
  assert.deepEqual(dialogs, []);
  assert.deepEqual(await page.evaluate(() => window.cspViolations), []);

  // Defense in depth: the delivered policy also blocks an injected inline script.
  await page.evaluate(() => {
    const injected = document.createElement('script');
    injected.textContent = 'window.inlineExecuted = true';
    document.body.append(injected);
  });
  await page.waitForFunction(() => window.cspViolations.length > 0);
  assert.equal(await page.evaluate(() => window.inlineExecuted), undefined);
});

test('shared analytics binding requires configured host and HTTP authentication', { timeout: 10_000 }, async (t) => {
  const child = spawn(process.execPath, ['analytics/server.mjs'], {
    cwd: new URL('..', import.meta.url), windowsHide: true,
    env: {
      ...process.env,
      ANALYTICS_HOST: '0.0.0.0',
      ANALYTICS_PORT: '0',
      ANALYTICS_ALLOWED_HOSTS: '127.0.0.1',
      ANALYTICS_USERNAME: 'reviewer',
      ANALYTICS_PASSWORD: 'correct horse battery staple',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  t.after(async () => {
    if (child.exitCode !== null) return;
    const exited = new Promise(resolve => child.once('exit', resolve));
    child.kill();
    await exited;
  });
  const base = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Shared analytics failed to start')), 5_000);
    child.once('error', reject);
    child.stdout.on('data', buffer => {
      const port = buffer.toString().match(/http:\/\/0\.0\.0\.0:(\d+)/)?.[1];
      if (port) { clearTimeout(timeout); resolve(`http://127.0.0.1:${port}`); }
    });
    child.stderr.on('data', buffer => reject(new Error(buffer.toString())));
  });
  assert.equal(await rawRequest(base, '/api/hands'), 401);
  assert.equal(await rawRequest(base, '/api/hands', {
    headers: { Authorization: `Basic ${Buffer.from('reviewer:wrong').toString('base64')}` },
  }), 401);
  assert.equal(await rawRequest(base, '/api/hands', {
    headers: { Authorization: `Basic ${Buffer.from('reviewer:correct horse battery staple').toString('base64')}` },
  }), 200);
});
