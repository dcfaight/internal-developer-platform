# Backend

Java 17 + Spring Boot 4.1.1, built with the existing Maven Wrapper. Open this
directory as a Maven project in IntelliJ IDEA. On Windows use `mvnw.cmd`.
The server binds to http://127.0.0.1:8080.

The persistent slice stores environments, operations, idempotency reservations,
and audit events in PostgreSQL, with Flyway migrations applied by a separate process.
See the [API contract](../docs/api.md) for schemas and validation.
An opt-in worker claims create and delete operations with PostgreSQL leases, provisions
platform-owned namespaces and an approved sample Deployment in local kind,
and records durable progress and sanitized failures in operation detail.
Creation acceptance remains `REQUESTED` / `PENDING`; only observed workload readiness
permits `READY`. Deletion is explicit; TTL expiration does not trigger cleanup.
The [manual expiration CLI](../automation/README.md) defaults to dry-run and
requests deletion only with `--execute`; no expiration daemon is installed.
Omitted or `null` TTL defaults to eight hours.
The [frontend portal](../frontend/README.md) supports environment creation,
confirmed deletion, list/detail reads, and paginated operation history. It uses
only the backend API and never accesses Kubernetes directly. Environment responses include
a nullable latest-operation summary; existing operation detail fields are unchanged.
Environment DTOs also include `teamId` and `createdBy`, both nullable for legacy
rows. Unowned environments remain operator-managed; there is no ownership transfer.

## Run locally

Docker with Compose is required. This is the bounded disposable-target
implementation, not production rollout or acceptance of the
[proposed dispositions](../docs/decisions/db-role-separation-prerequisite-review.md).
Before applying privileged SQL, confirm the target, accountable approver and
independent bootstrap/recovery credential custody under that record. ADR statuses
are unchanged. Do not apply this fresh-target bootstrap to an existing volume.

### 1. Bootstrap a fresh disposable target

From the repository root, use a new explicitly selected Compose project/volume
(keep that project selected for **all** subsequent commands):

```sh
cp .env.example .env
# Set distinct POSTGRES_PASSWORD (administrator) and DB_PASSWORD (runtime) in
# the ignored .env; restrict access with chmod 600 .env. Never source it.
export COMPOSE_PROJECT_NAME=platform-role-split
docker compose up -d --wait postgres
docker compose exec postgres sh -c \
  'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

In this administrator-only interactive `psql` session:

```sql
\i /opt/platform-database/bootstrap.sql
\password platform_migration
\password platform_runtime
ALTER ROLE platform_migration LOGIN;
\q
```

Use distinct generated secrets from the approved credential store. `\password`
prompts without putting plaintext in SQL history or process arguments. Set the
runtime password to the runtime secret in `.env`. Bootstrap creates NOLOGIN,
non-superuser roles; it rejects existing objects without explicit transition
opt-in and always rejects existing role names.
The administrator retains database ownership; `platform_migration` owns only
the `public` schema and its migration-created objects. Neither login has role
memberships, role administration, database `CREATE` or `TEMP`.

### 2. Migrate independently, then close migration access

Build the jar with `cd backend && ./mvnw -DskipTests package`. In a **separate,
short-lived Bash subshell**, from `backend/`:

```bash
(
  unset DB_PASSWORD POSTGRES_PASSWORD
  export MIGRATION_DB_URL=jdbc:postgresql://127.0.0.1:5432/platform_lab
  export MIGRATION_DB_USERNAME=platform_migration
  read -r -s -p 'Migration password: ' MIGRATION_DB_PASSWORD; echo
  export MIGRATION_DB_PASSWORD
  java -Dloader.main=io.github.dcfaight.platformlab.DatabaseMigration \
    -cp target/backend-0.0.1-SNAPSHOT.jar \
    org.springframework.boot.loader.launch.PropertiesLauncher
)
```

Adjust the URL to the selected local database/port. This entry point invokes
Flyway only: no Spring context, HTTP server, worker, `clean`, or runtime-secret
fallback. Exit status is authoritative; errors are deliberately sanitized.
Do not use Maven debug output, JDBC URLs containing credentials, shell tracing,
literal passwords in SQL/CLI arguments, or print `docker compose config` (use
`config --quiet`). Environment secrets remain visible to trusted host/Docker
administrators; this is not a secret-management service.

**On success or failure**, reconnect through the same administrator-only `psql`
path and close migration access; do not leave it enabled while investigating:

```sql
ALTER ROLE platform_migration NOLOGIN;
SELECT pg_terminate_backend(pid) FROM pg_stat_activity
WHERE usename = 'platform_migration';
```

On success only, apply explicit runtime grants and enable runtime:

```sql
\i /opt/platform-database/runtime-grants.sql
ALTER ROLE platform_runtime LOGIN;
```

Only the approved named objects/columns are granted. Future migration-created
tables, sequences and functions remain inaccessible by default (including
`PUBLIC EXECUTE` on new functions); review grants separately after migrations.
Runtime can read environments/operations/idempotency/events and team membership,
insert requests/operations/audit/events, update lifecycle columns, and use the
event identity sequence. It cannot delete application history, read audit rows,
change team ownership/creator fields, mutate schema, administer roles/grants,
write teams/memberships, or access Flyway history. See the
[exact matrix](../docs/decisions/db-role-separation-prerequisite-review.md#postgresql-privilege-inventory).
Trusted membership administration remains separate interactive administrator SQL.

### 3. Start only with runtime credentials

In a fresh Bash shell, without sourcing the Compose/admin environment:

```bash
unset POSTGRES_PASSWORD MIGRATION_DB_PASSWORD MIGRATION_DB_USERNAME MIGRATION_DB_URL
export DB_URL=jdbc:postgresql://127.0.0.1:5432/platform_lab
export DB_USERNAME=platform_runtime
read -r -s -p 'Runtime password: ' DB_PASSWORD; echo
export DB_PASSWORD
cd backend
./mvnw spring-boot:run
```

Use only these `DB_*` values in IntelliJ too. Configure Basic accounts below
before using protected endpoints. API and optional worker startup both have
`spring.flyway.enabled=false`; never override it for runtime. Readiness tests
connectivity, not schema currency: verify successful migration/grants before
starting processes. Never supply migration or bootstrap secrets to API/worker.

| Variable | Default |
|---|---|
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/platform_lab` |
| `DB_USERNAME` | `platform_runtime` |
| `DB_PASSWORD` | Required; no default |

