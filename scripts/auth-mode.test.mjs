import assert from 'node:assert/strict'
import { test } from 'node:test'
import { spawnSync } from 'node:child_process'
import { assertLocalAuthentication, assertTunnelAuthentication, developmentProfiles, productionProfiles } from './auth-mode.mjs'

const oauth = { GOOGLE_CLIENT_ID: 'id', GOOGLE_CLIENT_SECRET: 'secret', DISCORD_CLIENT_ID: 'id', DISCORD_CLIENT_SECRET: 'secret' }

test('local guest startup refuses public binds, production profiles and tunnel context', () => {
  for (const SERVER_ADDRESS of ['0.0.0.0', '::', '192.168.1.2', 'example.com']) {
    assert.throws(() => assertLocalAuthentication({ SERVER_ADDRESS }, 'local-auth'), /loopback/)
  }
  assert.throws(() => assertLocalAuthentication({}, 'local-auth,production'), /production/)
  assert.throws(() => assertLocalAuthentication({ TUNNEL_MODE: 'true' }, 'local-auth'), /tunnel/)
  assert.throws(() => assertLocalAuthentication({ TUNNEL_MODE: '1' }, 'local-auth'), /tunnel/)
  assert.throws(() => assertLocalAuthentication({}, 'local-auth', ['--server.address=0.0.0.0']), /loopback/)
  assert.doesNotThrow(() => assertLocalAuthentication({ SERVER_ADDRESS: '127.0.0.1' }, 'local-auth'))
})

test('tunnels require OAuth and never activate dev/local-auth', () => {
  assert.throws(() => assertTunnelAuthentication({ OAUTH_ENABLED: 'false' }, true), /Tunnels require/)
  assert.throws(() => assertTunnelAuthentication(oauth, true, ['--spring.profiles.active=local-auth']), /Tunnels require/)
  assert.throws(() => assertTunnelAuthentication({ ...oauth, SPRING_PROFILES_ACTIVE: 'dev,oauth' }, false), /Tunnels require/)
  assert.throws(() => assertTunnelAuthentication({ ...oauth, SESSION_COOKIE_SECURE: 'false' }, false), /Secure/)
  assert.throws(() => developmentProfiles({ TUNNEL_MODE: 'true' }), /Tunnels require/)
  assert.equal(developmentProfiles({ ...oauth, TUNNEL_MODE: 'true' }), 'oauth')
  assert.equal(developmentProfiles({}), 'dev')
  assert.equal(productionProfiles({ OAUTH_ENABLED: 'false' }), 'local-auth')
})

test('public guest launch scripts fail before starting Java or a tunnel client', () => {
  for (const script of ['start.mjs', 'dev-proxy.mjs', 'prod-proxy.mjs']) {
    const result = spawnSync(process.execPath, [`scripts/${script}`], {
      env: { ...process.env, OAUTH_ENABLED: 'false', SPRING_PROFILES_ACTIVE: 'local-auth', SERVER_ADDRESS: '0.0.0.0' },
      encoding: 'utf8', timeout: 5000,
    })
    assert.equal(result.status, 1, result.stderr)
    assert.match(result.stderr, /loopback|Tunnels require/)
  }
})
