const oauthVariables = ['GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET', 'DISCORD_CLIENT_ID', 'DISCORD_CLIENT_SECRET']
const disabled = (value) => ['false', '0', 'off', 'no'].includes(value?.trim().toLowerCase())
const enabled = (value) => Boolean(value?.trim()) && !disabled(value)
const localProfiles = (profiles) => profiles.split(',').some((profile) => ['dev', 'local-auth'].includes(profile.trim()))

export function productionProfiles(environment, args = []) {
  const override = args.findLast((argument) => argument.startsWith('--spring.profiles.active='))
  const profiles = override?.slice('--spring.profiles.active='.length)
    || environment.SPRING_PROFILES_ACTIVE?.trim()
    || (disabled(environment.OAUTH_ENABLED) ? 'local-auth' : 'oauth')
  if (disabled(environment.OAUTH_ENABLED) && profiles.split(',').some((profile) => profile.trim() === 'oauth')) {
    throw new Error('OAUTH_ENABLED=false cannot be used with the oauth profile.')
  }
  return profiles
}

export function developmentProfiles(environment) {
  const configured = disabled(environment.OAUTH_ENABLED) ? [] : oauthVariables.filter((name) => environment[name]?.trim())
  if (configured.length && configured.length !== oauthVariables.length) {
    throw new Error(`OAuth configuration is incomplete. Set ${oauthVariables.filter((name) => !environment[name]?.trim()).join(', ')}.`)
  }
  const tunnel = enabled(environment.TUNNEL_MODE)
  const profiles = environment.SPRING_PROFILES_ACTIVE?.trim()
    || (configured.length === oauthVariables.length ? (tunnel ? 'oauth' : 'dev,oauth') : 'dev')
  if (tunnel) assertTunnelAuthentication(environment, false, [], profiles)
  return profiles
}

export function assertLocalAuthentication(environment, profiles, args = []) {
  if (!localProfiles(profiles)) return
  const override = args.findLast((argument) => argument.startsWith('--server.address='))
  const address = override?.slice('--server.address='.length) || environment.SERVER_ADDRESS?.trim()
  if (address && !/^(127\.(?:\d{1,3}\.){2}\d{1,3}|::1|0:0:0:0:0:0:0:1)$/.test(address)) {
    throw new Error('Guest authentication requires a loopback SERVER_ADDRESS; use OAuth for public access.')
  }
  if (profiles.split(',').some((profile) => ['prod', 'production'].includes(profile.trim()))) {
    throw new Error('Guest authentication cannot be enabled in production profiles.')
  }
  if (enabled(environment.TUNNEL_MODE) || environment.PROXY_MODE || environment.VITE_TUNNEL_HOST || environment.TUNNEL_PUBLIC_URL) {
    throw new Error('Guest authentication cannot be exposed through a tunnel; use OAuth.')
  }
}

export function assertTunnelAuthentication(environment, production, args = [], profilesOverride) {
  const profiles = profilesOverride || (production
    ? productionProfiles(environment, args)
    : environment.SPRING_PROFILES_ACTIVE?.trim() || 'oauth')
  if (localProfiles(profiles) || !profiles.split(',').some((profile) => profile.trim() === 'oauth')
      || disabled(environment.OAUTH_ENABLED) || oauthVariables.some((name) => !environment[name]?.trim())) {
    throw new Error('Tunnels require OAuth credentials and an OAuth-only profile. Guest/local-auth login cannot be public.')
  }
  if (disabled(environment.SESSION_COOKIE_SECURE)) {
    throw new Error('Public OAuth tunnels require Secure session cookies; remove SESSION_COOKIE_SECURE=false.')
  }
}