No Kubernetes cluster is required for API-only use. No CORS configuration is
added; keep this API in a trusted local environment (loopback only).

### Existing databases, stop and rollback

- Stop **all** host API/workers (including IDE runs) and Compose backend/frontend
  before privileged changes. Do not mix old binaries with upgraded schema; V7
  changes the idempotency conflict key. A plain restart no longer migrates.
- For an existing volume, first retain it stopped and confirm its data/restore
  authority. Rehearse on an independently approved disposable copy first; review
  rows, Flyway history/checksums, owners, ACL/default/PUBLIC grants and memberships.
  The guarded path supports only the known V1–V7 schema, with database and all
  application objects owned by the connected bootstrap administrator, no extra
  schemas/routines/tables, and no preexisting split role names. Older schemas
  or unexpected inventories require separate review; never baseline/repair
  history, use `REASSIGN OWNED`, or delete data to make setup pass.
- After that review, in the isolated copy's administrator `psql` session:

  ```sql
  SET platform.role_split_transition = 'on';
  \i /opt/platform-database/bootstrap.sql
  RESET platform.role_split_transition;
  ```

  This explicitly transfers only inventoried tables (and their identity sequence)
  to migration ownership, removes PUBLIC access, and creates the restricted
  roles in one transaction. It does not rewrite application rows or Flyway
  history. Then use steps 1–3's password, independent migration/validation, grant,
  login-revocation and runtime-start flow. Compare retained rows/history and the
  effective privilege matrix on the copy before considering an approved stopped
  target transition. Any script error is a stop condition, not permission to
  loosen its guards. Existing database-specific grants to other identities
  still require administrator inventory; this is not a general ACL repair tool.
- Stop host processes with Ctrl-C; `docker compose down` (or
  `docker compose -f compose.deploy.yaml down`) retains the selected volume.
  Verify project/target before any command. `down --volumes` is destructive and
  is only for explicitly approved disposable test data, never a rollback.
- On failure keep runtime stopped. Through the independent administrator path,
  disable affected logins and terminate sessions (`NOLOGIN` alone does not end
  existing sessions). For runtime revocation use `ALTER ROLE platform_runtime
  NOLOGIN` and the termination query above with `usename = 'platform_runtime'`.
  Close migration access on every exit. Review partial migration state before
  retrying; do not auto-clean or widen grants.
