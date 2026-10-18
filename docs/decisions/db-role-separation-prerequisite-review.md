# PostgreSQL Role Separation: Slice Disposition and Implementation Work Order

**Record type:** Proposed slice-specific dispositions for maintainer review; not accepted decisions

**Status:** Preparation is complete; decision acceptance and live validation are not

**Scope:** A disposable PostgreSQL target and the runtime/migration role-separation slice only

## Purpose and status

This replaces the earlier prerequisite outline in place. It gives the maintainer
one bounded set of proposed dispositions, a grant inventory derived from the
current migrations and SQL, explicit unresolved inputs, and a copyable
implementation work order. It does not add a second recommendation layer, change
an ARCH-01 status or acceptance criterion, authorize implementation, or claim a
test, recovery, or deployment result.

The canonical status and sequence remain in the
[operating model and roadmap](../operating-model-and-sequence.md#candidate-db-role-slice-blocking-versus-can-wait-checklist).
All ARCH-01A through ARCH-01G records remain **Proposed**. PR #46 merged this
review document; it did not accept any disposition.

Preparation items completed by this document:

- [x] Inspect the A–G decision records, roadmap, migrations, SQL repositories,
  application configuration, Compose setup, and current test paths.
- [x] State a concrete recommendation and follow-on work order for maintainer
  review.

These are not readiness or acceptance checks:

- [ ] Required reviewers confirm the applicable dispositions under each ADR's
  existing acceptance criteria and record accepted/deferred outcomes in those
  ADRs and the roadmap.
- [ ] The named accountable maintainer, database authority, independent
  credential/recovery custody path, and any required review exception are
  confirmed.
- [ ] A separate implementation and PostgreSQL-backed validation change is
  reviewed and run on a disposable target.

## Slice boundary and criteria reconciliation

The proposed first target is a **fresh, isolated local Compose PostgreSQL 18.6
instance** using the repository's existing Compose service (`compose.yaml`):
one disposable database on one developer-controlled host, reached through its
loopback-published port and stored in one named volume created for the
disposable test. The host, PostgreSQL container, and volume are one failure
domain. This is a target proposal, not a verified running setup or independent
backup, and does not make an existing persistent Compose volume disposable. It
excludes the K3s target, production, `prod1`/`prod2`, other developer workloads,
and any continuity or availability claim.

The current local setup is not already role-separated: the official PostgreSQL
image initializes the `POSTGRES_USER` bootstrap account, while
[`backend/README.md`](../../backend/README.md#run-locally) exports that same
account as the application's `DB_USERNAME`; Spring Boot applies Flyway at
application startup because
[`application.yaml`](../../backend/src/main/resources/application.yaml) does
not disable it. The proposed implementation must replace that coupling, not
describe it as an existing control.

The candidate slice is narrow, but it is **not an exception to an ADR's
acceptance criteria**. A reviewer may accept an ARCH-01 decision only after the
decision record satisfies its own criteria, including any permitted, owned
deferrals. A narrow grant matrix cannot by itself accept all of ARCH-01F or
silently waive A/B/C/E/G requirements. If a criterion cannot be completed by
scoping the decision and recording an explicit permitted deferral, that ADR
remains Proposed and the dependent implementation remains blocked. The
recommendations below are intended for recording in the existing A–G records,
not as a substitute decision or an acceptance shortcut.

| Review order | Proposed slice disposition | Existing criterion that still governs acceptance |
|---|---|---|
| **A + C jointly** | Restrict this change to a single disposable Compose target. The Docker/PostgreSQL host operator is the proposed target administrator. Make no SLA, support, RPO/RTO, restore-success, or independent-failure-domain claim. Preserve a separately available bootstrap/recovery path and do not start mutating services after a restore until data and grants are checked. | **A** requires a named accountable owner and responsibility matrix, boundary/failure-domain account, approved availability/operating/cost constraints, and option comparison or a compliant deferral. **C** requires in-scope state/failure-scope objectives or explicit owned, time-bounded deferrals, recovery authority, dependency order, and measurable future evidence. The proposed 24-hour RPO/4-hour RTO in the recommendations are not accepted or measured. Record A and C together; do not infer objectives from RESTORE-01. |
| **B** | For this change, the proposed flow is local API/optional worker → the disposable Compose database. Keep developer workloads, K3s, production and other environments outside the target; do not promote credentials or data between them. This DB login is not a tenant boundary: existing API authorization remains responsible for team isolation. | **B** requires a placement/ownership matrix and complete allowed-flow/dependency map for every environment in the accepted scope, plus lifecycle transitions and reconciliation with A/C. The maintainer must explicitly scope this target and disposition the remaining environments; an unlisted flow is not implicitly approved. |
| **D / E** | Grant no peer-site or production authority. Preserve the accepted `prod1`/`prod2` intent without selecting a continuity mechanism. Preserve AUTH-01's operator, team-scoped developer, and expiry-only cleanup behavior. A trusted SQL administrator continues to provision teams and memberships; runtime gets reads only. Keep OIDC, a membership API, and a UI out of scope. | **D** still requires its own site/data-authority and transition criteria or permitted owned deferrals before continuity work. **E** still governs credential authority, approval/execution, recovery access, and revocation; its broader identity acceptance criteria cannot be declared satisfied by this database slice. Confirm whether independent approval is available or record an owned, expiring exception under E/F. |
| **F / G** | Separate runtime, migration/schema ownership, membership administration, backup/export, restore, and bootstrap/recovery duties. Approve the exact runtime matrix below before provisioning grants. Preserve existing backup/restore interfaces; do not add a backup, restore, PITR, HA, or failover mechanism here. | **F** requires its reviewed flow/credential inventory, named duties, grant matrix, lifecycle bounds or compliant deferrals, and future tests under its criteria. **G** requires data-scope, authority, key, target-isolation and post-restore dispositions; mechanism deferrals do not waive them. Reconcile both with C/E and D. |

### Concrete responsibility and credential-flow recommendation

Subject to explicit confirmation, **dcfaight is the proposed accountable
maintainer and approver for this independently operated repository**. This is
a candidate, not a confirmed assignment or approval. The same proposed owner
would account for the disposable target, approve scoped role/grant changes,
arrange recovery access, and record review evidence. Do not invent an operating
team, a second reviewer, or an existing external secret store.

| Identity / action | Proposed authority and flow | Boundary |
|---|---|---|
| Target/bootstrap administrator | The confirmed target operator uses an independently retrieved bootstrap credential to provision/revoke database roles and recover access. Use an interactive local administration path; it must not depend on the API or worker being available. | Not mounted into or exported to API/worker processes; not used for routine application or Flyway connections. |
| Migration | A separate migration login runs Flyway against the selected database. It owns or can assume only the schema-owner authority required for reviewed schema migrations; it is not a PostgreSQL superuser and does not administer login roles. | Secret is available only to the explicit migration invocation; enable login only for the approved run window, then disable it and terminate its sessions. Runtime startup migration is disabled. |
| Runtime | One runtime login is used by the API and optional worker. It has only the table/sequence operations in the matrix; its credential is supplied to those processes only. | No DDL, schema/object ownership, role/grant authority, membership writes, backup/restore administration, or membership in any role that can confer those powers. |
| Membership administration | A trusted human administrator executes approved SQL changes to `platform_teams` and `account_team_memberships`, separately from API/runtime and migration. | No membership-management endpoint, runtime writes, or shared app credential. Approval and execution owners must be confirmed. |
| Backup/export | A separate, narrowly scoped read identity is proposed only if/when an export is explicitly authorized; its destination access is separately controlled. | Not runtime or migration identity; no source writes or blanket `pg_read_all_data` assumption. No backup schedule or copy is established here. |
| Restore | A distinct operator identity restores only into an approved isolated target, then verifies data, schema ownership, grants, role membership, and revoked credentials before any mutating runtime/worker is enabled. | Not runtime, migration, or routine backup identity. Restore authority and post-restore approver must be confirmed. |

**Credential custody is a prerequisite, not a deferral.** Before implementation,
the maintainer must confirm who can retrieve the bootstrap/recovery credential
when the API, worker, and database are unavailable, where it is held, how it is
protected, and how retrieval is audited. A candidate safe procedure is an
individually controlled, encrypted/offline credential store with a tested
operator retrieval path, plus interactive role provisioning and rotation. The
repository does not establish that such a store or independent custodian exists;
the local `.env` example and a Kubernetes Secret are not proof of independent
custody. No password, hash, key, or custody location is to be committed here.

Create runtime and migration credentials through the approved bootstrap path;
deliver them only to their respective processes; rotate by issuing and verifying
the replacement, then revoking the old credential and its sessions. Record
numeric issuance/revocation bounds or an owned, expiring exception where E/F
require them. Proposed bounds for this disposable target, pending E/F approval:
rotate static runtime and migration passwords at least every 90 days; enable
migration login only for an approved run window of at most one hour, then
disable login and terminate its sessions; after confirmed disclosure, disable
the old login and terminate its sessions within one hour, then verify the
replacement before resuming service. Do not overlap old/new credentials beyond
the controlled cutover. These are review proposals, not accepted or automated
controls. Never recover by restoring migration or superuser credentials to the
runtime environment.

### Explicit missing confirmations and conflicts

The source is sufficient to define the database objects and proposed SQL
privileges. It cannot identify the human who accepts the owner role, the actual
custodian/retrieval path for a bootstrap credential, or an independent reviewer.
The maintainer's finite decision is to confirm dcfaight or name the actual
accountable person, record the real custody/retrieval and approval arrangement,
and either identify an independent reviewer or explicitly accept a bounded
review exception under E/F. The proposed exception, only if independent review
is unavailable, is limited to this disposable target: dcfaight executes and
approves the initial bootstrap/grant change, records the sanitized ACL/ownership
results and comparison to the approved matrix in the change, and obtains a
separate review before expanding to another target or production. The exception
expires no later than 90 days after approval and must be reapproved or replaced
before further privileged changes. This is not an accepted exception or a
substitute for any required authority. These items cannot be safely inferred
from code. A also needs the maintainer's approval of the target's operating and
cost constraints (proposed: manually operated local Compose only, no service
level/on-call commitment and no additional external spend); this repository
does not establish a cost estimate for the host. E/F need approval of the
proposed 90-day/one-hour credential bounds or explicit replacements. C/G need
the named recovery declaration, restore verification and stale-credential
revocation authority, which can be the confirmed maintainer only if that person
explicitly accepts those duties.

The following criteria are broader than this target and cannot be called
complete merely by accepting the table below:

| Criterion conflict | Proposed resolution for the existing ADR review; no criterion waived |
|---|---|
| A's owner, operational/cost constraints and boundary criteria reach beyond one local database. | Define this disposable target as the only in-scope target for this slice; record its actual operator, host/container/volume failure domain and no-production boundary in A. Approve explicit operating and cost constraints or record A-compliant deferrals for everything else. If those fields cannot be approved, leave A Proposed and block the slice. |
| B's criteria require a complete placement/flow matrix; C requires owned dispositions by applicable failure scope. | In B/C, inventory this database/API flow and explicitly disposition every remaining component/environment and failure scope using their existing deferral rules. Keep recovery authority/bootstrap access in scope and mandatory; only broader numeric objectives/mechanisms may be deferred where their criteria allow. If B/C cannot accept this bounded target without unreviewed environment assumptions, leave them Proposed and block. |
| D describes `prod1`/`prod2`, while this change has no site continuity. E/F/G acceptance spans identities, flows, data protection and recovery beyond runtime grants. | Record this target's explicit non-authority for other sites and a bounded, owned disposition for each out-of-slice criterion in D/E/F/G. Preserve all required identity/revocation, credential-custody, backup/restore responsibility, and post-restore verification fields. If an applicable criterion requires an owner or authority not confirmed here, do not substitute a deferral for that required field. |

## PostgreSQL privilege inventory

This recommendation is derived from Flyway migrations
[`V1`–`V7`](../../backend/src/main/resources/db/migration/) and SQL in
`EnvironmentRepository`, `OperationRepository`, `IdempotencyRepository`,
`AuditRepository`, `WorkerRepository`, and `TeamAuthorization` under
`backend/src/main/java/io/github/dcfaight/platformlab/`. It describes the
current application operations, not grants already present in a database.
Prefer column-level `INSERT`/`UPDATE` grants matching the listed columns; the
application needs the listed `SELECT` columns. Validate PostgreSQL's effective
privileges on the actual PostgreSQL 18 target before accepting a grant script.

| Object | Runtime requirement | Explicitly not granted |
|---|---|---|
| `public.environments` | `SELECT` on all columns (`SELECT *`, pagination, team filtering and counts); `INSERT` on `id, name, namespace, template_id, status, expires_at, created_at, updated_at, team_id, created_by`; `UPDATE` only on `status, updated_at` for deletion/worker lifecycle transitions and row locking. | `DELETE`; updates to identity, team ownership, creator, namespace or request data; DDL/ownership. |
| `public.operations` | `SELECT` on all columns; `INSERT` on `id, environment_id, type, status, attempt_count, next_attempt_at, created_at, updated_at, correlation_id`; `UPDATE` only on `status, lease_token, lease_expires_at, attempt_count, started_at, completed_at, failure_code, failure_message, progress_step, updated_at, failure_count, provisioned_at, next_attempt_at` for claim, retry and lease-fenced transitions. | `DELETE`; changing `id`, `environment_id`, `type`, or `created_at`; DDL/ownership. |
| `public.idempotency_requests` | `SELECT` on all columns; `INSERT` on `scope, key, name, template_id, ttl_hours, environment_id, operation_id` for reservation/replay. | `UPDATE`, `DELETE`, DDL/ownership. |
| `public.audit_events` | `INSERT` on `id, environment_id, operation_id, action, actor, created_at`; current application only appends creation/deletion audit records. | `SELECT` (no current query), `UPDATE`, `DELETE`, DDL/ownership. |
| `public.operation_events` | `SELECT` on all columns (`id` is used for ordering); `INSERT` on `operation_id, type, code, message, correlation_id`, leaving identity `id` and default `created_at` to PostgreSQL. | `UPDATE`, `DELETE`, DDL/ownership. |
| `public.platform_teams` | `SELECT (id)` for operator team validation. | `INSERT`, `UPDATE`, `DELETE`, DDL/ownership. |
| `public.account_team_memberships` | `SELECT (username, team_id)` for developer team authorization and environment filtering. | All writes, DDL/ownership. Membership changes remain trusted SQL administration. |
| Identity sequence for `public.operation_events.id` | Permit only the sequence privilege PostgreSQL 18 requires to generate the identity value during `INSERT` (proposed `USAGE`); confirm with an insert test and catalog checks. | `UPDATE`/`setval`; do not grant sequence ownership or broad all-sequence rights. No other application sequence is defined in V1–V7; other IDs are supplied by Java. |
| Application-schema functions | No user-defined function or procedure is created or called by V1–V7/application SQL. PostgreSQL built-ins such as `clock_timestamp()` are used. | No application function `EXECUTE` grants. New functions require separate review; revoke inappropriate default `PUBLIC EXECUTE`. |

Runtime additionally needs database `CONNECT` and schema `USAGE`, but not
database `CREATE`/`TEMP` or schema `CREATE`. Current queries use PostgreSQL
built-ins and do not require runtime-created temporary objects. There is no
runtime access requirement for `flyway_schema_history`.

The matrix is incomplete unless the effective privilege check also covers:

- Database-, schema-, table-, sequence- and function-level ACLs, including
  `PUBLIC`; database `CONNECT`/`TEMP` and the target's actual `public` schema
  grants must be inspected, not assumed from version defaults.
- Default privileges for the migration/object owner, so later tables,
  sequences and routines do not silently become runtime-accessible. Grant
  explicit runtime access to the named objects only.
- Object and schema ownership; runtime must own none of them. Use a dedicated
  schema owner/migration identity, not the runtime login, and do not give
  runtime membership (direct or transitive), `ADMIN OPTION`, or a path to
  `SET ROLE` into migration, owner, membership-admin, backup, restore, or
  bootstrap identities.
- Role attributes and escalation paths: runtime is `NOSUPERUSER`,
  `NOCREATEDB`, `NOCREATEROLE`, `NOREPLICATION`, and `NOBYPASSRLS`, and has no
  privilege `GRANT OPTION`, role `ADMIN OPTION`, or privileged
  inherited/SETtable membership. `NOINHERIT` alone is not sufficient if `SET
  ROLE` remains possible.
- The target's PostgreSQL defaults and default ACLs. Explicitly remove
  unnecessary `PUBLIC` schema `CREATE`, database `TEMP`/`CONNECT` and routine
  execute rights where compatible with the target, then grant only the
  named-identity access required. Keep `PUBLIC` built-in catalog access needed
  by ordinary SQL. A migration-owned object must not become runtime-owned or
  runtime-accessible through a default ACL.

This runtime identity is shared by API and optional worker code, so PostgreSQL
grants do not distinguish HTTP callers or enforce team row-level authorization.
The existing application authorization and worker lease/deletion controls must
remain intact. The grant boundary limits database capabilities; it is not a
replacement for those controls or a claim of multi-tenant isolation.

Backup/export and restore are separate interfaces, not “migration with a
different name.” A future export identity should read only the approved
database scope and required metadata, with destination access managed
separately; a future restore identity should write only to an isolated approved
target and restore role ownership/grants through an independently authorized
admin path. Do not grant `pg_read_all_data`, superuser, or blanket database
ownership merely for convenience. Inspect tool-specific requirements before
choosing grants. After restore, compare actual object owners, ACLs, role
membership and sequence access with the approved matrix, rotate/revoke stale
credentials, and keep API/worker mutations stopped until all checks pass.

The existing opt-in
[`automation/backup_restore.py`](../../automation/backup_restore.py) and
[disposable K3s logical restore runbook](../../infra/README.md#disposable-k3s-logical-backuprestore)
remain unchanged. That isolated drill is not a live-application backup service,
credential-custody system, recovery bootstrap path, or evidence that the proposed
24-hour RPO/4-hour RTO is accepted or achieved.

## Bounded deferrals proposed for review

These are proposals, not approved deferrals. If accepted, record the named
owner, date, revisit trigger and blocked work in the applicable ADR and link it
from the roadmap. Candidate owner for each is **dcfaight, pending confirmation**;
do not treat a candidate as an assigned operational team.

| Deferred scope | Residual risk while deferred | Revisit / proposed review bound | Work that remains blocked |
|---|---|---|---|
| Production hosting, all non-Compose placements, production isolation, support/cost model and deployment. | No production support, availability, cost, or isolation assurance; Compose remains one host/volume failure domain. | Before any non-Compose or production-facing access; proposed review by 90 days after acceptance. | Shared/production infrastructure design, promotion and rollout. |
| Numeric RPO/RTO (including the proposed 24-hour/4-hour values) and broad recovery objectives. | Data-loss/recovery time is unbounded; no recovery objective or achieved recovery claim, even for this disposable target. | Before relying on a backup or promising recovery; proposed review by 90 days after acceptance or before dependent G work. | Recovery objective claims, schedules, PITR/HA and objective-based drills. |
| `prod1`/`prod2` continuity, replication, routing, fencing implementation and failover/failback. | No site continuity or peer-site authority is provided. | Before any site-scoped credential, access, replication or traffic change; proposed review by 90 days after acceptance. | D continuity implementation and site-loss validation. |
| OIDC/provider rollout, new membership API/UI and organization-wide account lifecycle tooling. | Current bounded AUTH-01 remains; its operation is not upgraded to enterprise identity assurance. | Before production-facing access expansion or changes to current roles/membership; proposed review by 90 days after acceptance. | Federation, account lifecycle rollout and new membership surfaces. |
| Production-wide network enforcement, secret/certificate automation, backup schedule/retention/off-site custody, PITR, HA and automated failover. | No automated rotation, network hardening, independent backup copy, retention guarantee, PITR, HA or failover is established. | Before expanding the target or depending on those mechanisms; proposed review by 90 days after acceptance. | Those mechanisms and their live checks/drills. |

The 90-day date is a proposed bound, not an accepted review date. On acceptance,
enter the exact calendar date (no later than 90 days afterward) in each affected
ADR. It does not permit deferring the confirmed accountable maintainer,
database/membership/restore authority, credential custody and recovery
bootstrap, revocation responsibility, the slice grant matrix, or post-restore
permission checks.

## Copyable follow-on implementation work order

**Title:** Separate PostgreSQL application runtime and Flyway migration authority
on one disposable local Compose target

**Prerequisite:** Do not start until the applicable A/C, B, E and F dispositions
and D/G reconciliations are explicitly accepted or criteria-compliant
deferrals are recorded under their existing ADR processes; the bootstrap
custody/retrieval path and accountable approver are confirmed; the role matrix
above is approved. This note alone is not authorization.

**Scope:** Add only the database-role boundary, its setup/transition, tests and
operator documentation. No OIDC, HA/PITR, multi-site continuity, production
rollout, new UI, or recovery-mechanism implementation.

### Dependency-ordered work

1. **Role and grant provisioning.** Establish a database/schema owner separate
   from `platform_runtime`; provision a migration login with schema-only
   migration authority, a runtime login with the approved matrix, and separate
   membership/bootstrap identities. Create/revoke roles through the approved
   interactive bootstrap authority, not Flyway or application startup. Set
   database/schema ACLs and default privileges explicitly; preserve migration
   object ownership and the existing `flyway_schema_history`.
2. **Independent Flyway entry point.** Add a Maven Flyway invocation in
   [`backend/pom.xml`](../../backend/pom.xml), using the existing
   `backend/src/main/resources/db/migration/` directory. It must connect with
   migration-only variables and run without starting the application. Do not
   put passwords in Git, command-line arguments, shell history, Maven logs, or
   retained evidence.
3. **Runtime startup.** Disable Flyway during normal application startup in
   [`backend/src/main/resources/application.yaml`](../../backend/src/main/resources/application.yaml)
   and
   [`backend/src/main/resources/application-deploy.yaml`](../../backend/src/main/resources/application-deploy.yaml)
   (including tests/configuration that intentionally enable migration). API
   and optional worker receive only runtime `DB_USERNAME`/`DB_PASSWORD`; neither
   receives migration, bootstrap, membership-admin, backup, or restore secrets.
4. **Clean-database and existing-database paths.** Update
   [`compose.yaml`](../../compose.yaml), [`.env.example`](../../.env.example),
   and [`backend/README.md`](../../backend/README.md#run-locally) for a clean
   disposable setup: initialize PostgreSQL, provision roles interactively,
   migrate independently, then start the application as runtime. For an
   existing database, rehearse on a separate disposable copy; inventory rows,
   schema/history, ownership, ACLs and role memberships; stop API/workers;
   transfer ownership/revoke broad grants without dropping or rewriting
   application data; run migration as the migration identity; then start with
   runtime credentials and verify. Do not edit migration history or run a
   destructive reset against the existing database.
5. **PostgreSQL-backed tests.** Add a focused test such as
   `backend/src/test/java/io/github/dcfaight/platformlab/security/DatabaseRoleSeparationIntegrationTests.java`
   using the existing PostgreSQL 18.6 Testcontainers setup. Exercise both a
   clean database and a fixture with existing rows/history. Run Flyway
   independently as migration identity; run application persistence as runtime.
6. **Positive checks.** Prove the runtime can read/create environments,
   operations, idempotency reservations and audit events; perform required
   operation-event sequence inserts; read teams/memberships; and complete
   lease-fenced worker persistence. Verify the migration identity can apply
   migrations and owns schema objects while runtime startup does not invoke
   Flyway; after migration, verify the migration login is disabled and its
   sessions are terminated.
7. **Negative/effective-privilege checks.** Prove runtime cannot create/alter/
   drop schema objects, change object ownership, create/grant roles, read or
   write `flyway_schema_history`, write/delete teams or memberships, delete
   application rows, alter protected ownership fields, set/inherit a privileged
   role, or escalate through `PUBLIC`, default ACLs, object ownership,
   transitive role membership, `ADMIN OPTION`, or grant options. Inspect
   `pg_roles`, `pg_auth_members`, effective ACLs and `has_*_privilege` results;
   include the actual identity sequence behavior.
8. **Regression and operator handoff.** Run existing authorization/team,
   idempotency, audit, expiry-only cleanup, worker lease/fencing, and safe
   ownership/deletion tests, including
   `TeamAuthorizationIntegrationTests`,
   `PlatformLabApplicationTests`, `WorkerTests`,
   `OperationStatusTests`, and `KindWorkerFlowTests` as applicable. Update the
   local setup and stop/rollback instructions in `backend/README.md`; leave the
   separate K3s/production manifests and backup/restore mechanism unchanged
   unless separately accepted.

### Stop, rollback and acceptance criteria

- Stop API and worker before changing grants or object ownership. Stop the
  transition on unexpected rows, owner/ACL differences, failed migration,
  missing independent bootstrap access, failed positive/negative checks, or
  uncertainty about which account is connected.
- Keep services stopped while diagnosing. Roll back application binaries/config
  only with the restricted runtime identity; never restore the old privileged
  credential to runtime as a shortcut. Resolve partial role changes through
  the approved bootstrap identity. A database restore, if needed, is to an
  isolated disposable target under the separately approved restore authority.
- Accept implementation only when clean and existing disposable database paths
  preserve data/history; migration succeeds independently; startup and API/
  worker persistence use runtime-only credentials with Flyway disabled; all
  positive checks pass; all negative/effective privilege checks deny; existing
  authorization/idempotency/worker/deletion safeguards pass; and documented
  stop/rollback preserves the boundary.
- Retain sanitized target, revision, commands, role/ACL summaries, expected and
  actual results, and approvals. Never retain secret values, hashes, data
  exports, or claim a recovery objective from these checks. Report checks only
  after they are actually run.

## Finite maintainer review action

This PR is a proposed decision package, not the requested user's acceptance.
The next review is one concrete disposition pass, not another general
investigation:

1. **Confirm the accountable owner:** accept dcfaight as accountable
   maintainer/approver for the repository and this target, or name the actual
   person who accepts that responsibility. Confirm who executes role changes,
   who can retrieve bootstrap/recovery access independently of the API, and
   who approves restore permissions.
2. **Disposition the exact fields:** approve or edit the Compose target and
   failure domain; A/C constraints, authority and recovery order; B's in-scope
   runtime flow and excluded environments; D's no-peer-site authority; E's
   credential/membership approval, custody and revocation roles; F's table,
   sequence, function, `PUBLIC`, default-ACL and escalation matrix; and G's
   backup/restore identities, target isolation and post-restore checks. Approve
   each proposed deferral's scope, candidate owner and review bound, or reject
   it. No credential-custody or recovery-authority item may be left as a
   deferral.
3. **Record under the existing process:** update the relevant A/C, then B,
   D/E, F/G ADR decision, owner/reviewer, acceptance-criteria/evidence and
   deferral sections; mark an ADR Accepted or Deferred only when its own
   criteria are met. Link those outcomes from the existing roadmap register
   and check only dispositions actually recorded there. If a criterion cannot
   be satisfied, leave that ADR Proposed and the dependent implementation
   blocked; record the precise unresolved criterion rather than relaxing it.
4. **Only after those records are complete:** review the follow-on work order
   as a separate implementation change. Decision acceptance authorizes neither
   runtime changes in this PR nor production rollout or live recovery claims.

No item above is accepted by this note or by the PR request itself.
