# Internal Developer Platform (IDP)

An Internal Developer Platform for provisioning and operating software lifecycle
environments through a self-service portal. The intended audience is approximately
3,000 developers; this is not a measured capacity claim. The accepted target
architecture records separate production-continuity sites, not deployed production
clusters. See the [Phase B architecture decision](docs/decisions/phase-b-target-architecture.md).

> **Current scope:** Kubernetes deployment is implemented for a dedicated
> disposable single-node K3s cluster. Manual create → `READY` → delete →
> namespace-absence validation was recorded on October 16, 2026. GitOps, logical
> backup/restore, and lifecycle-capacity tooling exist; their remaining evidence
> gates are tracked in the [current roadmap](docs/operating-model-and-sequence.md).
> This is not production readiness or measured support for 3,000 developers.

**Disclaimer:** This is an independent portfolio project inspired by platform
engineering challenges. It is not an eBay product and does not reproduce eBay’s
proprietary systems.

## Documentation map

Use the [Operating Model and Delivery Sequence](docs/operating-model-and-sequence.md)
as the single authoritative current status, acceptance-gate, and priority list.
This README is an entry point; it does not maintain a second status matrix.
For the durable post-PR #27 remaining-work record, start with the
[roadmap table](docs/operating-model-and-sequence.md#current-delivery-gates)
and its proposed execution sequence.

| Need | Document |
|---|---|
| Current status, evidence gates, open decisions, and proposed priorities | [Operating Model and Delivery Sequence](docs/operating-model-and-sequence.md) |
| Local request → provision → observe → delete walkthrough | [Demo and troubleshooting](docs/demo.md) |
| kind, Compose, K3s, GitOps, and operational drill instructions/evidence | [Infrastructure guide](infra/README.md) |
| Optional read-only visual cluster checks | [FreeLens operator checklist](docs/runbooks/freelens-operator-checklist.md) |
| Windows FreeLens / Linux K3s connection recovery and token choices | [FreeLens setup and recovery](docs/runbooks/freelens-setup-recovery.md) |
| Cleanup, logical restore, and capacity tooling | [Automation guide](automation/README.md) |
| API contract | [API reference](docs/api.md) |
| Accepted architecture decisions | [Decision records](docs/decisions/) |
| Repository rename preparation and verification | [Repository rename checklist](docs/repository-rename-readiness.md) |
| Historical pre-Kubernetes assessment | [Archive notice](docs/pre-kubernetes.md) |
| Backend and frontend setup/contracts | [Backend guide](backend/README.md) · [Frontend guide](frontend/README.md) |

**Start the local demo:** see [docs/demo.md](docs/demo.md), then the
[backend setup](backend/README.md#run-locally) and
[frontend setup](frontend/README.md). The separate single-host
[API-only container path](infra/README.md#hardened-single-host-deployment-api-only)
does not replace the host-run kind lifecycle demo; container provisioning is
disabled there.

## 1. Project Purpose

The Internal Developer Platform helps developers request and operate environments
throughout the software development lifecycle.

The platform will let a developer:

1. Select a supported sample service.
2. Request an isolated development environment.
3. Follow provisioning progress.
4. View application readiness and diagnostic information.
5. Delete the environment.
6. Request safe cleanup for an expired environment through the operator-invoked
   cleanup workflow.

The platform prioritizes working infrastructure, understandable failures, and
safe automation over a large feature list. Its target architecture is documented
separately from currently implemented and validated capabilities.

## 2. Goals

- Build a usable self-service portal with React and TypeScript.
- Design a Java/Spring Boot REST API.
- Implement persistent asynchronous operations.
- Provision real resources in a local Kubernetes cluster.
- Automate environment expiration with Python.
- Demonstrate testing, observability, and operational documentation.
- Explain architectural decisions and trade-offs clearly.

### Initial non-goals

The current implementation does not yet include:

- Production workloads or validated production-grade availability.
- Public internet exposure.
- Strong isolation between untrusted tenants.
- Multi-cluster production orchestration.
- Arbitrary user-provided container images or Kubernetes manifests.
- A replacement for a CI/CD system.
- AI-generated automated remediation.

These boundaries describe the current implementation, not a change to the
target architecture. They keep current security assumptions explicit.

## 3. Primary User Journey

### Create an environment (available journey)

The portal submits creation requests through the existing API; it does not access Kubernetes directly.

1. The developer opens the portal and signs in.
2. They enter an environment name, select a member team, and choose an allowed lifetime.
3. The portal submits a request to the backend.
4. The backend records the environment and a provisioning operation.
5. A background worker claims the operation.
6. The worker creates the environment’s Kubernetes resources.
7. The portal displays progress while the worker checks readiness.
8. The environment becomes ready, or the operation reports a useful failure.

### Delete an environment (available journey)

1. The developer confirms deletion of a ready or failed environment in its detail view.
2. The backend records a deletion operation atomically; repeat submissions reuse active work.
3. The worker verifies platform ownership of the target namespace.
4. The worker requests namespace deletion.
5. The platform confirms removal before reporting completion.

Creation in progress cannot be deleted. Deletion retains environment and operation
records for inspection. A failed deletion can be requested again after correcting
the cause; ownership failures must never be bypassed. These resource-ownership
checks are separate from user authorization. `OPERATOR` may delete any environment;
`DEVELOPER` may delete only environments owned by their current teams. Unowned
legacy environments remain operator-managed.

### Expire an environment (available now, manually invoked)

The Python cleanup tool identifies expired environments through the backend API and requests deletion using the same workflow as the portal.

It does not independently delete arbitrary Kubernetes namespaces.
TTL metadata alone starts no daemon. See the [cleanup CLI/runbook](automation/README.md)
for trusted-loopback commands, finite bounds, exit statuses, and failure policy.

## 4. Technology Choices

| Area | Initial choice | Purpose |
|---|---|---|
| Frontend | React, TypeScript, Vite | Developer portal |
| Backend | Java, Spring Boot | APIs and orchestration |
| Backend build | Maven Wrapper | Repeatable builds |
| Persistence | PostgreSQL | Environments, operations, audit history |
| Schema management | Flyway | Versioned database migrations |
| Runtime | Local kind and a dedicated single-node K3s deployment | Sample provisioning and platform deployment paths |
| Local dependencies | Docker Compose | PostgreSQL and supporting services |
| Automation | Python | Expiration and operational scripts |
| Testing | JUnit, backend integration tests, frontend tests | Workflow confidence |
| CI | GitHub Actions | Automated validation |

Supported tool and image versions are recorded in their build files and
operational runbooks; they are not a capacity or production-support statement.

The backend and worker initially live in one application, with separate packages and responsibilities. A separate worker deployment can be introduced later if justified.

## 5. Development Workflow

### VS Code

Use VS Code for:

- React and TypeScript.
- Documentation.
- Kubernetes manifests.
- Python automation.
- Repository-wide configuration.

### IntelliJ IDEA

Use IntelliJ IDEA for:

- The Spring Boot backend.
- Java application code.
- Backend tests.
- Debugging API and worker behavior.

### Shared repository

Both editors open directories from the same local clone:

- Open the repository root in VS Code.
- Open `backend/` as a Maven project in IntelliJ IDEA.
- Use one Git working tree and the same feature branch.
- Avoid editing the same file simultaneously in both editors.

No separate frontend and backend repositories are required.

## 6. Architecture

```mermaid
flowchart TD
    Developer[Developer] --> Portal[React Portal]
    Portal --> API[Spring Boot REST API]
    API --> DB[(PostgreSQL)]

    Worker[Background Operation Worker] --> DB
    Worker --> K8s[Kubernetes API]
    K8s --> Namespace[Managed Environment Namespace]
    Namespace --> App[Sample Application]

    Cleanup[Python Expiration Tool] --> API
```

### Responsibility boundaries

**Portal**
- Collects input.
- Displays environment and operation state.
- Shows actionable errors.
- Never receives Kubernetes credentials.

**REST API**
- Validates requests.
- Persists desired changes and operations.
- Returns operation identifiers.
- Exposes status and diagnostic information.

**Worker**
- Claims pending operations.
- Reconciles requested resources with observed resources.
- Checks readiness.
- Records progress, failures, and completion.

**PostgreSQL**
- Stores requested state and operation history.
- Does not replace Kubernetes as the source of observed runtime state.

**Kubernetes**
- Runs the sample application.
- Exposes actual workload readiness and resource state.

**Python automation**
- Finds expired environments through the API.
- Requests safe deletion.
- Supports dry-run mode.

## 7. Repository Structure

```text
internal-developer-platform/
├── frontend/
│   ├── src/
│   │   ├── api/
│   │   ├── components/
│   │   ├── features/
│   │   │   ├── environments/
│   │   │   └── operations/
│   │   └── pages/
│   └── package.json
├── backend/
│   ├── src/main/java/
│   │   └── <base-package>/
│   │       ├── environment/
│   │       ├── operation/
│   │       ├── kubernetes/
│   │       ├── audit/
│   │       └── config/
│   ├── src/main/resources/
│   │   └── db/migration/
│   ├── src/test/
│   └── pom.xml
├── automation/
│   ├── README.md
│   ├── cleanup_expired.py
│   └── tests/
├── infra/
│   ├── README.md
│   ├── kind/
│   └── kubernetes/
├── sample-app/
├── docs/
│   ├── architecture.md
│   ├── api.md
│   ├── demo.md
│   ├── operating-model-and-sequence.md
│   ├── pre-kubernetes.md
│   ├── decisions/
│   └── archive/
├── .github/workflows/
├── compose.yaml
├── .env.example
└── README.md
```

The existing repository name is retained for compatibility; other legacy
technical identifiers are listed in the [Phase B architecture decision](docs/decisions/phase-b-target-architecture.md).

This tree is a navigation aid; use the documentation map above for current
guidance and status.

### Repository layout

The backend base package is `io.github.dcfaight.platformlab`.
The frontend provides an environment creation and status portal. The backend requires PostgreSQL and
implements persistent environment creation/deletion requests and environment/operation
reads, with Flyway migrations and durable idempotency. An opt-in worker provisions
an approved sample workload in local kind and safely removes owned namespaces.
The separate K3s package deploys the platform itself. Manual K3s lifecycle
validation was recorded on October 16, 2026; automated lifecycle, GitOps,
logical-restore, capacity, and restart-persistence evidence gates remain in the
[canonical roadmap](docs/operating-model-and-sequence.md).

## 8. Domain Model

### Environment

Represents a requested development environment.

Key fields:

- `id`
- `name`
- `namespace`
- `templateId`
- `teamId` (nullable for legacy/unowned operator-managed environments)
- `createdBy` (authenticated creator; nullable for legacy rows)
- `status`
- `expiresAt`
- `createdAt`
- `updatedAt`
- `lastErrorCode`
- `lastErrorMessage`

Initial environment states:

- `REQUESTED`
- `PROVISIONING`
- `READY`
- `FAILED`
- `DELETING`
- `DELETED`
- `DELETE_FAILED`

Environment readiness is a last-observed state, not a guarantee of continuous application health.

### Operation

Represents a durable unit of background work.

Key fields:

- `id`
- `environmentId`
- `type`
- `status`
- `attemptCount`
- `nextAttemptAt`
- `leaseExpiresAt`
- `startedAt`
- `completedAt`
- `errorCode`
- `errorMessage`

Initial operation types:

- `CREATE_ENVIRONMENT`
- `DELETE_ENVIRONMENT`

Initial operation states:

- `PENDING`
- `RUNNING`
- `SUCCEEDED`
- `FAILED`

### Audit Event

Records important actions and transitions.

Key fields:

- `id`
- `environmentId`
- `operationId`
- `action`
- `actor`
- `timestamp`
- `details`

`actor` is the authenticated username of the account that requested creation or
deletion (the cleanup account for CLI deletions). Rows written before
authentication existed keep the legacy `local-development` actor, which is now a
reserved, unconfigurable username.

## 9. Proposed REST API

All routes use the `/api/v1` prefix. This table preserves the target blueprint.
Currently implemented: `POST /environments`, `GET /environments` (pagination
only, no status filter), `GET /environments/{id}`,
`GET /environments/{id}/operations` (paginated history), `DELETE /environments/{id}`,
and `GET /operations/{id}`. Environment events routes are not implemented. Operation detail
includes durable progress events. Creation acceptance returns `REQUESTED` / `PENDING`;
deletion acceptance returns `DELETING` with a `PENDING`/`RUNNING` operation.
Only the worker can verify readiness or namespace absence.

| Method | Route | Purpose |
|---|---|---|
| GET | `/environments` | List environments with bounded pagination and filters |
| POST | `/environments` | Request environment creation |
| GET | `/environments/{id}` | Read environment details |
| GET | `/environments/{id}/operations` | Read paginated operation history |
| DELETE | `/environments/{id}` | Request environment deletion |
| GET | `/environments/{id}/events` | Read sanitized platform and runtime events |
| GET | `/operations/{id}` | Read operation progress |

### API behavior

- Creation and deletion return `202 Accepted` when asynchronous work is accepted.
- Responses identify the environment and operation.
- Creation supports an `Idempotency-Key` header.
- New idempotency keys are scoped by caller and team; equivalent replay returns the original result.
- Reusing a key with different input returns a conflict.
- Repeated deletion requests do not create competing deletion operations.
- Errors include a stable code, useful message, and request identifier.
- Kubernetes credentials and sensitive internal details are never returned.

The implemented request and response schemas, validation, pagination, errors,
and durable idempotency guarantees are defined in [docs/api.md](docs/api.md).

## 10. Reliable Background Work

The first version uses a PostgreSQL-backed operation queue instead of adding a message broker.

Design requirements:

- Create the environment and operation in one database transaction.
- Claim work atomically.
- Use leases so abandoned work can be recovered after a worker restart.
- Use bounded retries with backoff for transient failures.
- Do not automatically retry permanent validation or permission errors.
- Make provisioning safe to repeat.
- Use deterministic namespace names derived from environment identifiers.
- Set deadlines for provisioning and deletion.
- Preserve useful failure context.

No database transaction remains open while waiting for Kubernetes.

Successful operations retain enough history to explain what happened.

## 11. Kubernetes Provisioning

The current provisioned workload is one approved `sample-service` Deployment
with resource requests/limits, readiness/liveness probes, and platform ownership
metadata. It does not create a workload Service, Ingress, or NetworkPolicy.
Platform Services and the platform TLS Ingress in the K3s package serve the
portal/API; they are not workload exposure for provisioned environments.

The broader target blueprint has described environment resources as:

- A Deployment and internal Service for an approved sample application.
- Application configuration, resource requests and limits, and a ResourceQuota.
- Readiness and liveness probes, platform ownership labels, and network policies
  where supported by the selected cluster networking.

Namespace separation alone is not a strong security boundary.

### Safety requirements

- Validate namespace identifiers.
- Restrict operations to the configured local cluster.
- Never delete a namespace based only on its name prefix.
- Verify ownership labels and the matching persisted environment record.
- Use narrowly scoped permissions where feasible.
- Document permissions that necessarily operate at cluster scope.
- Never store kubeconfig files or secrets in Git.
- Do not accept arbitrary manifests from portal users.

The local demo accesses the sample application through a documented port-forward
workflow rather than public workload ingress.

## 12. Frontend Experience

The list, creation form, detail/history, and confirmed deletion experience are
available now.

### Environment list

- Name and status.
- Creation and expiration times.
- Refresh behavior.
- Empty, loading, and error states.

### Create environment form

- Validates the trimmed name and 1–24-hour lifetime; only the approved `sample-service` template is selectable.
- Reuses a tab-scoped idempotency key and exact payload after an ambiguous result; starting a different request is explicit and warns the original may exist.
- Distinguishes acceptance from readiness and explains that lifetime metadata does not trigger cleanup.

### Environment detail

- Provisioning progress.
- Latest observed readiness.
- Operation history.
- Sanitized diagnostic events.
- Delete confirmation, pending/failed outcomes, and durable deletion history.

The UI distinguishes “request accepted,” “resources created,” and “application ready.”

## 13. Cleanup Automation

The Python expiration tool:

1. Scans bounded API pages, deduplicating IDs (not a cross-page snapshot).
2. Selects expired `READY`/`FAILED` environments at one UTC cutoff.
3. Displays intended actions by default; only `--execute` requests deletion.
4. Rechecks detail and uses an atomic server-side expiration/state guard.
5. Never retries DELETE or terminal `DELETE_FAILED` operations automatically.
6. Emits a structured summary and nonzero exit for errors/incomplete scans.

Execution is manual and trusted-loopback only; no scheduler/CronJob is added.
Both modes authenticate with a `CLEANUP` account from environment variables.
This exclusive automation role has list/detail discovery of all environments
(including legacy rows) and expiry-guarded deletes, but cannot read operation
history, direct operations or metrics.
`202` is acceptance, not namespace removal. Database/audit history is retained.
See [commands, bounds, recovery and exit codes](automation/README.md) and the
[create → expire walkthrough](docs/demo.md#5-manual-expiration-cleanup).
SSO and stronger tenant/deployment hardening remain prerequisites for broader use.

## 14. Local Setup Plan

Follow the executable [local demo guide](docs/demo.md):

1. Install Java 17, Node.js 24/npm 11, Docker with Compose, kind, kubectl, curl, and jq.
2. Configure the ignored `.env`, start PostgreSQL, and export the backend `DB_*` variables.
3. Create the dedicated kind cluster and enable the worker in the host-run backend.
4. Start the Vite frontend and submit a request through the portal.
5. Replay safely and observe the operation in the API and portal.

The worker uses a fixed sample image; no custom application build/load is required.
For API-only use, skip kind and leave the worker disabled; requests remain pending.

All API reads and writes require HTTP Basic credentials for accounts you configure at backend
startup ([how](backend/README.md#authentication-and-authorization)); only health
probes stay anonymous. Plain HTTP Basic and missing production deployment hardening
mean this project must still run only in a trusted local environment. Do not
expose it publicly.

### Persistent backend quick start (available now)

Prerequisites: Node.js 24 with npm 11, Java 17, and Python 3.12.
The backend includes Maven Wrapper; a global Maven installation is not required.
Frontend dependencies are pinned in `frontend/package-lock.json`, and the
Spring Boot 4.1.1 and Maven versions are pinned in the backend build files.
Docker with Compose is required for local PostgreSQL; backend integration tests
also require Docker (Testcontainers `postgres:18.6`, no skipping).
kind and kubectl are needed only for the optional local cluster.

From the repository root, prepare local PostgreSQL:

```sh
cp .env.example .env
# Edit .env and set POSTGRES_PASSWORD to a nonempty local-only value.
docker compose up -d --wait postgres

# Spring Boot does not automatically load Compose's .env.
# Source only your trusted local file: this executes shell code.
set -a
. ./.env
set +a
export DB_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/${POSTGRES_DB}"
export DB_USERNAME="$POSTGRES_USER"
export DB_PASSWORD="$POSTGRES_PASSWORD"
```

Run the backend in that same shell (or configure the same `DB_*` variables in
IntelliJ). Run the frontend in a separate terminal:

```sh
cd backend
./mvnw spring-boot:run
```

```sh
cd frontend
npm ci
npm run dev
```

The portal is at http://localhost:5173. Use **Create environment** to request the
approved sample, or use the curl examples below to exercise API replay. Vite proxies `/api` to the
local backend, so no CORS configuration is needed. It lists environment status,
template, namespace, created/expires timestamps, and latest/active operation.
Select an environment to see its details, paginated operation history, recorded
progress steps/events, attempt count, lifecycle timestamps, request correlation,
and sanitized failure summaries. Views refresh every 10 seconds or on demand;
failed refreshes explicitly retain the last loaded data. Times use the browser's
local timezone. Eligible environments expose a guarded delete action in detail;
acceptance is not completed removal. Browsing, create and delete require signing in
with `OPERATOR` or `DEVELOPER`; credentials stay in page memory only. Developers
must select a member team; operators may choose an explicit team or leave creation
unowned for compatibility. `/me` reports `username`, `roles`, and `teams`.
Trusted SQL administrators provision `platform_teams` and
`account_team_memberships`, not account-config team fields; see the
[team provisioning example](docs/api.md#trusted-team-provisioning).

With the worker disabled, the portal honestly shows `REQUESTED` / `PENDING`;
enable the optional local-kind worker to observe provisioning and readiness.
Expiry timestamps do not trigger cleanup. Ambiguous browser submissions can be
retried with their stored key and payload in the same tab session; this does not
cancel server work. This is a bounded portfolio implementation, not production
software; see the [current roadmap](docs/operating-model-and-sequence.md) and
[frontend guide](frontend/README.md) for scope and limitations.
The backend listens on http://127.0.0.1:8080 and applies Flyway migrations on
startup. `DB_URL` defaults to
`jdbc:postgresql://127.0.0.1:5432/platform_lab`, `DB_USERNAME` to `platform_lab`,
and `DB_PASSWORD` is required with no default.
See [backend/README.md](backend/README.md#manual-api-checks) for copyable curl
checks covering creation, normalized replay, conflicts, list/detail reads,
errors, and restart persistence. The platform-wide examples use an `OPERATOR` account
(curl prompts with `-u <username>`); no CORS is added. The audit actor is the
authenticated username, never supplied by caller headers.
See [infra/README.md](infra/README.md) for the optional kind cluster and
[backend/README.md](backend/README.md) to enable the provisioning worker.
The approved sample uses a fixed public image, not a user-supplied manifest.

Checks from the repository root (also run by CI):

```sh
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend test
npm --prefix frontend run build
docker info
(cd backend && ./mvnw verify)
python -m compileall -q automation
docker compose config --quiet
```

The Compose check requires the configured password in `.env`.
On Windows use `mvnw.cmd` in place of `./mvnw`.
Stop PostgreSQL with `docker compose down`; data persists in a named volume.
In this local Compose/host-run workflow, backend and PostgreSQL restarts preserve
environments, operations, audit history, and idempotency reservations. Replaying a key after restart returns
the original accepted response without additional writes.
K3s restart persistence requires the separate opt-in
[same-PVC validation gate](infra/README.md#disposable-k3s-restart-persistence);
no passing K3s restart result is recorded.
Use `docker compose down --volumes` only when intentionally discarding local data.

## 15. Testing Strategy

### Backend

The current slice is verified with `./mvnw verify` and a mandatory Docker-backed
Testcontainers PostgreSQL 18.6 instance. CI runs `docker info` before verifying;
Docker unavailability is a failure, not a skipped integration suite.
Worker and lifecycle checks below remain part of the target strategy.

- Input validation.
- State transitions.
- Request idempotency.
- Atomic operation creation.
- Worker claiming and lease recovery.
- Retry classification.
- Safe deletion checks.
- Database integration tests.

### Frontend

- Form validation.
- Loading, empty, and failure states.
- Operation polling.
- Delete confirmation.
- API error presentation.

### Automation

- Expiration selection.
- Dry-run behavior.
- Repeated execution.
- API failures.
- Already-deleting environments.

### End-to-end

- Create an environment successfully.
- Observe readiness.
- Diagnose an intentionally unhealthy workload.
- Recover interrupted provisioning.
- Delete an environment.
- Clean up an expired environment.

Tests requiring a real cluster will be identified separately from fast pull-request checks.

## 16. Observability

Initial observability includes:

- Structured backend logs.
- Request, environment, and operation identifiers.
- Health endpoints.
- Operation duration and outcome measurements.
- Sanitized Kubernetes events.
- Audit history.

Measurements will distinguish:

- Request acceptance latency.
- Queue waiting time.
- Provisioning duration.
- Application readiness time.
- Deletion duration.

Any reported performance results will include the test environment and method.

An opt-in [lifecycle capacity-baseline harness and operator runbook](automation/README.md#opt-in-lifecycle-capacity-baseline)
defines an initial experimental profile of 10 `sample-service` lifecycles with
at most 2 concurrent workflows including cleanup. Tooling and offline tests are
not a validated measured run, an autoscaling system, a monitoring stack, or a
production-capacity claim; retained target-specific evidence is still required.

## 17. Delivery Roadmap

Current implementation, validation evidence, and remaining milestones are
tracked only in the [Operating Model and Delivery Sequence](docs/operating-model-and-sequence.md).
The journey and acceptance ideas below are descriptive blueprint material, not
a second current status checklist.

### Later candidates

- SSO/OIDC and stronger tenant isolation (Basic reads/writes, team authorization,
  and the separate TLS container portal exist now).
- Service catalog and ownership.
- Multiple approved templates.
- Deployment versions and history.
- Broader GitOps promotion beyond the bounded local frontend rollback drill.
- AI-assisted diagnosis using sanitized evidence.
- Multi-cluster support.

Later features are not commitments for the initial release.

## 18. Demo Acceptance Criteria

The initial release is complete when a reviewer can:

1. Follow the documented setup from a clean clone.
2. Create an environment through the portal.
3. See real Kubernetes resources created.
4. Observe a healthy sample application.
5. Inspect an intentionally failed provisioning attempt.
6. Confirm duplicate requests do not create duplicate resources.
7. Restart the backend without silently losing pending work.
8. Delete an environment safely.
9. Demonstrate expiration cleanup in dry-run and execution modes.

## 19. Interview Discussion Topics

This project should support a clear discussion of:

- Why provisioning is asynchronous.
- How repeated requests are handled.
- How workers recover after failure.
- Why namespace ownership checks matter.
- How desired state differs from observed state.
- Why the MVP avoids unnecessary microservices.
- How developer productivity could be measured.
- What would need to change before enterprise deployment.

## 20. Limitations

This project demonstrates engineering decisions in a controlled environment.

It does not establish:

- Enterprise-scale performance.
- Production readiness.
- Compliance certification.
- Strong multi-tenant isolation.
- Operational experience at eBay’s scale.

Known limitations and unfinished features will remain visible as implementation progresses.

## How we make decisions here

When a choice is not fully determined by the current evidence, prefer the simplest defensible option that still fits the documented goals and constraints.

- Use existing repository evidence first, before introducing new assumptions.
- Make a clear recommendation rather than waiting for perfect certainty.
- State assumptions explicitly so future changes can be reviewed against them.
- Defer only the parts that genuinely require more evidence or a later decision.
- Keep unresolved items visible, bounded, and clearly labeled.
- Avoid blocking progress unless the choice materially affects correctness, safety, scope, or recoverability.
- If later evidence disproves an assumption, update the decision and explain the change.
