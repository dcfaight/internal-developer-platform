# Infrastructure

This is the operational runbook and historical evidence layer. For current
status, remaining work, and blockers, start with the
[canonical roadmap table](../docs/operating-model-and-sequence.md#current-delivery-gates).
Record new validation outcomes there with links to retained evidence; dated
results below do not close other gates.

## Local kind development cluster

`kind/cluster.yaml` defines a dedicated, single-node local development cluster.
With Docker and kind installed, from the repository root:

```sh
kind create cluster --name developer-platform-lab --config infra/kind/cluster.yaml
kubectl --context kind-developer-platform-lab get nodes
```

Remove the local cluster when finished:

```sh
kind delete cluster --name developer-platform-lab
```

The backend worker builds the approved namespace and sample Deployment manifest
in its Kubernetes package. The separate [K3s packaging](#local-k3s-platform)
in `kubernetes/` deploys the platform itself, not those sample workloads.
There is no arbitrary manifest or image input.

Enable the worker as described in [backend/README.md](../backend/README.md).
It requires the `kind-developer-platform-lab` kubeconfig context and a loopback
HTTPS API server; remote clusters are deliberately unsupported. Use a host-run
backend, not a container with a different loopback network. Do not commit
kubeconfig files or secrets. The kind admin credentials are for this disposable
development cluster only, not a production permission model.

Provisioned namespaces are retained after failures or TTL expiry until explicitly
deleted through the portal/API. The leased worker removes only owned namespaces
and reports completion after absence is observed; see the [local lifecycle
runbook](../docs/demo.md#4-delete-safely-and-verify-absence). Expiration
cleanup is available through the manually invoked API-only CLI, not a scheduler.
Deleting the entire disposable kind cluster above is a separate manual cluster
reset, not the platform deletion workflow.

## Provisioned environment resource budgets

During each provisioning attempt, the worker reconciles an owned
`ResourceQuota/platform-environment-budget` and
`LimitRange/platform-container-defaults` before creating or reconciling
`Deployment/sample-service`. These controls apply to newly provisioned
namespaces; they are not a continuous controller.

The quota is per namespace:

| Resource | Budget |
|---|---:|
| Total CPU requests | `2` cores |
| Total memory requests | `2Gi` |
| Total CPU limits | `4` cores |
| Total memory limits | `4Gi` |
| Pods | `10` |

The container LimitRange supplies default requests of `100m` CPU and `64Mi`
memory, defaults limits to `500m` CPU and `256Mi` memory, and caps one
container at `1` CPU and `1Gi` memory. The approved sample explicitly requests
`50m` CPU/`32Mi` memory and limits `250m` CPU/`128Mi` memory, below both the
per-container maxima and namespace totals. These conservative starter budgets
leave room for a small development workload; they are not capacity estimates
for the target developer population.

Inspect a provisioned namespace with:

```sh
kubectl --context kind-developer-platform-lab -n env-<id-without-hyphens> \
  get resourcequota platform-environment-budget -o yaml
kubectl --context kind-developer-platform-lab -n env-<id-without-hyphens> \
  get limitrange platform-container-defaults -o yaml
kubectl --context kind-developer-platform-lab -n env-<id-without-hyphens> \
  describe resourcequota platform-environment-budget
```

The worker reconciles only resources carrying matching platform ownership
labels and annotations, with lease checks before each create or replace and
resourceVersion-guarded replacement. Conflicts are not adopted or overwritten;
Kubernetes API failures use the existing sanitized operation failure handling.
An already-READY namespace is **not** automatically migrated or continuously
reconciled. No existing environment or shared cluster is changed by this
increment; a future migration needs an explicit, reviewed operator procedure.

Quotas limit aggregate resource consumption but are not a complete isolation or
security boundary. They do not constrain network access, permissions, kernel
sharing, or hostile workloads. The worker's existing cluster-scoped
namespace `get/create/delete` permissions remain a separate, documented
limitation; use only the dedicated disposable cluster described here.

### Validation evidence for this increment

- `cd backend && ./mvnw --batch-mode verify`: passed, 125 tests with no failures;
  five kind-gated tests were skipped because the opt-in variable was unset.
- `cd backend && PLATFORM_KIND_TEST=true ./mvnw --batch-mode -Dtest=KindProvisioningTests#resourceQuotaRejectsAnOverBudgetPodOnDisposableKind test`:
  passed against the dedicated local kind cluster, verifying both LimitRange
  defaults and API rejection of an over-budget Pod.
- `python3 -m unittest discover -s automation/tests -v`: passed, 80 tests;
  seven opt-in deployment/K3s tests were skipped because no target was selected.
- `kubectl kustomize infra/kubernetes`: passed.

In this increment's earlier local-kind validation, the broader
`PLATFORM_KIND_TEST=true ./mvnw --batch-mode
-Dtest=KindProvisioningTests,KindWorkerFlowTests test` run did not pass in this
local environment: one existing sample readiness check timed out after 120
seconds, and the worker flow remained `WAITING`. The disposable cluster showed
repeated sample HTTP probe timeouts and non-ready CoreDNS/local-path pods. The
direct quota/LimitRange test passed independently. These are results for that
dated run, not a new failure report; see the
[current roadmap](../docs/operating-model-and-sequence.md) for the later K3s
lifecycle evidence and remaining gates. The test cluster was deleted after
validation. Frontend tests were not run because frontend code was unchanged.

## Hardened single-host deployment (API-only)

This is a separate, opt-in Compose project, not an overlay on the developer
Compose file. Run commands from the repository root with Docker Compose 2.24.4+
(`!reset` removes the inherited PostgreSQL host port), Docker BuildKit, Python 3.12
and OpenSSL. Existing `docker compose up postgres`, host-run backend/Vite, kind
provisioning, deletion and cleanup commands remain unchanged.

**Boundary:** this packages the portal and persistent API, not a production
provisioner. The backend's `deploy` profile disables the worker. Create requests
are authenticated and persisted but stay pending; accepted deletions also need a
worker to complete. Do not point a host worker at this separate deployment's
database or mount kind admin kubeconfig into a container. Use the existing
[local demo](../docs/demo.md) for the complete lifecycle. The cleanup CLI remains
trusted-loopback HTTP only and is not extended to this TLS endpoint in this slice.

### Configure and start

1. Use a **separate database password**, operator username and slow password hash.
   Keep them in your deployment environment or an access-restricted, ignored
   `.env.deploy` file (Compose accepts `--env-file .env.deploy`). Never commit
   credentials, hashes, certificates or private keys. Environment values are
   visible to Docker administrators; this is not external secret management.
   Generate an operator hash with the existing
   [backend recipe](../backend/README.md#authentication-and-authorization).
   Export these variables in the deployment shell:

   ```sh
   read -r -s -p 'Deployment database password: ' POSTGRES_PASSWORD; echo
   export POSTGRES_PASSWORD
   read -r -s -p 'Separate runtime database password: ' DB_PASSWORD; echo
   export DB_PASSWORD
   export DEPLOY_OPERATOR_USERNAME=alice
   # Paste the generated hash, not the plaintext login password:
   read -r -s -p 'Operator password hash: ' DEPLOY_OPERATOR_PASSWORD_HASH; echo
   export DEPLOY_OPERATOR_PASSWORD_HASH
   export TLS_CERT_FILE=/absolute/path/to/tls.crt
   export TLS_KEY_FILE=/absolute/path/to/tls.key
   export HTTPS_PORT=8443
   ```

   All six credentials/certificate variables must be nonempty or Compose refuses
   to start. Certificate files must already exist. The certificate must match the
   hostname used by clients, with a complete chain trusted by those clients.
   nginx runs as UID/GID `101:101`: ensure that identity can read the key, e.g.
   on a rootful Linux Docker host, `sudo chown root:101 "$TLS_KEY_FILE"` and
   `sudo chmod 640 "$TLS_KEY_FILE"`; do not make a real deployment key world-readable.
   Adjust ownership for rootless/user-namespace mappings. Mounts are read-only.

2. For **disposable localhost testing only**, generate a one-day self-signed
   certificate outside the checkout, then explicitly trust that certificate for
   test requests:

   ```sh
   TLS_DIR="$(mktemp -d /tmp/platform-tls.XXXXXX)"
   export TLS_CERT_FILE="$TLS_DIR/tls.crt"
   export TLS_KEY_FILE="$TLS_DIR/tls.key"
   openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
     -keyout "$TLS_KEY_FILE" -out "$TLS_CERT_FILE" -subj /CN=localhost \
     -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1'
   sudo chown root:101 "$TLS_KEY_FILE"
   sudo chmod 640 "$TLS_KEY_FILE"
   ```

3. Build, provision and migrate **before** starting runtime. This role split is
   for a fresh disposable Compose target only; it does not approve production
   deployment or change ADR status. Complete the authority/custody checks and
   [bootstrap procedure](../backend/README.md#1-bootstrap-a-fresh-disposable-target)
   first. Select a new project/volume; never apply bootstrap to an existing
   deployment volume:

   ```sh
   export COMPOSE_PROJECT_NAME=platform-role-split-deploy
   docker compose -f compose.deploy.yaml config --quiet
   docker compose -f compose.deploy.yaml build --pull
   docker compose -f compose.deploy.yaml up -d --wait postgres
   docker compose -f compose.deploy.yaml exec postgres sh -c \
     'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
   ```

   In that interactive administrator session apply
   `\i /opt/platform-database/bootstrap.sql`, set distinct migration/runtime
   secrets with `\password platform_migration` / `\password platform_runtime`,
   and `ALTER ROLE platform_migration LOGIN;`. Keep runtime NOLOGIN until grants
   are verified. Exit `psql`, then invoke the independent migration service:

   ```bash
   (
     read -r -s -p 'Migration password: ' MIGRATION_DB_PASSWORD; echo
     export MIGRATION_DB_PASSWORD
     docker compose -f compose.deploy.yaml run --rm migration
   )
   ```

   On **every exit**, through administrator `psql`, disable migration login and
   terminate its sessions using the
   [backend procedure](../backend/README.md#2-migrate-independently-then-close-migration-access).
   On success only, apply `\i /opt/platform-database/runtime-grants.sql`, verify
   the matrix and `ALTER ROLE platform_runtime LOGIN;`. The runtime password must
   match `DB_PASSWORD` above. Only then:

   ```sh
   docker compose -f compose.deploy.yaml up -d --wait --wait-timeout 180
   curl --fail --cacert "$TLS_CERT_FILE" https://localhost:8443/
   curl --fail --cacert "$TLS_CERT_FILE" -u "$DEPLOY_OPERATOR_USERNAME" \
     https://localhost:8443/api/v1/environments
   ```

   Open `https://localhost:8443` in a browser that trusts the configured certificate.
   Sign in using the configured operator's **plaintext** password, not its hash.
   The frontend publishes only `127.0.0.1:${HTTPS_PORT}:8443`; adjust the example
   URL if changing the port. Do not bypass TLS verification or transmit Basic
   credentials to an HTTP URL. Public/interface binding is intentionally not
   provided; Basic-authenticated, team-scoped API access plus TLS does not establish
   safe public exposure. Health probes remain anonymous and private.

### Runtime and operations

- Backend and frontend images use digest-pinned build/runtime bases, separate
  build stages, numeric non-root users, no build tools in runtime images,
  read-only roots, writable size-bounded tmpfs, dropped capabilities,
  `no-new-privileges`, CPU/memory/PID limits and init process signal forwarding.
  Only nginx can reach the API network; backend and the explicit, short-lived
  migration service can reach the database network. Those networks are internal;
  only the frontend also joins an edge
  network so Docker can publish its loopback TLS port. No backend or database
  port is published.
  Docker administrators and the host remain trusted. Compose provides limits,
  not scheduler resource requests or multi-host availability.
- PostgreSQL reuses the existing pinned image and persistent volume definition
  but runs in the **separate** `platform-lab-deploy` project/volume. It needs a
  writable data directory and its standard startup privileges; application
  container restrictions do not apply to it. Its administrator secret is never
  passed to the backend. The backend receives only `platform_runtime` credentials;
  the opt-in migration service receives only `platform_migration` credentials.
- Startup waits for PostgreSQL and backend readiness before starting nginx.
  Backend readiness checks database access; liveness measures the process.
  Flyway is disabled at runtime; migrations/grants must already be complete.
  nginx's health check measures its own listener,
  not end-to-end API availability. Check backend readiness independently:

  ```sh
  docker compose -f compose.deploy.yaml ps
  docker compose -f compose.deploy.yaml exec -T backend \
    curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness
  docker compose -f compose.deploy.yaml logs --tail 100 backend frontend
  ```

  Database loss makes readiness fail, not liveness; do not create a restart storm
  for a dependency outage. Compose does **not** restart unhealthy-but-running
  services or withdraw traffic based on health. Requests may receive 5xx during
  outages; restore PostgreSQL and check readiness again. Restart policy covers
  process exit/host restart, not HA. nginx re-resolves the backend's Docker DNS
  name after replacement. Allow the 30-second stop grace period.
- Rebuild periodically with updated reviewed base-image digests and dependency
  pins. Builds use the committed Maven Wrapper and npm lockfile, but are not
  byte-for-byte reproducible or a container vulnerability scan. Trust the host,
  build network and registries; never disable package-download TLS verification.
  Back up and restore-test PostgreSQL before upgrades; Flyway migrations may
  prevent simple image rollback. Take a backup before applying new migrations.
  **V7 requires a coordinated backend stop/start, not a mixed-version rolling
  migration:** stop every old backend process before starting the upgraded
  backend/Flyway. The idempotency primary key changes from `key` to `(scope, key)`,
  so old `ON CONFLICT (key)` statements no longer work after migration. Existing
  rows and audit history are retained. Apply this rule to host-run, Compose and
  Kubernetes deployments; do not run old and upgraded backend versions together.
- Replace certificates before expiry and restart/recreate nginx to load them.
  HSTS lasts one year; use disposable localhost certificates only for testing.
  Rotate password hashes by recreating the backend. There is no certificate
  renewal, SSO, external secrets, rate limiting, strong multi-tenant isolation,
  database transport TLS, or worker service-account
  design in Compose. The separate K3s packaging below uses an explicit in-cluster
  worker identity; this does not enable provisioning in the Compose deployment.
- Stop with `docker compose -f compose.deploy.yaml down`; this retains the
  database volume. `down --volumes` permanently deletes deployment history:
  use only for disposable tests after confirming the project name.
  Follow [role-split stop/rollback](../backend/README.md#existing-databases-stop-and-rollback):
  keep API/workers stopped on migration/grant failure, revoke logins **and**
  sessions through independent administration, and never roll back to privileged
  runtime credentials.

### Validation

CI keeps frontend, backend, automation and real-kind checks, and adds an isolated
deployment smoke job. It generates disposable credentials/certificates, builds
both images, starts the restricted stack, verifies TLS with certificate validation,
static serving, same-origin API auth/idempotent creation/delete guards, internal
probes and Docker runtime restrictions, then removes its own project/volumes.
Run it explicitly with:

```sh
PLATFORM_DEPLOY_TEST=true python3 -m unittest discover -s automation/tests -p test_deployment.py -v
```

Without that opt-in, automation discovery only validates deployment configuration
and missing-setting failures. Backend tests separately verify database-loss
readiness versus liveness. A successful API probe does not prove kind provisioning,
namespace deletion, backup recovery, certificate renewal or production readiness.

## Local K3s platform

The role-separation provisioning in this slice is **Compose-only**, not a K3s
rollout. New backend images disable startup Flyway in every profile. Do not
promote this image to an unmigrated K3s database or assume the existing shared
Secret establishes role separation; that transition needs its own reviewed
credential/migration wiring. The isolated logical-restore fixture below uses
the independent migration entry point, not an API with startup Flyway enabled.

`kubernetes/` is a Kustomize package for a **separate, single-node K3s**
installation: one backend API, one frontend, PostgreSQL 18.6 with a 5Gi
`local-path` PVC, ClusterIP Services, generated ConfigMaps, external Secrets, and
a Traefik TLS Ingress. It reuses the existing Dockerfiles without changing Compose
or the host-run kind workflow. K3s deployment is implemented. Consult the
[current roadmap](../docs/operating-model-and-sequence.md) for outstanding
validation gates; the [pre-Kubernetes assessment](../docs/pre-kubernetes.md)
is archived historical context.

**Boundary:** unlike the API-only Compose deployment, this package explicitly
overrides the `deploy` profile with `PLATFORM_WORKER_ENABLED=true` and
`PLATFORM_KUBERNETES_MODE=in-cluster`. The worker runs inside the backend and
provisions sample namespaces in this same disposable cluster. No admin kubeconfig
is mounted; the host-kind workflow and Compose deployment remain unchanged.
Applying this version starts processing **existing pending operations** in its
database as well as new requests. Review the backlog before upgrading.

`worker-rbac.yaml` grants the dedicated `platform-worker` ServiceAccount
namespace `get/create/delete`, Deployment `get/create/update`, and
ResourceQuota/LimitRange `get/create/update`. Dynamic
namespaces require cluster-wide grants; Kubernetes RBAC cannot constrain these
grants by ownership labels or an `env-` name prefix. Application ownership checks
are not a security boundary against stolen worker credentials: a compromised
backend could delete unrelated namespaces (including `platform-lab` or
`kube-system`) or create/modify Deployments elsewhere.
Use only a dedicated disposable cluster, not a shared or production cluster.
There is no Secrets, pod exec, RBAC-management, wildcard, or cluster-admin grant.

Automatic service-account mounts stay disabled for every component. Only the
backend mounts an explicit read-only projection at
`/var/run/secrets/platform-worker`, containing the namespace's `kube-root-ca.crt`
and an API-audience token with a requested one-hour lifetime. Kubelet rotates the
token; the client reloads it instead of caching startup credentials. The client
uses only `https://kubernetes.default.svc:443`, verifies CA and hostname, ignores
ambient kubeconfig/proxies, and does not follow redirects. The backend UID/GID
10001 reads the group-readable projection via `fsGroup`.

For an intentional API-only K3s run, set `PLATFORM_WORKER_ENABLED` to `"false"`
in `backend.yaml` before applying; accepted creates then remain
`REQUESTED` / `PENDING`. Do not run lifecycle checks in that mode. To remove
unused worker privileges too, remove its projected mount/volume,
`serviceAccountName`, and RBAC resource from the package.

### Prerequisites and images

Use Bash, Docker, kubectl with Kustomize, OpenSSL, and an existing K3s node with
the default `local-path` provisioner. Traefik with its `websecure` entrypoint is
needed only for ingress; TLS port-forwarding below also works without Traefik.
Run the commands from the repository root. Explicitly select the K3s context,
**not** `kind-developer-platform-lab`:

```sh
export K3S_CONTEXT=your-k3s-context
kubectl --context "$K3S_CONTEXT" get nodes
kubectl --context "$K3S_CONTEXT" get storageclass local-path
kubectl --context "$K3S_CONTEXT" get ingressclass traefik
docker build -t platform-lab-backend:k3s-v2 ./backend
docker build -t platform-lab-frontend:k3s-v1 ./frontend
docker save platform-lab-backend:k3s-v2 platform-lab-frontend:k3s-v1 \
  -o /tmp/platform-lab-images.tar
# Run on the K3s node (copy the archive there first if building elsewhere):
sudo k3s ctr images import /tmp/platform-lab-images.tar
rm /tmp/platform-lab-images.tar
```

Build for the node's architecture. Docker's image store is **not** K3s's containerd
store. Both app images use `imagePullPolicy: Never` intentionally: import them on
the node or pods fail with `ErrImageNeverPull`. PostgreSQL is pulled from Docker
Hub. For updates, build/import a new tag and update `images` in
`infra/kubernetes/kustomization.yaml` before reapplying; do not silently reuse tags.
Registry distribution and multi-node scheduling are not provided.

Verify nodes and CoreDNS are healthy, pod networking and kubelet HTTP probes work,
and the node can pull `nginxinc/nginx-unprivileged:1.28-alpine` (or preload that
exact image). Each sample requests 50m CPU/32Mi memory, with 250m/128Mi limits.
The Kubernetes API must be reachable from the backend through cluster DNS and
the `kubernetes` Service. Standard projected service-account tokens and the
namespace CA ConfigMap must be available. The worker does not create sample
Services or Ingresses; use a loopback Deployment port-forward to view the sample.
If the backend is stuck mounting credentials, check that `kube-root-ca.crt` exists
in `platform-lab` and inspect Pod events; it is normally published by the cluster's
root-CA controller.

### Secrets and TLS

No usable credentials, hashes, or private keys are committed. Create the namespace
and two required Secrets separately; applying the package alone fails closed
until they exist. Generate the operator password hash using the existing
[backend recipe](../backend/README.md#authentication-and-authorization).
Use a **different database password** from development/Compose.

```sh
kubectl --context "$K3S_CONTEXT" apply -f infra/kubernetes/namespace.yaml
SECRET_DIR="$(mktemp -d /tmp/platform-k3s-secrets.XXXXXX)"
chmod 700 "$SECRET_DIR"
read -r -s -p 'Database password: ' DB_PASSWORD; echo
printf '%s' "$DB_PASSWORD" > "$SECRET_DIR/database-password"
read -r -p 'Operator username: ' OPERATOR_USERNAME
printf '%s' "$OPERATOR_USERNAME" > "$SECRET_DIR/operator-username"
read -r -s -p 'Operator password hash (not plaintext): ' OPERATOR_HASH; echo
printf '%s' "$OPERATOR_HASH" > "$SECRET_DIR/operator-password-hash"
chmod 600 "$SECRET_DIR/"*
kubectl --context "$K3S_CONTEXT" -n platform-lab create secret generic platform-secrets \
  --from-file="$SECRET_DIR/database-password" \
  --from-file="$SECRET_DIR/operator-username" \
  --from-file="$SECRET_DIR/operator-password-hash"
rm -rf "$SECRET_DIR"
unset DB_PASSWORD OPERATOR_USERNAME OPERATOR_HASH SECRET_DIR
```

Values must be nonempty. Secrets are Kubernetes Secrets, **not** an external secret
manager: restrict cluster access and configure K3s secrets encryption separately.
Avoid shell tracing and never commit credentials or generated Secret YAML.

For disposable local testing, create a one-day certificate **outside the checkout**:

```sh
TLS_DIR="$(mktemp -d /tmp/platform-k3s-tls.XXXXXX)"
chmod 700 "$TLS_DIR"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -keyout "$TLS_DIR/tls.key" -out "$TLS_DIR/tls.crt" \
  -subj /CN=platform-test.local -addext 'subjectAltName=DNS:platform-test.local'
chmod 600 "$TLS_DIR/tls.key"
kubectl --context "$K3S_CONTEXT" -n platform-lab create secret tls platform-tls \
  --cert="$TLS_DIR/tls.crt" --key="$TLS_DIR/tls.key"
```

For longer use, supply a certificate and chain trusted by clients for your chosen
hostname; update both ingress `rules.host` and `tls.hosts` if not using
`platform-test.local`. There is no cert-manager, issuance, or automatic renewal.
The Secret also provides nginx's direct TLS endpoint, with group-readable key
permissions for UID/GID 101. Never bypass TLS verification or send Basic
credentials over HTTP.

### Apply and smoke-test

```sh
kubectl kustomize infra/kubernetes
kubectl --context "$K3S_CONTEXT" apply --dry-run=server -k infra/kubernetes
kubectl --context "$K3S_CONTEXT" apply -k infra/kubernetes
kubectl --context "$K3S_CONTEXT" -n platform-lab rollout status deployment/postgres --timeout=180s
kubectl --context "$K3S_CONTEXT" -n platform-lab rollout status deployment/backend --timeout=300s
kubectl --context "$K3S_CONTEXT" -n platform-lab rollout status deployment/frontend --timeout=180s
kubectl --context "$K3S_CONTEXT" -n platform-lab get pods,svc,pvc,ingress
PLATFORM_K3S_TEST=true PLATFORM_K3S_CONTEXT="$K3S_CONTEXT" \
  python3 -m unittest discover -s automation/tests -p test_kubernetes.py -v
```

Services must exist before nginx starts so that it can resolve `backend` through
cluster DNS; `apply -k` creates them before Deployments. nginx resolves that stable
Service address on startup, not via Docker's `127.0.0.11` resolver. Restart nginx
if replacing the backend Service with a different ClusterIP.

For TLS ingress, map `platform-test.local` to the K3s node's reachable IP in local
DNS/hosts, or use curl's explicit override:

```sh
export K3S_NODE_IP=192.0.2.10 # Replace with your node's reachable address.
read -r -p 'Configured operator username: ' OPERATOR_USERNAME
curl --fail --cacert "$TLS_DIR/tls.crt" \
  --resolve "platform-test.local:443:$K3S_NODE_IP" https://platform-test.local/
curl --fail --cacert "$TLS_DIR/tls.crt" \
  --resolve "platform-test.local:443:$K3S_NODE_IP" -u "$OPERATOR_USERNAME" \
  https://platform-test.local/api/v1/environments
```

The ingress is HTTPS-only on Traefik's `websecure` entrypoint, not an HTTP redirect.
It terminates TLS and forwards to nginx's cluster-internal HTTP listener; nginx
proxies only `/api/v1/` to the backend, not `/actuator`. Cluster-internal traffic is
not encrypted or isolated in this pass. Restrict node/firewall access to trusted
local clients: API reads require Basic credentials, but this is not safe public
exposure. Developers are scoped to persisted team memberships; operators remain
platform-wide. Health probes stay anonymous. No RBAC or ingress changes are
needed for this application authorization policy.

Without ingress, keep this running in another terminal:

```sh
kubectl --context "$K3S_CONTEXT" -n platform-lab port-forward --address 127.0.0.1 svc/frontend 8443:443
```

Then use `curl --fail --cacert "$TLS_DIR/tls.crt" --resolve
platform-test.local:8443:127.0.0.1 https://platform-test.local:8443/` (on one line).
For browser use, map the hostname locally and trust the test certificate explicitly.
Sign in with the operator's **plaintext** password, not its hash.

The offline check is `python3 -m unittest discover -s automation/tests -p
test_kubernetes.py -v` (on one line); it renders manifests when kubectl is present.
Live smoke checks are opt-in and read-only: they verify availability, a bound PVC,
explicit worker configuration, required/denied RBAC permissions, UI/API routing,
and private management paths. The selected kubectl identity needs impersonation
permission to run the `auth can-i --as` checks. They do not prove ingress/TLS,
worker lifecycle, restart persistence, or recovery.

### Authenticated end-to-end lifecycle check

Use only the disposable cluster and an already configured OPERATOR account.
Choose a trusted HTTPS origin that routes to this deployment, either ingress or
the frontend TLS port-forward above. For the port-forward example,
`platform-test.local` must resolve to `127.0.0.1` on the testing host. Do not use
`--insecure`, plaintext remote HTTP, or a password hash as the login password.

```sh
export PLATFORM_K3S_CONTEXT="$K3S_CONTEXT"
export PLATFORM_K3S_API_URL=https://platform-test.local:8443
export PLATFORM_K3S_CA_FILE="$TLS_DIR/tls.crt"
export PLATFORM_K3S_OPERATOR_USERNAME=alice
read -r -s -p 'Operator plaintext password: ' PLATFORM_K3S_OPERATOR_PASSWORD; echo
export PLATFORM_K3S_OPERATOR_PASSWORD
PLATFORM_K3S_LIFECYCLE_TEST=true \
  python3 -m unittest discover -s automation/tests -p test_kubernetes.py -v
unset PLATFORM_K3S_OPERATOR_PASSWORD
```

This separate opt-in **mutates** the deployment: it verifies anonymous rejection,
authenticated acceptance, idempotent replay, bounded operation polling, observed
Deployment readiness, namespace ownership markers, durable two-operation history,
API-driven deletion, and actual namespace absence. The test waits up to seven
minutes per operation, does not follow redirects or ambient proxies, and retains
database history. It attempts API cleanup on failure when the environment state
allows it; pending/provisioning failures may leave resources for diagnosis.
Never remove finalizers or bypass ownership checks to make a test pass.
No successful run is implied by adding this check; record actual results.

### Recorded lifecycle validation — 2026-10-16

**PASS:** after merging [PR #14](https://github.com/dcfaight/developer-platform-lab/pull/14),
the worker-enabled K3s path was manually validated on the disposable K3s cluster
using context `default` and HTTPS ingress `platform-test.local` at `192.168.1.5`.
The create → provision → ready → authenticated delete → namespace cleanup
manual validation passed. This is not production readiness or evidence of an
automated lifecycle-test run. See the
[current roadmap](../docs/operating-model-and-sequence.md) for separate restore,
automated lifecycle, and restart-persistence gates.

The recorded environment was `demo` (`fab18d99-7a86-4886-8960-31277137837f`),
with namespace `env-fab18d997a864886896031277137837f`.

| Check | Observed result |
| --- | --- |
| Apply and backend rollout | `kubectl --context default apply -k infra/kubernetes` reported unchanged resources; `kubectl --context default -n platform-lab rollout status deployment/backend --timeout=180s` completed successfully. |
| Create operation `322165ac-f272-459f-9dff-cdcb5b0ce66b` | The existing pending create was processed after deployment: `SUCCEEDED`, `progressStep: READY`, `attemptCount: 1`, no failure code/message. Events were `CLAIMED → PROVISIONED → WAITING → CLAIMED → SUCCEEDED` (21:46:25–21:46:34 UTC). |
| Namespace and sample workload | Namespace `Active`; `sample-service` Deployment `1/1` ready and available; pod `1/1 Running`, zero restarts. |
| Authenticated API deletion | An OPERATOR's plaintext password was prompted for by curl; DELETE returned `DELETING` / `PENDING` with operation `c01c7741-033d-4117-8369-1b6de415a4d2`. Acceptance alone was not treated as cleanup. |
| Final environment GET and namespace check | Environment `DELETED`; `latestOperation` was the delete operation with `SUCCEEDED` / `DELETED` (updated 21:55:26 UTC). `kubectl get namespace` returned `NotFound`. The API record remained as history. |

To reproduce manually, first complete the setup above. Substitute your actual
context, reachable ingress address/hostname, and matching trusted certificate.
The recorded create was already pending; the POST below starts a **new** run.
Use the configured operator username, not an example account; curl prompts for
its plaintext password. Do not paste credentials into commands or commit them.

```sh
export K3S_CONTEXT=default
export K3S_NODE_IP=192.168.1.5
export K3S_HOST=platform-test.local
read -r -p 'Configured operator username: ' OPERATOR_USERNAME
: "${OPERATOR_USERNAME:?Set the configured operator username}"
: "${TLS_DIR:?Set the directory containing the trusted ingress tls.crt}"

curl --silent --show-error --fail --cacert "$TLS_DIR/tls.crt" \
  --resolve "$K3S_HOST:443:$K3S_NODE_IP" -u "$OPERATOR_USERNAME" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: k3s-validation-$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM" \
  -d '{"name":"demo","templateId":"sample-service","ttlHours":8}' \
  "https://$K3S_HOST/api/v1/environments"
# Copy environmentId and operationId from this response, not the historical IDs.
read -r -p 'environmentId: ' ENV_ID
read -r -p 'Create operationId: ' CREATE_OPERATION_ID
ENV_NAMESPACE="env-${ENV_ID//-/}"

# Repeat until SUCCEEDED / READY; inspect CLAIMED, PROVISIONED and readiness events.
curl --silent --show-error --fail --cacert "$TLS_DIR/tls.crt" \
  --resolve "$K3S_HOST:443:$K3S_NODE_IP" -u "$OPERATOR_USERNAME" \
  "https://$K3S_HOST/api/v1/operations/$CREATE_OPERATION_ID" | python3 -m json.tool
kubectl --context "$K3S_CONTEXT" get namespace "$ENV_NAMESPACE"
kubectl --context "$K3S_CONTEXT" -n "$ENV_NAMESPACE" get deployments,pods

# Only after creation succeeds and sample-service is 1/1 ready:
curl --silent --show-error --fail --cacert "$TLS_DIR/tls.crt" \
  --resolve "$K3S_HOST:443:$K3S_NODE_IP" -u "$OPERATOR_USERNAME" \
  -X DELETE "https://$K3S_HOST/api/v1/environments/$ENV_ID"
read -r -p 'Delete operationId from response: ' DELETE_OPERATION_ID
# Repeat both GETs until SUCCEEDED / DELETED and environment status DELETED.
curl --silent --show-error --fail --cacert "$TLS_DIR/tls.crt" \
  --resolve "$K3S_HOST:443:$K3S_NODE_IP" -u "$OPERATOR_USERNAME" \
  "https://$K3S_HOST/api/v1/operations/$DELETE_OPERATION_ID" | python3 -m json.tool
curl --silent --show-error --fail --cacert "$TLS_DIR/tls.crt" \
  --resolve "$K3S_HOST:443:$K3S_NODE_IP" -u "$OPERATOR_USERNAME" \
  "https://$K3S_HOST/api/v1/environments/$ENV_ID" | python3 -m json.tool
kubectl --context "$K3S_CONTEXT" get namespace "$ENV_NAMESPACE"
# Expected final result: NotFound (nonzero exit), not a connectivity/auth error.
```

### Viewing the platform in OpenLens/Lens

OpenLens, Lens, or a similar Kubernetes UI can browse this K3s cluster using a
kubeconfig with the correct context and sufficient Kubernetes RBAC permissions.
Environments are namespaces in **one cluster**, not separate clusters. Before
deletion, browse the `env-…` namespace for `sample-service`, pods, and events;
after successful cleanup that namespace is absent. `platform-lab` holds the
portal, backend, and PostgreSQL.

On the UI machine, first verify `kubectl --context "$K3S_CONTEXT" get nodes`
using the same kubeconfig you will import/select in the UI. Its Kubernetes API
server address (typically port 6443) must be reachable, with trusted CA data and
valid credentials. A kubeconfig pointing at `127.0.0.1` on the K3s node will not
work unchanged on another machine; use a reachable endpoint covered by the API
server certificate and trusted LAN/VPN access. Portal ingress access or curl's
`--resolve` does not provide Kubernetes API access. Keep kubeconfig private,
prefer a least-privilege viewing identity, never disable certificate verification,
and do not expose the API publicly just to connect a UI. UI connectivity was not
part of the recorded validation.

### Concise local status report

Record the cluster/context, image tags, observation time, and exact checks run.
Summarize separately: available platform Deployments/PVC, TLS and UI/API routing,
authentication/persistence/replay, worker configuration, and lifecycle results.
Use environment detail and operation GETs for current state, attempts, progress,
and sanitized failures, not the original POST/replay response. Portal counts cover
only the loaded page; health readiness checks PostgreSQL, not worker progress.
For API-only runs, report `REQUESTED` / `PENDING` and zero attempts as expected,
not as provisioning success. Mark skipped/unrun checks explicitly; availability
alone does not prove workload readiness, deletion, persistence after restart, or
backup recovery.

### Persistence, startup, and remaining work

- PostgreSQL initializes its database/user/password only on an **empty** volume.
  Its mount is `/var/lib/postgresql`, matching the PostgreSQL 18 image and Compose.
  One replica and `Recreate` avoid concurrent database pods using the same data.
  K3s local-path data is node-local, not HA or a backup.
- Flyway migrations run in backend startup, before readiness. There is no ordered
  pod startup: the backend may restart until PostgreSQL accepts connections.
  Its startup probe allows five minutes; readiness includes the database,
  liveness does not. The UI probe checks nginx, not API availability.
- Pod restarts/reapplying preserve the PVC. **Deleting the PVC or namespace can
  permanently delete data** with local-path's default Delete reclaim policy.
  `kubectl delete -k` also deletes the PVC/namespace: it is a destructive teardown,
  not a routine stop. Do not use it to upgrade.
- Secret updates need app restarts; nginx also needs a restart to reload renewed
  TLS certificates. Changing the database Secret does **not** change the password
  inside an initialized database: coordinate a database-side password rotation
  before restarting consumers. Back up before image/schema upgrades; migration
  rollback is not assured.
- App pods run non-root with read-only roots, bounded memory-backed `/tmp`,
  dropped capabilities, seccomp, resource requests/limits and no automatic
  service-account token. Only the backend receives the explicit worker projection.
  PostgreSQL retains its image's initialization privileges. These are
  useful defaults, not validated production hardening.
- A separate [disposable logical backup/restore drill](#disposable-k3s-logical-backuprestore)
  is available; it does not test the live PVC or restart persistence.
- **Open operational work:** retain logical-restore evidence and execute the
  [restart-persistence gate](#disposable-k3s-restart-persistence); run the automated
  authenticated lifecycle check
  separately from the recorded manual validation; review network policies,
  database role separation, secret encryption/rotation, certificate renewal,
  and access. Gate status is maintained in the
  [current roadmap](../docs/operating-model-and-sequence.md). No expiration
  scheduler or cleanup-CLI TLS extension is included.

## Disposable K3s restart persistence

**PERSIST-01 — defined, live result/evidence pending.** This gate definition
preceded the [capacity-baseline increment](../automation/README.md#validation-gate);
neither definition is evidence that GitOps or restart persistence has passed.
The recorded manual lifecycle check, frontend-only GitOps drill, backend restart
tests, and logical restore fixture do not prove K3s same-PVC restart persistence.
No successful run of this drill is recorded.

### Scope and explicit opt-in

This manual, operator-invoked drill uses the **existing application database**
on a dedicated disposable **single-node K3s** target. It gracefully replaces the
PostgreSQL pod, waits for recovery, then gracefully replaces the backend pod.
Keep the same PVC/PV, node, images, schema, Secrets, and configuration throughout.
It tests committed state across controlled pod restarts, not in-flight crash
recovery, host/K3s-service restart, node loss, backup recovery, PITR, HA, failover,
or production readiness. Temporary API unavailability is allowed and must be timed.

Use Bash, kubectl, curl, `jq`, and the worker-enabled deployment and trusted TLS
setup above. The operator needs Kubernetes permissions to read nodes/PVs/PVCs,
Deployments/pods, exec the private backend health probe, and delete the selected
platform pods, plus the configured API `OPERATOR` account. Confirm nodes, storage,
image availability, and platform readiness first. Inspect existing pending
work/leftovers; do not interrupt unrelated work.
Quiesce other API writers and cleanup invocations so fixture comparisons are
meaningful. If GitOps manages the target, record its synchronized revision and
keep desired state fixed; do not alternate manual base applies with GitOps, change
Deployment templates, or weaken controller ownership/pruning restrictions.
The drill deletes only explicitly selected platform **pods**, never storage or
environment namespaces. Do not run it against a shared/production context.

Choose an absolute access-controlled evidence directory outside the checkout on
operator-managed storage. A `/tmp` directory or task artifact alone is not durable;
copy and verify the evidence before the host/task expires. Do not enable shell
tracing, record passwords/Authorization headers, or export Secrets/kubeconfig.

```sh
ROOT=/home/runner/work/developer-platform-lab/developer-platform-lab
export K3S_CONTEXT=your-disposable-k3s-context
read -r -p 'Type the disposable context to authorize pod restarts: ' CONFIRM_CONTEXT
test -n "$K3S_CONTEXT" && test "$CONFIRM_CONTEXT" = "$K3S_CONTEXT" || exit 1
read -r -p 'Absolute evidence directory outside the checkout: ' EVIDENCE
test "${EVIDENCE:0:1}" = / || exit 1
umask 077
mkdir -p "$EVIDENCE"
chmod 700 "$EVIDENCE"
git -C "$ROOT" rev-parse HEAD > "$EVIDENCE/checkout-revision.txt"
kubectl --context "$K3S_CONTEXT" get nodes -o wide > "$EVIDENCE/nodes.txt"
```

Review the selected target before continuing; confirmation is a manual safety
step, not an automated cluster-identity check. Record UTC date, context/cluster
identity, node/K3s version, deployment source (manual or GitOps revision), desired
images/runtime image IDs, operator identity (no credentials), and chosen timeouts.
Use a stable ingress or frontend TLS port-forward that will survive backend and
database pod replacement. The examples below use the ingress variables from the
manual lifecycle sequence (`K3S_HOST`, `K3S_NODE_IP`, `TLS_DIR`,
`OPERATOR_USERNAME`); curl prompts for the configured operator's plaintext password.
Do not use `--insecure`, redirects, or plaintext remote HTTP.

### Fixture, restart, and lifecycle sequence

1. Using the [manual lifecycle sequence](#recorded-lifecycle-validation--2026-10-16)
   and [API contract](../docs/api.md), create **two new drill-owned environments**
   with distinct run-specific idempotency keys. Keep one `READY` (`ENV_ID`);
   fully delete the other through the API (`HISTORY_ENV_ID`), waiting for
   `DELETED`, successful delete operation, and namespace `NotFound`.
   Do not reuse the historical example IDs. Retain each exact POST JSON, key,
   caller/team scope, HTTP `202` body and `Location`, and all returned IDs.
   Generate each key once and save it; do not regenerate the manual example's
   timestamp/random key when replaying.
   The ready fixture has one successful create operation; the deleted fixture
   has successful create and delete operations.

   Before restarting, replay the ready fixture's exact POST with the same key
   and caller/team: require the original `202` body/IDs/accepted states and
   `Location`, not current `READY` state. Also submit a different valid name with
   that key: require `409 IDEMPOTENCY_CONFLICT`. Save both request bodies and
   responses. The conflict is expected; omit curl's `--fail` for that check,
   capture its HTTP status explicitly, and do not treat arbitrary errors as success.

2. Capture a stable **before** snapshot after both fixtures are terminal.
   Save ready/deleted environment detail and complete operation history/events.
   For these new fixtures, one page of 100 is sufficient; verify `totalElements`
   is respectively 1 and 2 and `totalPages` is 1. Do not silently truncate history.
   Run this block again with `PHASE=after-postgres` and `PHASE=after-backend`
   after the respective recovery, before new lifecycle mutations:

   ```sh
   PHASE=before
   for ID in "$ENV_ID" "$HISTORY_ENV_ID"; do
     curl --silent --show-error --fail --connect-timeout 5 --max-time 30 \
       --noproxy '*' --cacert "$TLS_DIR/tls.crt" \
       --resolve "$K3S_HOST:443:$K3S_NODE_IP" -u "$OPERATOR_USERNAME" \
       "https://$K3S_HOST/api/v1/environments/$ID" \
       -o "$EVIDENCE/$PHASE-$ID-environment.json"
     curl --silent --show-error --fail --connect-timeout 5 --max-time 30 \
       --noproxy '*' --cacert "$TLS_DIR/tls.crt" \
       --resolve "$K3S_HOST:443:$K3S_NODE_IP" -u "$OPERATOR_USERNAME" \
       "https://$K3S_HOST/api/v1/environments/$ID/operations?page=0&size=100" \
       -o "$EVIDENCE/$PHASE-$ID-history.json"
   done
   kubectl --context "$K3S_CONTEXT" -n platform-lab get pods \
     -l 'app in (postgres,backend)' -o json |
     jq '[.items[] | {name: .metadata.name, uid: .metadata.uid,
       node: .spec.nodeName, images: [.spec.containers[].image],
       containers: .status.containerStatuses}]' > "$EVIDENCE/$PHASE-pods.json"
   kubectl --context "$K3S_CONTEXT" -n platform-lab get pvc postgres-data -o json \
     > "$EVIDENCE/$PHASE-pvc.json"
   PV="$(jq -r '.spec.volumeName' "$EVIDENCE/before-pvc.json")"
   kubectl --context "$K3S_CONTEXT" get pv "$PV" -o json > "$EVIDENCE/$PHASE-pv.json"
   kubectl --context "$K3S_CONTEXT" -n platform-lab exec deployment/backend -- \
     curl --fail --silent --show-error --max-time 10 \
       http://127.0.0.1:8080/actuator/health/readiness \
     > "$EVIDENCE/$PHASE-readiness.json"
   ```

   Check every command's exit status and JSON content before continuing; a
   partial/empty snapshot is not evidence. Require aggregate readiness `UP`
   (its configured group includes `db`; component details are hidden), PVC
   `Bound`, and the expected ready sample Deployment/pods.
   Retain those sample observations and the deleted namespace's `NotFound`.

3. Select the single current PostgreSQL pod by `app=postgres`; inspect its name,
   UID, node, and `postgres-data` mount against the baseline. Require exactly one
   ready pod, then run **one component at a time**:

   ```sh
   POSTGRES_POD="$(kubectl --context "$K3S_CONTEXT" -n platform-lab get pod \
     -l app=postgres -o jsonpath='{.items[0].metadata.name}')"
   date -u +%FT%TZ > "$EVIDENCE/postgres-restart-start.txt"
   kubectl --context "$K3S_CONTEXT" -n platform-lab delete pod "$POSTGRES_POD" \
     --wait=true --timeout=180s > "$EVIDENCE/postgres-restart.txt"
   kubectl --context "$K3S_CONTEXT" -n platform-lab rollout status deployment/postgres \
     --timeout=300s >> "$EVIDENCE/postgres-restart.txt"
   kubectl --context "$K3S_CONTEXT" -n platform-lab rollout status deployment/backend \
     --timeout=300s >> "$EVIDENCE/postgres-restart.txt"
   ```

   Do not force deletion, shorten grace periods, scale/reset the database, or
   delete/recreate the PVC/PV/platform namespace. Require a **new pod UID**, the
   same node and same PVC UID/PV name/PV UID, a bound volume, database readiness
   `UP`, and authenticated API recovery within the recorded bounded wait.
   A rollout command alone can race replacement and report an old state:
   verify the new UID and ready container explicitly before taking the
   `after-postgres` snapshot. Record unexpected backend restarts, if any.
   Repeat the accepted replay and conflict probes from step 1 and save results.
   Record the recovery-end UTC timestamp only after readiness and authenticated
   API recovery are verified, not merely after `rollout status` exits.

4. Repeat step 3 for the single `app=backend` pod, using `BACKEND_POD`, a
   `backend-restart` log/timestamps, and `rollout status deployment/backend
   --timeout=300s`. Wait for a new ready backend UID, database readiness `UP`,
   and authenticated API recovery; capture `after-backend` and repeat both replay
   probes. Do not restart PostgreSQL again or change images/schema/configuration.
   For **each** after snapshot, compare both fixtures' JSON with the baseline
   (for example, `diff -u <(jq -S . BEFORE_FILE) <(jq -S . AFTER_FILE)`).
   Require unchanged IDs, ownership, expiry, terminal states, operation counts,
   timestamps, correlation IDs, attempts, and ordered events. Ignore only
   request-specific HTTP headers such as `Date`/`X-Request-Id`, not lost history.
   Replays must add no environment or operation and must preserve `Location`.

5. After both comparisons pass, create a **fresh** environment with a new key.
   Observe successful create operation, `READY`, owned namespace, and
   `sample-service` Deployment/pod readiness; API acceptance alone is insufficient.
   Delete both this new environment and the retained ready fixture **through the
   authenticated API**. Poll operation/detail/history until delete `SUCCEEDED`,
   environment `DELETED`, and each namespace is `NotFound` (not an auth/network
   error). Retain create/delete operation IDs, events, and final API history,
   including the unchanged pre-restart create events. Confirm the pre-deleted
   fixture is still deleted and its history unchanged. Do not retry an ambiguous
   DELETE blindly; recover its operation via authenticated detail/history reads.
   Never delete environment namespaces directly or strip finalizers.

### Required retained evidence and gate closure

Archive the metadata, exact fixture requests/keys/scopes and acceptance responses,
before/after snapshots for **both restart phases**, replay/conflict HTTP statuses,
bodies and `Location` comparisons, restart commands/UTC timings, readiness and
post-restart lifecycle/namespace-absence results. Include an explicit pass/fail/
unknown checklist for every requirement and an operator statement listing all
interventions: **no manual SQL/data repair, Flyway repair, restore/reseed,
storage replacement, or PVC/PV loss** is allowed for a pass. Normal automatic
PostgreSQL startup recovery on the existing volume is within scope.

On any timeout, lost/mutated history, changed storage identity, replay mismatch,
repair requirement, or lifecycle failure, stop and retain a failed/blocked
result plus sanitized diagnostics and leftovers. Do not repair state and relabel
that attempt as passing. Only clean up drill-owned environments through the API
when their state permits; retain failures for diagnosis. Destructive cluster
teardown is separate and must wait until evidence is archived.

Retain a checksum manifest alongside the sanitized evidence in access-controlled
operator-managed storage **outside the task environment**, verify the archived
copy, and record its durable reference. Then update **PERSIST-01** in the
[canonical roadmap](../docs/operating-model-and-sequence.md#current-delivery-gates)
with target, UTC date, checkout/deployed revisions, exact checks, outcome,
interventions, and evidence reference (or blocker/next action). A merge, an
unretained task-local run, or a passing lifecycle/restore test does not close this
gate, the capacity baseline, or any production recovery/readiness gate.

## Disposable K3s logical backup/restore

### Safety and prerequisites

This **operator-invoked logical round trip**, not a backup service, requires
Python 3.12, `kubectl`, and an explicitly selected **disposable K3s cluster**.
Do not use a production/shared context. The operator needs permission to create
namespaces, NetworkPolicies and pods, exec into those pods, and delete the
drill-owned namespace. No application/API credentials are needed.

Build the backend from this checkout and import it into **every schedulable
node's containerd store** as `platform-lab-backend:k3s-v2`, following the earlier
K3s image preparation instructions. The drill uses `imagePullPolicy: Never` for
this local image; an older image lacking this checkout's migrations fails
verification. Nodes must also have access to `postgres:18.6`. Neither the
application stack nor Argo CD needs to be installed. Default K3s network-policy
enforcement must be enabled.

The workflow creates a random `backup-drill-…` namespace, a deny-all
ingress/egress NetworkPolicy, and two standalone PostgreSQL pods. Each has its
own fresh, size-bounded `emptyDir`; **no PVC, PV, Service, application Secret,
worker RBAC, live database connection, or existing deployment is used or
modified**. PostgreSQL listens only on loopback. A backend container in the
source pod applies the real Flyway migrations over loopback, with the deploy
profile and worker explicitly disabled; its HTTP listener is also loopback-only.
The restore pod contains PostgreSQL only. Both pods have service-account token
automount disabled. Restored pending operations cannot reach a provisioning
worker.

### Run and retain evidence

Set `ROOT` to the absolute path of your checkout. Create an access-controlled
artifact directory **outside it**, on operator-managed storage (or copy results
to durable storage before a temporary host expires):

```sh
ROOT=/home/runner/work/developer-platform-lab/developer-platform-lab
ARTIFACTS="$(mktemp -d /tmp/platform-logical-restore.XXXXXX)"
chmod 700 "$ARTIFACTS"
K3S_CONTEXT=your-disposable-k3s-context
kubectl --context "$K3S_CONTEXT" get nodes

PLATFORM_BACKUP_RESTORE_TEST=true python3 "$ROOT/automation/backup_restore.py" \
  --confirm-disposable --context "$K3S_CONTEXT" --artifacts "$ARTIFACTS"
```

**Both** the exact environment opt-in and `--confirm-disposable` are required;
omitting either refuses before Kubernetes access. The context and artifact
directory must be explicit. Normal CI only runs offline safety checks.
Alternatively, run the dedicated live unittest workflow:

```sh
PLATFORM_BACKUP_RESTORE_TEST=true \
PLATFORM_K3S_CONTEXT="$K3S_CONTEXT" \
PLATFORM_BACKUP_RESTORE_ARTIFACTS="$ARTIFACTS" \
  python3 -m unittest discover -s "$ROOT/automation/tests" -p test_backup_restore.py -v
```

The fixture has two environments, linked pending create operations, audit and
progress events, team memberships, and the **same idempotency key in two
actor/team scopes**. The drill validates known fixture values and relationships,
then uses PostgreSQL 18 `pg_dump --format=custom` and `pg_restore` into the empty
restore database. It compares every column in all fixture tables and Flyway
history (including checksums/success), schema DDL, and sequence value/called state.
For schema comparison, PostgreSQL itself parses the source DDL into a fresh
schema-only `schema_reference` database inside the disposable source pod. This
normalizes PostgreSQL's equivalent array-cast rendering on logical restore
without custom SQL rewriting; the source fixture database is not reconfigured.
No migrations or backend process run against the restored database.

Each run prints a new mode-0700 directory containing:

- `fixture.dump` (mode 0600): the logical backup, not raw database storage.
- `fixture.dump.sha256`: SHA-256 of that artifact.
- `source-schema.sql` and `restore-schema.sql` (mode 0600): server-normalized
  source DDL and restored DDL for inspecting schema verification, without fixture rows.
- `evidence.json` (mode 0600): pass/fail and failed stage, explicit context,
  namespace/pod UIDs, database identities, configured/runtime images,
  Kubernetes/PostgreSQL versions, checksum, per-table/schema/sequence verification
  results (including false comparisons on mismatch), and measured backup/restore
  command durations in seconds.

Evidence contains no passwords, SQL rows, kubeconfig, or raw error bodies.
Context/resource identities can still be internal metadata: review before sharing.
Retain the backup, checksum and evidence together securely; do not commit them.
Verify the retained artifact with `sha256sum -c fixture.dump.sha256` from its run
directory. Success requires exit zero, `status: passed`, every verification true,
and `cleanup: deleted-owned-namespace`. Durations are observations for this tiny
fixture, **not production RTO**.

### Failure handling, cleanup, and limits

Kubernetes requests have a 15-second request timeout; commands normally have a
60-second subprocess bound, pod readiness 180 seconds, backup/restore 120
seconds each, and cleanup waits at most 90 seconds. Pods have a 900-second active
deadline and bounded CPU/memory/storage; the disposable source backend allows
60 seconds for initial database-pool connection acquisition. Failure or Ctrl-C attempts cleanup and
retains sanitized stage evidence and any partial backup. A failed/partial dump
is not recovery evidence.

Cleanup deletes **only the generated namespace**, after matching its ownership
label and recorded UID, with a UID deletion precondition; it never runs a broad
label deletion or applies/deletes the live Kustomization. On cleanup failure,
abrupt host loss/SIGKILL, or an ambiguous namespace-create timeout, inspect the
exact generated namespace and its ownership/UID before removing it. Do not
force-delete resources or finalizers, delete `platform-lab`, or delete any PVC.
After externally archiving evidence, the operator chooses when to remove the
local run directory; artifacts are not automatically deleted.

A passing run proves a **quiescent known-fixture logical backup/restore round
trip at one schema/PostgreSQL version** into independent disposable storage.
It does **not** prove backup of live application data, live PVC/restart
persistence, retention/off-cluster durability, arbitrary migration rollback,
concurrent-write consistency, full-role/global-object recovery, node-loss
recovery, PITR, HA, replication, failover, production RPO/RTO or production
recovery readiness. Scheduling, production backup storage/retention, recovery
objectives and capacity testing remain future work.

**2026-10-17 validation:** automation discovery passed **111 tests, 11 opt-in
checks skipped**; the dedicated opt-in unittest workflow subsequently passed
**all 17 tests with no skips**. The standalone live drill passed on disposable K3s
`v1.35.1+k3s1` with PostgreSQL `18.6`: all fixture/Flyway/schema/sequence checks
passed, with backup **0.207 s** and restore **0.216 s**. The retained dump's SHA-256
is `f5b2485d5353db839290f922e0876c42d0b30d6df85ec022189c3ab3d6029749`;
its checksum and the matching schema files were independently checked. A
missing-backend-image attempt also failed within the readiness bound and cleaned
its owned namespace. Neither run used any live application database or PVC.

Artifacts/evidence are at
`/tmp/platform-backup-validation/backup-drill-nwa9agpk` **in this task environment
only**, not durable publication. Archive that run directory externally before
the environment expires, or repeat the runbook on the intended disposable target.
Durably retained target-cluster evidence is still required before closing the
logical restore validation gate; these tiny-fixture timings are not RPO/RTO.
The separate GitOps synchronization/rollback live-validation blocker below
remains open; this drill does not resolve it.

## Disposable K3s GitOps deployment and frontend rollback

### Scope and controller decision

**Tooling exists; successful live validation is still required.** The next
roadmap gate is a retained successful run on the disposable K3s target, proving
Git synchronization, frontend upgrade and Git-driven revert with unchanged
backend and persisted state. The recorded image-download/DNS failure below
remains an open blocker; bootstrap and offline checks do not close
[GITOPS-01](../docs/operating-model-and-sequence.md#current-delivery-gates).

This opt-in path reuses the platform Kustomize package through
`infra/gitops/local-k3s`. Argo CD **v3.5.5** is selected for this bounded increment:
it reconciles the existing Kustomize target and exposes the synchronized Git
revision and deployment health without adding Helm or a release API. Its
namespace-scoped installation and CRDs are pinned to upstream commit
`a5038b0e2bbac1715754380ae0084df8e3d5b48c`. The GitHub advisory check for
`github.com/argoproj/argo-cd/v3` 3.5.5 returned no advisories on 2026-10-17;
this is not a comprehensive container vulnerability audit.

The Application watches this repository's **`main` branch**, specifically
`infra/gitops/local-k3s`. Git is authoritative; changing a digest in that path
is a release, and a new commit reverting that change is rollback. For a reviewed
disposable-target drill on a feature branch, an operator may explicitly select
that branch in the bootstrap Application before the baseline; record the
selection, keep it fixed through all three phases, and restore `main` afterward.
Do not use out-of-Git Application image overrides or `kubectl set image`.
There is one local target, not dev/QA/UAT promotion or production continuity.

Ownership is deliberately split:

| Owner | Resources |
| --- | --- |
| Bootstrap administrator | Argo CD/CRDs, `platform-lab` namespace, PVC, worker service account/RBAC, cluster registration, AppProject/Application |
| Secret administrator | Existing `platform-secrets` and `platform-tls`, supplied outside Git |
| Argo CD | Only platform Deployments, Services, generated ConfigMaps and Ingress in `platform-lab` |
| Existing backend worker | Dynamically provisioned `env-…` namespaces and sample workloads; never included in the GitOps target |

The AppProject has no allowed cluster-scoped kinds and only the four platform
resource kinds above. The controller/server get a Role in `platform-lab`, with
no delete, Secret, PVC, namespace, or RBAC permissions there. The registered
destination is namespace-limited for this entire dedicated Argo CD instance.
Automatic pruning is disabled; there is no
Application deletion finalizer or automatic namespace creation. Do not enable
`Replace`, `Force`, pruning, cascading deletion, or wildcard privileges to resolve
a sync failure. Generated ConfigMaps retain their content hashes so configuration
changes roll pods; old ConfigMaps intentionally accumulate. Only an administrator
may garbage-collect them, after checking live pods and retained rollback revisions.
Application deletion leaves workloads running; it is not an uninstall procedure.

This limits controller resource ownership, **not trust in repository writers**:
approved Deployment changes can execute code as the backend's powerful worker
identity. Protect repository writes, review deployment changes, and use only a
dedicated disposable single-node cluster. Existing worker RBAC limitations remain.

### Bootstrap prerequisites and order

Use the existing K3s secrets/TLS procedure above, a healthy single-node cluster,
Docker Buildx with a `docker-container` builder, kubectl/Kustomize, `jq`, and
trusted GitHub/registry access. No kubeconfig, password, key, or image archive
belongs in Git. The controller needs outbound HTTPS to GitHub and its pinned
upstream image registries; do not disable certificate verification.

1. Set explicit paths/context and install the pinned CRDs/controller:

   ```sh
   export ROOT=/home/runner/work/developer-platform-lab/developer-platform-lab
   export K3S_CONTEXT=your-disposable-k3s-context
   ARGO_REV=a5038b0e2bbac1715754380ae0084df8e3d5b48c
   kubectl --context "$K3S_CONTEXT" create namespace argocd
   kubectl --context "$K3S_CONTEXT" apply --server-side \
     -f "https://raw.githubusercontent.com/argoproj/argo-cd/$ARGO_REV/manifests/crds/application-crd.yaml" \
     -f "https://raw.githubusercontent.com/argoproj/argo-cd/$ARGO_REV/manifests/crds/appproject-crd.yaml" \
     -f "https://raw.githubusercontent.com/argoproj/argo-cd/$ARGO_REV/manifests/crds/applicationset-crd.yaml"
   kubectl --context "$K3S_CONTEXT" apply -k "$ROOT/infra/gitops/controller"
   kubectl --context "$K3S_CONTEXT" -n argocd rollout status statefulset/argocd-application-controller --timeout=300s
   kubectl --context "$K3S_CONTEXT" -n argocd rollout status deployment/argocd-repo-server --timeout=300s
   ```

   Keep the Argo CD API private; no ingress or public access is added. The upstream
   installation includes auxiliary components, not just the reconciler.
   For an existing installation, inspect conflicts first; never force ownership
   of unrelated Argo CD resources. Bootstrap is not managed by this Application.

2. Create `platform-lab` and its two external Secrets using the existing
   **Secrets and TLS** section. Preload all application artifacts below, plus the
   approved sample image. Ensure PostgreSQL can pull `postgres:18.6`. The target
   retains Traefik's `platform-test.local` ingress; without Traefik, use trusted
   frontend TLS port-forwarding as above.

3. Review the rendered bootstrap and target, then apply bootstrap **only after**
   secrets and artifacts exist:

   ```sh
   kubectl kustomize "$ROOT/infra/gitops/bootstrap"
   kubectl kustomize "$ROOT/infra/gitops/local-k3s"
   kubectl --context "$K3S_CONTEXT" apply -k "$ROOT/infra/gitops/bootstrap"
   kubectl --context "$K3S_CONTEXT" -n argocd get application platform-local -o yaml
   ```

   Bootstrap reuses the existing PVC definition and worker RBAC, filtering out
   application resources. The Application starts reconciliation immediately.
   For an existing manually applied deployment, back up first and review pending
   operations: enabling this worker processes them. Do not alternate manual base
   applies and GitOps applies. The target uses backend `Recreate` for coordinated
   upgrades; **V7 still requires every old backend process to stop**, including any
   outside Kubernetes. This does not make arbitrary migrations reversible.

### Immutable artifacts and retention

`infra/gitops/local-k3s/releases.json` retains historical `backend` and `frontend`
archive records with source commit, platform, manifest/configuration digests, and
archive SHA-256. `currentBackend` and `currentFrontend` identify the selected images
separately. Backend `pr53-ca2e5c9` tracks the recovered, locally imported image by manifest
digest; registry publication, source commit, configuration digest, archive checksum,
and the live runtime imageID-to-manifest relationship have not been independently
verified. The historical backend archive metadata does not describe `pr53-ca2e5c9`.
The target selects the backend and baseline frontend by **manifest digest**, not
a reusable tag. Both application pull policies remain `Never`.
The two frontend variants differ only in public `/release.json`; both use the
same source commit and are compatible with the same backend/schema. This is a
controlled release/rollback fixture, not a claim of two independent product releases.

Retain **both frontend archives and the fixed backend archive** outside Git in
access-controlled artifact storage for the entire rollback window. Copy the
recorded archives from the release producer, verify their SHA-256 against the
catalog, and import on the K3s node:

```sh
# ARTIFACTS is an absolute directory outside the checkout containing the archives.
sha256sum "$ARTIFACTS/platform-frontend-a.tar" "$ARTIFACTS/platform-frontend-b.tar" "$ARTIFACTS/platform-backend.tar"
# Compare each checksum with releases.json before importing.
sudo k3s ctr images import --digests --base-name docker.io/library/platform-lab-frontend "$ARTIFACTS/platform-frontend-a.tar"
sudo k3s ctr images import --digests --base-name docker.io/library/platform-lab-frontend "$ARTIFACTS/platform-frontend-b.tar"
sudo k3s ctr images import --digests --base-name docker.io/library/platform-lab-backend "$ARTIFACTS/platform-backend.tar"
sudo k3s ctr images ls
```

Ensure containerd has each **exact named digest reference** from the catalog.
OCI imports with unnamed entries may create `import-…@sha256:…` names; alias that
entry with `sudo k3s ctr images tag EXISTING_REFERENCE CATALOG_IMAGE_REFERENCE`
without changing its digest. Verify the manifest/config digest before tagging.
Import both frontend versions before the first deployment; do not rely on image
cache retention or assume Docker's image store is K3s's store.

Before merging the recovered backend pin, run the offline contract checks and
render the target (set `ROOT` to the absolute checkout path):

```sh
python3 -m unittest discover -s "$ROOT/automation/tests" -p test_gitops.py -v
kubectl kustomize "$ROOT/infra/gitops/local-k3s"
```

On every K3s node eligible to run the backend, verify the recovered tag's manifest
digest and the **exact digest-qualified reference**, without syncing or restarting:

```sh
BACKEND_DIGEST=sha256:8e601480ced03a127d658006cccdffce6c46817463f799b67de98d64661b2811
BACKEND_IMAGE="docker.io/library/platform-lab-backend@$BACKEND_DIGEST"
sudo k3s ctr images ls | grep -F 'docker.io/library/platform-lab-backend'
sudo k3s ctr content get "$BACKEND_DIGEST" | sha256sum
sudo k3s crictl inspecti "$BACKEND_IMAGE"
```

Require both `:pr53-ca2e5c9` and `$BACKEND_IMAGE` to target `$BACKEND_DIGEST`,
the content checksum to equal its hex value, and CRI inspection to resolve the
exact reference with `linux/amd64` image configuration. The old `c7e66463…` digest
reference is a different build, not an alias for the recovered image. If the new
exact reference is absent, stop: an operator must verify the tag's target digest
and add the alias using the procedure above, then repeat these checks before
merge/sync. Offline checks cannot establish node-local availability with pull
policy `Never`; no registry publication or live K3s verification is implied.

Archives are not committed or published by CI. The catalog identifies artifacts,
but a fresh node cannot deploy without an operator supplying them. Rebuilding
may yield different digests; never pretend a rebuilt artifact matches an old
release. To produce a new reviewed artifact set from a clean committed checkout:

```sh
SOURCE_REVISION="$(git -C "$ROOT" rev-parse HEAD)"
test -z "$(git -C "$ROOT" status --porcelain)"
docker buildx create --name platform-releases --driver docker-container
# Optional: use only an administrator-provided trusted proxy root CA.
BUILD_TRUST=()
if [ -n "${BUILD_CA_FILE:-}" ]; then
  BUILD_TRUST=(--secret "id=build_ca,src=$BUILD_CA_FILE")
fi
for RELEASE in a b; do
  docker buildx build --builder platform-releases --platform linux/amd64 \
    "${BUILD_TRUST[@]}" \
    --provenance=false --build-arg SOURCE_REVISION="$SOURCE_REVISION" \
    --build-arg RELEASE_ID="gitops-$RELEASE" \
    --output "type=oci,dest=$ARTIFACTS/platform-frontend-$RELEASE.tar" "$ROOT/frontend"
done
docker buildx build --builder platform-releases --platform linux/amd64 \
  "${BUILD_TRUST[@]}" \
  --provenance=false --output "type=oci,dest=$ARTIFACTS/platform-backend.tar" "$ROOT/backend"
tar -xOf "$ARTIFACTS/platform-frontend-a.tar" index.json | jq .
sha256sum "$ARTIFACTS/"*.tar
```

Record the index's single image manifest digest and that manifest's config
digest, source revision and platform in a reviewed catalog update; update the
target image references together. OCI identity is byte-precise; these builds
are not claimed to be byte-for-byte reproducible. Do not garbage-collect the
previous artifacts until their rollback window expires.
The optional `build_ca` BuildKit secret establishes build-time npm/Maven trust
without disabling TLS verification or copying a private key into an image.

### Git-driven frontend upgrade and rollback drill

Start with the catalog's baseline frontend selected and Argo CD `Synced`,
`Healthy`, and latest operation `Succeeded`. Do not change backend, PostgreSQL,
configuration, secrets, PVC, or Flyway during this drill.

Before starting, complete bootstrap in the order above, verify the controller
and repo-server rollouts, and verify/import all three exact catalog artifacts.
Run the existing checks without enabling the live drill:

```sh
PLATFORM_GITOPS_ARTIFACTS="$ARTIFACTS" \
  PLATFORM_GITOPS_ACCESS_TEST=true PLATFORM_K3S_CONTEXT="$K3S_CONTEXT" \
  python3 -m unittest discover -s "$ROOT/automation/tests" -p test_gitops.py -v
```

Leave `PLATFORM_GITOPS_TEST` unset for this preflight; archive verification and
RBAC checks are prerequisites, not sync/upgrade/revert proof. Choose an
access-controlled durable evidence destination before running. Use a fresh
absolute directory under `/tmp`, outside the checkout, for each attempt so an
old `PASS` file cannot be mistaken for a new result.

1. Create an environment through the authenticated API and wait for `READY`.
   Retain it as a persistence fixture; record its environment ID and operation
   history. Do not delete it while the drill runs.
2. In one Bash terminal, start the opt-in test using the existing trusted HTTPS
   endpoint and a configured OPERATOR account (do not enable shell tracing):

   ```bash
   export PLATFORM_K3S_CONTEXT="$K3S_CONTEXT"
   export PLATFORM_K3S_API_URL=https://platform-test.local:8443
   export PLATFORM_K3S_CA_FILE="$TLS_DIR/tls.crt"
   export PLATFORM_K3S_OPERATOR_USERNAME=your-configured-operator
   read -r -s -p 'Operator plaintext password: ' PLATFORM_K3S_OPERATOR_PASSWORD; echo
   export PLATFORM_K3S_OPERATOR_PASSWORD
   export PLATFORM_GITOPS_ENVIRONMENT_ID=your-ready-environment-uuid
   GITOPS_RUN_DIR="$(mktemp -d /tmp/platform-gitops.XXXXXX)"
   export PLATFORM_GITOPS_EVIDENCE="$GITOPS_RUN_DIR/evidence.json"
   PLATFORM_GITOPS_TEST=true python3 -m unittest discover \
     -s "$ROOT/automation/tests" -p test_gitops.py -v 2>&1 | tee "$GITOPS_RUN_DIR/test.log"
   GITOPS_TEST_STATUS=${PIPESTATUS[0]}
   unset PLATFORM_K3S_OPERATOR_PASSWORD
   test "$GITOPS_TEST_STATUS" -eq 0
   ```

3. Wait for the test to print `Waiting for Git reconciliation of gitops-b`.
   Change **only** the frontend digest in the target Kustomization to the
   catalog's second frontend manifest digest. Commit/review/publish that change
   through the normal repository workflow on the Application's watched branch.
   Argo CD must reconcile the new Git commit; inspect its status and frontend
   rollout. The test waits up to 20 minutes per phase.
4. When the test prints `Waiting for Git reconciliation of gitops-a`, create and
   publish a **new revert commit** for that frontend-only release change.
   Do not reset history, change the Application revision, run `rollout undo`,
   or manually patch the Deployment. Wait for Git reconciliation and the test.
5. A passing evidence file records three distinct synchronized Git commits,
   exact desired/runtime frontend digests and served release identities.
   Assertions require unchanged backend/PostgreSQL specs, Deployment/pod UIDs
   and restart counts, Flyway history, PVC and Secret UIDs, and authenticated
   environment/operation history, with backend database readiness `UP`.
   It records only a hash of fixture state, not credentials or API contents.
   The JSON is written **only after all three phases pass**. Require both a
   zero test exit status and a fresh `result: PASS` file from this attempt;
   skipped tests, partial phases, or an old evidence file are not success.
   Archive `evidence.json` and `test.log` externally with the release catalog,
   references to the three reviewed release commits, and the retained OCI
   archives/checksums from the artifact procedure above. Include a sanitized
   run record with UTC date, target/context, Application source/branch,
   checked-out test revision, fixture environment/operation IDs, command,
   outcome and durable artifact location. The JSON records the checked
   invariants and a state hash, not raw before/after state or API responses;
   retain the test log with it to make the comparison outcome explicit.
   Verify the bundle is readable from the durable destination before removing
   task-local files, then update GITOPS-01 in the canonical roadmap with its
   sanitized evidence reference. Never archive credentials, kubeconfig, Secret
   contents or unsanitized diagnostic output. `/tmp` alone is not retention.
   Clean up the fixture **through the API**, not by deleting namespaces.

Failure or interruption does not trigger destructive cleanup or an automatic
rollback. Inspect Argo CD conditions, pod events, artifact availability and
readiness. Fix the Git change or publish its reviewed revert. A retained frontend
artifact cannot repair a database migration. Database restore is a separate,
backup-dependent procedure; it remains unvalidated here.

If controller startup, image pulls, DNS, or any phase fails, retain the test log
and sanitized Application conditions/pod events as a **blocked/failed attempt**,
with target, revision, UTC date, blocker and next action. The test does not
write failure evidence JSON. Do not claim the gate complete or remove the
historical blocker based on bootstrap, artifact imports, or RBAC checks alone.
Resolve image/DNS availability without weakening TLS, ownership or permissions,
then repeat the entire baseline → upgrade → revert drill in a fresh run directory.
This checks state preservation during frontend reconciliation, not persistence
across a backend/database restart or production recovery.

### Validation status

The offline GitOps contract tests render both targets and check ownership,
non-pruning behavior, permissions and pinning. Run with existing unittest
discovery; the live drill is skipped unless explicitly enabled. Controller
installation rendering requires access to the commit-pinned upstream URL.
Set `PLATFORM_GITOPS_ARTIFACTS` to the absolute archive directory to additionally
verify archive checksums, manifest/config digests and embedded frontend identity.
After bootstrap, `PLATFORM_GITOPS_ACCESS_TEST=true` with
`PLATFORM_K3S_CONTEXT` enables read-only impersonation checks for both controller
and server permission boundaries; the operator needs impersonation permission.

**2026-10-17:** a disposable K3s v1.35.1+k3s1 node was started for validation,
and the pinned CRDs and namespace-scoped Argo CD resources applied successfully.
Controller startup was blocked by image downloads from `cdn01.quay.io`
(`ErrImagePull`, DNS failure). **No successful Git synchronization,
frontend upgrade/revert, or persistence proof is claimed.** A target-cluster
operator must complete the drill and retain its evidence before marking the
roadmap acceptance boundary complete. Registry publication, multi-node artifact
distribution, backend/schema rollback, backup recovery and production readiness
remain excluded.
Both frontend OCI archives and the fixed backend archive were successfully
built from source revision `2c2e05c7765a7481883936af6a8700028c085cea`,
using the environment's trusted proxy CA via BuildKit secrets. Archive hashes,
manifest/config digests and frontend release identities were verified against
the catalog. They are retained at `/tmp/platform-frontend-a.tar`,
`/tmp/platform-frontend-b.tar` and `/tmp/platform-backend.tar` in this task
environment only: **this is not durable artifact publication**. Copy them to
operator-managed storage before this environment expires, or build and review
a new artifact set and update the catalog/target. Do not interpret the committed
digests as downloadable registry artifacts.
The three exact catalog image references were also imported and verified in the
disposable node's containerd store. For the containerized K3s validation node,
the CLI was `ctr --address /run/k3s/containerd/containerd.sock --namespace k8s.io`
instead of the host installation's `k3s ctr` wrapper.

Additional validation for this increment:

- Full automation discovery with `PLATFORM_GITOPS_ARTIFACTS=/tmp`: **94 tests,
  no failures, nine opt-in tests skipped**.
- GitOps-specific checks with artifact verification and live RBAC enabled:
  **12 tests, no failures, only the end-to-end Git rollback drill skipped**.
  Both controller/server identities were denied namespace/PVC/Deployment deletion,
  platform Secret reads, cluster RBAC creation and environment-namespace writes.
- Bootstrap and digest-pinned target: **server-side dry-run passed** after the
  prerequisite namespace existed. All three OCI image imports passed.
- Frontend: **134 tests passed**, lint and production build passed.
- Backend Maven `verify`: **141 tests, no failures, five kind-gated tests skipped**.
- Secret scanning: no secrets found. CodeQL Python analysis: zero alerts.
  The automated model-based review was unavailable; a separate read-only code
  review completed with no significant findings.

These results validate packaging, artifact identity and permission boundaries;
they do not replace the blocked controller synchronization/rollback drill.
