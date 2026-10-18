# PostgreSQL Role Separation: Prerequisite Review

**Record type:** Proposed dispositions for review; not accepted decisions
**Scope:** Runtime/migration credential separation on a disposable target only

## Purpose and basis

Supply recommended answers for the bounded review before the next DB-role
implementation slice. This supplements the roadmap's
[blocking versus can-wait checklist](../operating-model-and-sequence.md#candidate-db-role-slice-blocking-versus-can-wait-checklist)
and applies the [ARCH-01 recommendations](ARCH-01-recommendations.md); it does
not replace either document or repeat the A–G architecture review.

This is a prerequisite review document, **not an implementation PR or rollout
authorization**. All ARCH-01 ADR statuses, acceptance criteria, validation gates,
and recorded evidence remain unchanged. Recommendations below are not accepted
decisions, implemented controls, or passing test results.

## Settled context and open review

The documented foundation remains a platform-team-operated IDP with guarded
developer self-service, React/TypeScript, Java/Spring Boot, PostgreSQL,
Kubernetes, and Python. AUTH-01 already separates operator, team-scoped developer,
and expiry-only cleanup responsibilities; trusted SQL administrators provision
teams and memberships, with no membership-management API. Current Flyway
migrations run on application startup; changing that is future implementation.

Still open: relevant explicit decision acceptance, named accountable owners and
approvers, the exact grant/credential lifecycle matrix, recovery access, and
permitted deferrals. Proposed role assignments below do not resolve named
ownership. The roadmap remains the sole current-status and sequencing record.

## Recommended dispositions — pending review

| Concern | Recommended disposition | Defer | Future work / revisit |
|---|---|---|---|
| A — Ownership and target | Platform team owns and operates the existing disposable demonstration target and its DB-role change; review administrative authority and the single failure domain jointly with C. | Production hosting/provider selection, not accountable ownership or isolation constraints. | Confirm named operations, access, incident, and recovery owners in the A/C review; revisit if target or failure scope changes. |
| B — Boundaries and flows | Keep API/worker database access within the protected management boundary, separate from developer workloads. Distinguish runtime, migration, membership administration, and recovery credentials; namespaces alone are not strong isolation. | Production placement and promotion mechanisms, not ownership of these flows. | Review the target-specific flow/credential inventory against A/C before dependent access changes. |
| C — Recovery dependencies | Carry forward the recommendation note's bounded database-recovery target and dependency order as proposals, not accepted or measured objectives. Require independently available recovery access/keys, database verification, then management/workload reconciliation. | Broader workload/site objectives and mechanisms only through permitted owned, time-bounded deferrals. | Confirm recovery declaration/verification authority and bootstrap access; revisit for changed identity/key/data dependencies. |
| D — Continuity constraints | Limit credentials and grants to this disposable target; do not infer peer-site authority or select a continuity model. Preserve the accepted `prod1`/`prod2` intent. | Replication, routing, site provisioning, fencing implementation, and failover/failback. | Reconcile C/E/F recovery authority with D; dependent continuity work remains blocked. |
| E — Identity and membership administration | Preserve AUTH-01. Recommend database/data-owner approval of grants and membership changes, trusted SQL administrators executing approved membership changes, and security review of privileged exceptions. Runtime reads membership but cannot change teams, memberships, or its own authority. | Human OIDC/provider integration and new membership UI/API, not credential ownership, revocation, approval, or recovery authority. | Confirm named approvers/executors and independent review, or an owned expiring exception under E; revisit for entitlement or credential-lifecycle changes. |
| F — Runtime and migration authority | Invoke Flyway independently with separate migration/schema-owner credentials and separately approved, time-bounded privilege. Start API/workers with runtime credentials only and startup Flyway disabled; runtime must neither possess migration credentials nor inherit/elevate into privileged roles. | Broader network, secret/certificate automation, and worker hardening only through permitted dispositions; the grant matrix cannot be deferred to unblock migration. | Approve exact grants, custody, issuance/revocation bounds, and stop/rollback plan. Runtime gets required application-data operations and authorization reads, not schema ownership, role administration, or membership writes. |
| G — Backup/restore interfaces | Keep backup/export and restore/admin authority separate from runtime and routine migration. Database/data owners approve scope; operations execute approved recovery, with grant verification and stale-credential revocation before mutating workers resume. Recovery credentials/keys must be independently available and restore targets isolated. | Scheduled backup/retention automation, PITR, HA, and failover under G's permitted deferrals. | Review C/E/F bootstrap/key and post-restore handoffs with D/G; dependent recovery rollout and drills remain blocked. |

Every permitted deferral needs a named owner, revisit condition, required
expiry/review date, residual risk, and blocked follow-on work. None is approved
by this table. A deferral cannot replace required acceptance; any narrower
accepted scope must be explicitly reviewed against the existing ADR criteria.

## Prerequisites for the implementation PR — all unchecked

- [ ] Link relevant reviewed, explicitly accepted decisions from the roadmap,
  reconciling A/C jointly, then B → D/E → F/G. Confirm owners/approvers and
  permitted deferrals; do not mark readiness from merged recommendations alone.
- [ ] Approve a grant/responsibility matrix covering runtime, migration,
  membership administration, backup, and restore, including credential custody,
  lifecycle/revocation bounds, recovery access, and post-restore verification.
  Migration credentials are separate from runtime credentials and unavailable
  to application startup and API/worker processes.
- [ ] Specify runtime's required environment, operation, idempotency, and audit
  reads/writes, sequence access, and team/membership reads. Deny schema mutation
  and ownership, role/grant administration, and team/membership mutations.
  Privileged membership administration remains a separate trusted SQL action,
  not runtime authority or a new API.
- [ ] Require future tests proving migrations succeed independently and runtime
  startup, API/worker persistence, required reads/writes, and authorization
  membership reads still work. Require denied schema/role/team/membership
  mutations and escalation through effective grants, ownership, role inheritance,
  or role switching. Preserve authorization, idempotency, expiry-only cleanup,
  worker fencing, and ownership/deletion safeguards.
- [ ] Review the separate disposable-target implementation/validation plan,
  local setup, and safe stop/rollback instructions preserving access restrictions.
  Define sanitized evidence with target, revision, configuration, commands,
  approvals, and expected/actual results; never retain credentials.

## Sequence after review

First record the required reviewed dispositions and explicit acceptance under
the existing ADR process; this note does not supply acceptance. Once those
prerequisites are met, the next PR is the narrow runtime/migration credential
and grant separation slice, with its tests and local setup/rollback documentation.
No runtime, infrastructure, secret, or test changes and no live checks occur
here. Production rollout, OIDC, broader isolation, PITR, HA, and multi-site
continuity remain outside that slice. Existing validation gates proceed
independently under their current runbooks and do not confer ARCH-01 acceptance.
