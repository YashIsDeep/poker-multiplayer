# Admin Console

A standalone, read-only terminal browser for the Poker Multiplayer H2 database.
It connects directly through JDBC. It is not imported, served, or referenced by
the Spring application or the React frontend.

## Start it

From the repository root:

```bat
npm run admin-console
```

Admin Console automatically connects to the active development database at
`127.0.0.1:9092`. If the development database server is stopped, it falls back
to the packaged application's `data/poker.mv.db` file. It can be launched from
the repository root or from inside `admin-console`.

Useful commands:

```text
tables
describe app_user
rows app_user 25
count spring_session
sql SELECT display_name, last_login_at FROM app_user ORDER BY last_login_at DESC
quit
```

You can also run one command without entering the interactive prompt:

```bat
npm run admin-console -- rows app_user 10
npm run admin-console -- sql "SELECT COUNT(*) AS users FROM app_user"
```

## Safety

- The JDBC connection is marked read-only and every command is rolled back.
- The `sql` command accepts only read-query forms and rejects multiple
  statements and `SELECT ... FOR UPDATE`.
- Results are capped at 500 rows and queries time out after 30 seconds.
- Database credentials are read only from this process's environment.

Optional connection overrides:

```text
ADMIN_DB_URL
ADMIN_DB_USER
ADMIN_DB_PASSWORD
ADMIN_DB_HOST
ADMIN_DB_PORT
```

`ADMIN_DB_HOST` and `ADMIN_DB_PORT` override development database detection and
also honor the existing `DB_HOST` and `DB_PORT` values. `ADMIN_DB_URL` takes
precedence over all auto-detection. The default credentials are user `sa` and
an empty password.

To inspect the development database file while its TCP server is stopped, set
`ADMIN_DB_URL` to an absolute file URL ending in
`server/data/poker;AUTO_SERVER=TRUE`.