- Roll back compatible binaries/config only **with the runtime identity**.
  Never restore the old shared administrator password to API/worker. There is no
  automatic down-migration. Any required restore goes to a separately approved
  isolated target; verify owners, actual/default/PUBLIC ACLs, role memberships,
  sequence privileges and stale credential revocation before enabling mutations.
- Rotate/revoke through the confirmed custodian, update only the consuming
  process, terminate old sessions and verify replacement before resuming.
  Proposed 90-day rotation/one-hour migration and incident windows in the
  disposition record are not automated or newly accepted here. Retain only
  sanitized revision/target, approval, ACL/ownership and expected/actual results,
  never passwords, hashes, connection strings containing secrets or data exports.

## Container deployment and probes

The [deployment runbook](../infra/README.md#hardened-single-host-deployment-api-only)
uses the multi-stage Dockerfile and `deploy` profile. The image contains a Java 17
JRE, the packaged application, and curl for health checks, not Maven or a JDK.
It runs as UID/GID `10001`, with a read-only root filesystem and bounded `/tmp`
in Compose. Base images are digest-pinned; Maven Wrapper and dependency versions
come from this repository. Rebuild and update pins regularly for security fixes.

The profile requires explicit `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`, binds
`0.0.0.0:8080` **only inside the private container network**, disables the kind
worker, ignores forwarded headers, and allows 20 seconds for graceful shutdown
(Compose waits 30 seconds). Never publish that HTTP port or mount a kubeconfig.
The default host-run profile still binds loopback and supports the kind worker.

`GET /actuator/health/liveness` and `GET /actuator/health/readiness` are anonymous,
status-only probes (`{"status":"UP"}`); broader Actuator paths remain protected.
Readiness includes PostgreSQL connectivity and returns `503` when unavailable;
liveness deliberately does not depend on the database. Neither measures worker
progress or provisioned workload health. The TLS proxy does not expose Actuator.
Only health is exposed by Actuator; no environment/configuration dump is enabled.

## Authentication and authorization

All API reads and writes require HTTP Basic credentials for an account configured
at startup. Only health probes remain anonymous. This is a bounded Basic/team
authorization boundary, not SSO or production tenant isolation.
See [docs/api.md](../docs/api.md#authentication-and-authorization)
for the exact rules and error responses.

| Role | Access |
|---|---|
| `OPERATOR` | Platform-wide reads, metrics, creation and deletion. Creation may specify an existing `teamId`; omission creates an unowned environment for compatibility. |
| `DEVELOPER` | List/detail/history/operation reads and deletion only for environments owned by current member teams. Creation requires an explicit member `teamId`; missing, unknown or nonmember teams return `403`. Metrics are denied. |
| `CLEANUP` | Exclusive automation role: list/detail discovery of all environments (including legacy), and expiry-guarded `DELETE /api/v1/environments/{id}?expiredBefore=…` only. No operation history/direct operation reads, metrics, creation or manual deletion. Cannot combine with another role. |

Accounts are configured with Spring Boot relaxed binding; only password
**hashes** are accepted, prefixed `{bcrypt}` or `{pbkdf2@SpringSecurity_v5_8}`.
Usernames match `[A-Za-z0-9][A-Za-z0-9._@-]{0,63}`; `local-development` is
reserved and duplicate (case-insensitive) names are rejected at startup.
With no accounts configured the backend still starts, logs a warning, and rejects
every API read and write. Generate a PBKDF2 hash without putting the password in shell
history (`$` in bcrypt hashes needs careful shell quoting, so PBKDF2 is simpler):

```sh
python3 -c 'import hashlib,secrets,getpass; pw=getpass.getpass(); s=secrets.token_bytes(16); print("{pbkdf2@SpringSecurity_v5_8}"+(s+hashlib.pbkdf2_hmac("sha256",pw.encode(),s,310000,32)).hex())'
```

Then, in the shell that starts the backend (or in the IntelliJ run configuration):

```sh
export PLATFORM_SECURITY_USERS_0_USERNAME=alice
export PLATFORM_SECURITY_USERS_0_PASSWORDHASH='<paste the printed hash>'
export PLATFORM_SECURITY_USERS_0_ROLES=OPERATOR
# Optional exclusive account for both cleanup CLI dry-run and execute:
export PLATFORM_SECURITY_USERS_1_USERNAME=cleanup-bot
export PLATFORM_SECURITY_USERS_1_PASSWORDHASH='<another hash>'
export PLATFORM_SECURITY_USERS_1_ROLES=CLEANUP
# Optional team-scoped developer account:
export PLATFORM_SECURITY_USERS_2_USERNAME=bob
export PLATFORM_SECURITY_USERS_2_PASSWORDHASH='<another independently generated hash>'
export PLATFORM_SECURITY_USERS_2_ROLES=DEVELOPER
```

Passwords and roles use the existing configuration; do not add account-config
team fields. A trusted SQL administrator provisions PostgreSQL
`platform_teams(id varchar(63))` and
`account_team_memberships(username varchar(64), team_id REFERENCES platform_teams(id))`.
After Flyway has created the tables, connect to the platform database with trusted
administrator credentials and run, for example:

```sql
BEGIN;
INSERT INTO platform_teams (id) VALUES ('payments') ON CONFLICT DO NOTHING;
INSERT INTO account_team_memberships (username, team_id)
VALUES ('bob', 'payments') ON CONFLICT DO NOTHING;
COMMIT;
```

Team IDs are lower-case slugs of 1–63 characters. `bob` must separately be a
configured `DEVELOPER` account. Membership usernames **must match the configured
account username spelling exactly**: Basic lookup may be case-insensitive, but
returns the canonical configured spelling and database membership equality is
exact. Use that spelling in trusted SQL; do not normalize it independently.
Membership revocation removes team-scoped access,
including replay; neither `createdBy` nor an old idempotency key grants access.
There is no team/membership management API or automatic legacy adoption.

`GET /api/v1/me` verifies credentials and returns, for example,
`{"username":"bob","roles":["DEVELOPER"],"teams":["payments"]}`; the
portal uses it to sign in. Audit events record the authenticated username as
`actor` (never a caller-supplied header). Rows written before this change keep
`local-development`; the V5 migration widens the actor constraint to the username
format. Request completion logs still do not record the actor.

New idempotency reservations are scoped to caller and team, including an unowned
operator scope. Legacy global reservations remain replay-compatible for operators
only. Equivalent authorized replay returns the original acceptance without a new
audit event; different callers/team scopes can independently reuse a key.

Remaining gaps, stated plainly: credentials travel in every API request as
plain HTTP Basic, which is only acceptable on loopback (any other deployment needs
TLS); there is no SSO/OIDC, session expiry, rate limiting, or account lockout;
accounts are static configuration with no runtime management. Operator access is
intentionally platform-wide; Basic/team authorization is not strong tenant
isolation. No OIDC, GitOps or ownership transfer is added, and worker RBAC/ingress
remain unchanged.

### V7 upgrade compatibility

Back up PostgreSQL before upgrading. V7 changes the idempotency primary key from
`key` to `(scope, key)`. Stop **all old backend processes** before starting the
upgraded backend and Flyway: this is not a mixed-version rolling migration,
because the old `ON CONFLICT (key)` statements no longer work afterward.
Environment, operation, idempotency and audit rows are retained.

## Enable local provisioning

Create the dedicated cluster using [infra/README.md](../infra/README.md), then,
in the shell with the same `DB_*` exports:

```sh
export WORKER_ENABLED=true
export PLATFORM_KUBERNETES_MODE=local-kind
export PLATFORM_KUBERNETES_KUBECONFIG="$HOME/.kube/config"
cd backend
./mvnw spring-boot:run
```

The worker runs inside Spring Boot; there is no separate worker process.
Multiple backend instances may share PostgreSQL. Each uses a unique claim token
and short database transactions; Kubernetes calls never hold database row locks.
Due operations are claimed with `FOR UPDATE SKIP LOCKED`, and abandoned leases
are reclaimable after restart. Old/expired claim holders cannot publish results.
Recovery waits for the persisted lease to expire; startup does not revoke leases
that another backend may still own. Reclaimed operations retain the `CLAIMED`
event type with code `LEASE_RECOVERED` and an explanatory message in operation detail.

The client explicitly selects `kind-developer-platform-lab`, requires a loopback
HTTPS API server, and rejects executable credential plugins. It does not use the
current context as permission to reach arbitrary clusters. Keep the kubeconfig
trusted and local; never commit it. Only persisted `env-<UUID without hyphens>`
namespaces and the fixed `sample-service` Deployment/image are allowed.
Unowned resources are not adopted or overwritten.

The default `platform.kubernetes.mode=local-kind` preserves those restrictions.
The separate [K3s package](../infra/README.md#local-k3s-platform) explicitly sets
`PLATFORM_KUBERNETES_MODE=in-cluster` and `PLATFORM_WORKER_ENABLED=true`, retaining
the `deploy` profile's network/probe settings. That mode uses the fixed cluster
API HTTPS endpoint and dedicated projected service-account CA/token files, not
kubeconfig or the current context. Unknown modes and missing credentials fail
closed. Do not enable it in Compose or mount an administrator kubeconfig.
The worker begins processing existing pending work when enabled.

Default worker settings under `platform.worker`:

| Property | Default |
|---|---|
| `enabled` | `false` (`WORKER_ENABLED`) |
| `poll-delay-ms` | `1000` |
| `lease-duration` | `30s` |
| `max-attempts` | `5` |
| `retry-backoff` | `2s` |
| `readiness-poll-interval` | `5s` |
| `readiness-timeout` | `5m` |

Readiness polls do not consume the transient-error retry budget. Transient
Kubernetes errors use bounded retries; validation, authorization, and ownership
failures are terminal. A readiness deadline prevents indefinite waiting.
Operation detail retains UTC timestamps, attempt counts, sanitized messages,
and progress events, including lifecycle milestones such as `NAMESPACE_CREATED`
and `NAMESPACE_DELETE_REQUESTED`. `GET /api/v1/operations/metrics` summarizes
status counts, durations, and worker backlog/lease health (`OPERATOR` only)
(see [API contract](../docs/api.md#read-operation-metrics)). Lease expiry permits at-least-once execution; idempotent
Kubernetes reconciliation and database fencing protect repeated work.
Queued work and retry delays survive restart, as do provisioned/waiting checkpoints
and terminal results. After a crash before a provisioning checkpoint, the worker
re-reads owned resources and only creates or updates what is missing or different.
Even after the last provisioning attempt, matching resources can be checkpointed
and continue to readiness without another write; additional mutations fail with
`ATTEMPTS_EXHAUSTED`. Restarts do not reset attempt budgets or deadlines.

## Safe asynchronous deletion

`DELETE /api/v1/environments/{id}` returns `202 Accepted`, a `Location` pointing
to `/api/v1/operations/{operationId}`, and an `AcceptedEnvironmentResponse`:

```json
{"environmentId":"<uuid>","operationId":"<uuid>","environmentStatus":"DELETING","operationStatus":"PENDING"}
```

No request body or idempotency key is required. Environment row locks and a
partial unique active-operation index serialize concurrent requests. Repeats
reuse the active `DELETE_ENVIRONMENT` operation and return its durable `PENDING`
or `RUNNING` status. `REQUESTED`, `PROVISIONING`, and `DELETED` return `409`;
`READY`, `FAILED`, and `DELETE_FAILED` permit a new deletion. Malformed/unknown
IDs return `400`/`404`. After terminal failure, retrying creates a new operation
and audit record without replacing history.

The optional `expiredBefore` query parameter is an ISO-8601 instant no later than
the backend clock. Under the environment lock it restricts **new** deletion to
expired `READY`/`FAILED` records, returning `409` for unexpired or `DELETE_FAILED`
records. Active deletes are reused; the normal unguarded manual retry remains
unchanged. Cleanup rechecks detail and uses this atomic guard rather than claiming
a client read removes lifecycle/expiry races. See the [API contract](../docs/api.md#guarded-expiration-deletion).

API errors use `INVALID_REQUEST` (`400`, malformed UUID), `NOT_FOUND` (`404`),
`ENVIRONMENT_STATE_CONFLICT` (`409`, disallowed state or active create), and
`INVALID_ENVIRONMENT_NAMESPACE` (`409`, noncanonical persisted namespace).

The worker requires the canonical namespace, matching managed-by/environment
labels **and** annotations, and a persisted original CREATE operation. Newly
provisioned namespaces additionally carry the original operation ID in the
`platform-lab/create-operation-id` label and annotation. If either marker is
present, both must match that persisted original operation; partial or mismatched
markers fail with `KUBERNETES_OWNERSHIP_CONFLICT`.

For compatibility with existing platform environments, deletion also accepts
the original ownership scheme when **both** create-operation markers are absent.
All canonical-name, managed/environment label/annotation, persisted-original-
operation, lease, and UID/resourceVersion checks still apply. No namespace is
relabelled or adopted. Missing managed/environment ownership or a missing original
CREATE operation fails closed, including for legacy namespaces. Namespace DELETE
requests include both UID and resourceVersion preconditions; ownership races
are retried after a fresh read, never by forcing deletion.

Accepted Kubernetes deletion is not completion. A terminating namespace stays
`DELETING` until a subsequent read observes absence, then becomes `DELETED`.
Transient failures use the existing retry budget/backoff; deletion uses the
existing `readiness-timeout` as its durable deadline (`DELETION_TIMEOUT`).
Terminal failure sets `DELETE_FAILED`. Expired leases cannot publish results,
and lease ownership is rechecked immediately before issuing a Kubernetes
DELETE. After an accepted DELETE followed by a crash, reclamation reads the
namespace again, including after the last attempt, rather than recreating
resources or assuming deletion completed.

There is exactly one original `CREATE_ENVIRONMENT` operation per environment,
enforced by a unique index. Claims reference that exact operation, with no
oldest/latest-operation fallback. Missing or mismatched namespace ownership
fails closed. CREATE execution checks its lease before starting and immediately
before each namespace, resource-budget, LimitRange, or Deployment create or
replace. New/retried CREATE work reconciles the owned
`platform-environment-budget` ResourceQuota and `platform-container-defaults`
LimitRange before the sample Deployment. See the
[resource-budget runbook](../infra/README.md#provisioned-environment-resource-budgets)
for exact limits and operator checks. Existing READY namespaces are not
continuously reconciled or automatically migrated; legacy namespaces are not
assigned new create-operation ownership.

Database lease checks and Kubernetes requests are not one atomic transaction.
A process can pause after its final lease check or leave an HTTP request in
flight when its lease expires; a late CREATE could therefore finish after
failure/deletion acceptance. Preflights reduce this window but cannot eliminate
it: a database lease cannot revoke an in-flight Kubernetes request. The same
limitation applies to an already-issued DELETE; its UID/resourceVersion
preconditions protect the inspected namespace, not the database lease.
Preventing this residual cross-system race requires a Kubernetes-side
admission/fencing controller or equivalent external coordination. Database
fencing still prevents stale holders from publishing operation results.

The default deletion deadline is five minutes from the first claim; reclaiming
does not reset it. `DELETION_TIMEOUT` reports
`Deletion did not complete within its limits.` Other durable failure codes are
`KUBERNETES_OWNERSHIP_CONFLICT`, `KUBERNETES_VALIDATION_FAILED`,
`KUBERNETES_UNAVAILABLE` (retryable), `KUBERNETES_REQUEST_REJECTED`,
`KUBERNETES_CONFIG_INVALID`, and `INTERNAL_ERROR`. Only absent namespaces can
complete successfully; no finalizers are removed to force completion.

DELETE crash retries use `max-attempts` until an accepted/terminating namespace
observation is persisted. An exhausted reclaim performs a final read: absence
succeeds and termination continues polling, but an active namespace fails with
`ATTEMPTS_EXHAUSTED` / `Deletion did not complete within its attempt limits.`
without another DELETE. Normal termination polls do not consume attempts.
Repeated crashes during reconciliation remain bounded by the original durable
deadline, including crashes before the accepted DELETE could be persisted.

This slice observes initial Deployment readiness, not ongoing health after
success. It does not provide custom manifests/images, Services, ingress,
scheduled cleanup, or production RBAC/SSO. The portal supports
creation and confirmed deletion through the backend API; it has no direct
Kubernetes access.
Expired TTL metadata does not remove resources.

## Manual API checks

Run these in another terminal with an `OPERATOR` account configured as above.
`-u "$OPERATOR_USERNAME"` makes curl prompt for the password, keeping it out of
shell history and process arguments. Without credentials, API reads and writes
return `401 UNAUTHENTICATED`. The first request returns `202`, both IDs,
`REQUESTED` / `PENDING`, a `Location` operation URL, and `X-Request-Id`:

```sh
OPERATOR_USERNAME=alice
curl -i -u "$OPERATOR_USERNAME" -X POST http://127.0.0.1:8080/api/v1/environments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: local-demo-1' \
  -d '{"name":" demo ","templateId":"sample-service"}'

# Equivalent normalized request: same IDs, states, and Location; no new writes.
curl -i -u "$OPERATOR_USERNAME" -X POST http://127.0.0.1:8080/api/v1/environments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: local-demo-1' \
  -d '{"name":"demo","templateId":"sample-service","ttlHours":8}'

# Different normalized input with the same key: 409 IDEMPOTENCY_CONFLICT.
curl -i -u "$OPERATOR_USERNAME" -X POST http://127.0.0.1:8080/api/v1/environments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: local-demo-1' \
  -d '{"name":"different","templateId":"sample-service","ttlHours":8}'

curl -i -u "$OPERATOR_USERNAME" 'http://127.0.0.1:8080/api/v1/environments?page=0&size=20'

# Replace these values with the IDs from the first response.
ENVIRONMENT_ID='<environmentId>'
OPERATION_ID='<operationId>'
curl -i -u "$OPERATOR_USERNAME" "http://127.0.0.1:8080/api/v1/environments/$ENVIRONMENT_ID"
curl -i -u "$OPERATOR_USERNAME" "http://127.0.0.1:8080/api/v1/environments/$ENVIRONMENT_ID/operations"
curl -i -u "$OPERATOR_USERNAME" "http://127.0.0.1:8080/api/v1/operations/$OPERATION_ID"
curl -i -u "$OPERATOR_USERNAME" http://127.0.0.1:8080/api/v1/operations/metrics

# Malformed UUID: 400. Valid but unknown UUID: 404.
curl -i -u "$OPERATOR_USERNAME" http://127.0.0.1:8080/api/v1/environments/not-a-uuid
curl -i -u "$OPERATOR_USERNAME" http://127.0.0.1:8080/api/v1/operations/00000000-0000-0000-0000-000000000000
```

The operator examples deliberately omit `teamId`, so the new environment stays
unowned/operator-managed. To create with an explicit owner, add
`"teamId":"payments"` and use a fresh key. After provisioning the developer and
membership above, use:

```sh
DEVELOPER_USERNAME=bob
curl -i -u "$DEVELOPER_USERNAME" http://127.0.0.1:8080/api/v1/me
curl -i -u "$DEVELOPER_USERNAME" http://127.0.0.1:8080/api/v1/environments \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: developer-demo-1' \
  -d '{"name":"team demo","templateId":"sample-service","teamId":"payments","ttlHours":8}'
# Only member-team environments and totals, never the unowned operator example:
curl -i -u "$DEVELOPER_USERNAME" 'http://127.0.0.1:8080/api/v1/environments?page=0&size=20'
# Metrics require OPERATOR: this returns 403.
curl -i -u "$DEVELOPER_USERNAME" http://127.0.0.1:8080/api/v1/operations/metrics
```

To verify persistence, stop the backend, run `docker compose restart postgres`
from the repository root, then restart the backend with the same configuration.
Repeat the replay and detail requests: IDs and records are retained. Even
`docker compose down` followed by `docker compose up -d --wait postgres`
retains the named volume. `docker compose down --volumes` intentionally
discards all local database data, including idempotency history.

## Tests

From `backend/`, with Docker running:

```sh
docker info
./mvnw verify
```

Backend integration tests use Testcontainers with `postgres:18.6`; they require
a working Docker daemon and do not skip when Docker is unavailable. They use
their own database container, not the Compose database, and need no local
`DB_*` exports. CI checks Docker availability before Maven verification.
Python 3.12 (`python3` on PATH) is also required: a real HTTP/backend/PostgreSQL
test invokes the cleanup CLI against controlled expired fixtures, checking
read-only dry-run and durable deletion requests without Kubernetes access.

Kubernetes HTTP tests exercise the actual client against a local API test
server. The smaller cluster test is opt-in locally and runs in its own CI job:

```sh
# After creating the dedicated kind cluster:
PLATFORM_KIND_TEST=true ./mvnw -Dtest=KindProvisioningTests,KindWorkerFlowTests test
```

These tests provision and re-apply a unique namespace/Deployment and exercise
HTTP acceptance through the worker to real workload readiness and namespace
deletion (the full-flow test also uses Testcontainers PostgreSQL). The smaller
provisioning test leaves resources in the disposable cluster; use the manual
whole-cluster reset in the infrastructure guide when finished.
