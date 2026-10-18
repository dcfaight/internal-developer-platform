# Operating Model and Delivery Sequence

**Canonical current status and roadmap.** Last reviewed October 17, 2026,
for the post-PR #31 architecture/recovery planning sequence. Update this document when implementation
or validation evidence changes; README and runbooks provide entry points and execution details, not
competing status matrices.

## Operating model

This independent IDP is intended to be built and operated by a platform team
and consumed by developers through approved self-service workflows. The
approximately 3,000 developers are the target population, not measured capacity,
environment count, or concurrency. Dev, QA, and UAT are the lifecycle focus.
The accepted Phase B decision keeps `prod1` and `prod2` as independent
production-continuity sites/clusters; it does not define deployed production
clusters or settle control-plane ownership, management placement, active/active
versus active/passive operation, isolation, recovery objectives, or production
readiness.

## Status vocabulary

- **Implemented:** code or configuration exists. This alone is not validation.
- **Validation recorded:** a dated check passed for the stated target and scope.
- **Evidence pending:** the acceptance run or durable evidence is still needed.
- **Blocked:** an identified dependency currently prevents the acceptance run.
- **Planned/deferred:** not part of the implemented or validated scope.

A merge is not a live validation result, and a successful bounded check is not
production readiness. "Task-local" identifies evidence produced in an
ephemeral task environment; it does not mean the artifact is durably retained.

## Current delivery gates

This is the durable roadmap record after PR #31, not a claim of new validation.
**CAPACITY-01 is the next focused validation gate**, after the GitOps and
restart-persistence gate definitions. The documented validation priorities remain
**GITOPS-01 → PERSIST-01 → CAPACITY-01**; defining those prior gates
does not mean their live validation or retained evidence is complete.
ARCH-01 decision work is tracked separately and may proceed in parallel;
ARCH-01 identifiers refer to decision records, not additional validation gates.
The other open acceptance gates remain recorded here. “Evidence pending” means
validation or durable retention is still required; no recorded blocker does not
mean a target has been verified healthy.

| Gate | Status / open blockers | Capability and implementation | What it proves / validation recorded | Remaining acceptance | References |
|---|---|---|---|---|---|
| **AUTH-01 — Basic/team boundary** | Implemented; validation recorded for bounded scope. | Basic-authenticated `OPERATOR`, team-scoped `DEVELOPER`, and exclusive expiry-guarded `CLEANUP` roles are implemented. Trusted SQL administrators provision teams and memberships; legacy unowned environments remain operator-managed. | Live smoke checks recorded anonymous-read `401`, cross-team create `403` without writes/reservations, owned-team create/replay `202`, and membership-revocation denial. | This bounded gate is implemented and its smoke evidence is recorded. SSO/OIDC, account lifecycle, rate limiting, and stronger tenant isolation are deferred; this is not production identity assurance. | [API and auth contract](api.md); [backend auth setup](../backend/README.md#authentication-and-authorization) |
| **BUDGET-01 — Per-environment guardrails** | Implemented; validation recorded for bounded scope. | ResourceQuota and LimitRange budgets are enforced for provisioned environments. The fixed sample workload is a provisioning fixture, not a representative developer workload. | A targeted disposable kind check recorded LimitRange defaults and rejection of an over-budget Pod. | Budgets are guardrails, not capacity estimates; broader isolation and capacity evidence remain separate gates. | [Budget details and evidence](../infra/README.md#provisioned-environment-resource-budgets) |
| **K3S-01 — K3s platform lifecycle** | Implemented; automated evidence pending. No blocker recorded. | Platform Kustomize package, in-cluster worker, projected credentials, and dedicated RBAC are implemented for a disposable single-node K3s cluster. | Manual create → `READY` → authenticated delete → namespace absence passed October 16, 2026. This was not an automated test run. | Record a successful automated target-specific lifecycle run with retained evidence. Do not infer PVC restart persistence or production readiness from lifecycle success. | [K3s runbook and manual evidence](../infra/README.md#local-k3s-platform); [K3s package](../infra/kubernetes/) |
| **GITOPS-01 — GitOps live validation** | Implemented; evidence pending; blocked by `cdn01.quay.io` DNS/image-download failure. | Bounded Argo CD tooling for frontend-only reconciliation and rollback is implemented; target ownership excludes the namespace, PVC, worker RBAC, and external secrets. | Proves target-cluster Git synchronization and frontend upgrade/revert without disturbing backend or persisted state. October 17, 2026: bootstrap resources applied, but Argo controller startup was blocked by the image-download failure. No successful Git sync, frontend upgrade/revert, or persistence proof is recorded. | Resolve artifact/image availability without weakening TLS or permissions. Retain successful evidence for three distinct Git commits (baseline, frontend upgrade, revert), exact desired/runtime releases, and unchanged backend/database/PVC/secret and environment/operation state. | [GitOps runbook and validation status](../infra/README.md#disposable-k3s-gitops-deployment-and-frontend-rollback) |
| **RESTORE-01 — Logical database round trip** | Implemented; durable evidence pending. No blocker recorded. | Opt-in disposable K3s fixture-based logical backup/restore tooling is implemented; it uses isolated temporary databases, not the live application database or PVC. | October 17, 2026: task-local disposable K3s round trip passed fixture, Flyway, schema, and sequence checks; backup took 0.207 s and restore 0.216 s. The artifacts were only in that task environment. | Retain the dump, checksum, and evidence durably for the intended disposable target, or repeat the drill and archive them. This is not live PVC backup, restart persistence, node-loss recovery, PITR, HA, or production recovery. | [Logical backup/restore runbook and limits](../infra/README.md#disposable-k3s-logical-backuprestore) |
| **CAPACITY-01 — Lifecycle baseline** | Next focused gate; harness implemented; live validation and retained evidence pending. No live-run blocker recorded. | PR #26 implemented `capacity_baseline.py`, an opt-in harness for 10 fixed `sample-service` lifecycles with at most 2 concurrent workflows, including cleanup, plus offline tests and optional resource samples. | Must measure bounded lifecycle timing, failure, and cleanup behavior on the selected disposable target. Harness/offline tests are implementation evidence; no successful live capacity-baseline run is recorded. Per-environment quotas are guardrails, not capacity estimates. | Close only with one successful 10/10 run, exit zero, complete operation-metrics evidence, and no unresolved leftovers, archived outside the task environment. Retain the manifest, summary, invocation/exit record, target/revision/configuration, cache condition, worker settings, timings, failures, cleanup outcomes, and explicit measurement availability/limitations under the linked evidence contract; record date, target, revision, outcome, and sanitized durable reference here. Optional resource samples may be unavailable; that prevents resource-capacity conclusions, not lifecycle-gate closure. This is not support for 3,000 developers, saturation, or autoscaling evidence. | [Capacity gate and evidence contract](../automation/README.md#validation-gate); [capacity runbook](../automation/README.md#opt-in-lifecycle-capacity-baseline); [resource budgets](../infra/README.md#provisioned-environment-resource-budgets) |
| **PERSIST-01 — Restart persistence on K3s** | Gate/runbook defined; live validation and retained evidence pending. No live-run blocker recorded. | Opt-in manual drill gracefully replaces PostgreSQL and then backend pods on a dedicated disposable single-node K3s target, retaining the same bound PVC/PV and fixed images/configuration. | Must prove known environment/operation IDs, terminal history/events, and accepted replay/conflict behavior survive both restarts, with database readiness recovered and no manual database repair. No passing K3s restart result is recorded; prior lifecycle, GitOps, and logical-restore checks do not close this gate. | Retain before/after API and storage/pod observations, restart commands/timings, original requests/keys and replay comparisons, post-restart create → `READY` → delete → namespace absence, and a no-repair/no-PVC-loss record. Close only with a passing run archived outside the task environment and a dated target/revision/outcome/evidence reference here. No PVC/namespace deletion, backup recovery, HA, node loss, failover, or PITR is tested. | [Restart-persistence drill and evidence contract](../infra/README.md#disposable-k3s-restart-persistence) |
| **CLEANUP-01 — Expiration cleanup** | Implemented; acceptance reconciliation pending. Historical limits remain in the linked evidence. | A manual API-driven cleanup CLI is implemented; it defaults to dry-run and never deletes namespaces directly. Scheduling and remote-TLS transport are deferred. | The cleanup README preserves the original change's hosted-CI, review/security, kind, and browser verification limits. Those dated results are historical and have not been reconciled here as new failures. | Reconcile the recorded gates against current hosted CI/review and produce a successful disposable kind/browser cleanup walkthrough before treating that acceptance as closed. Do not weaken safeguards to make a drill pass. | [Cleanup usage and original evidence](../automation/README.md#evidence-and-remaining-gates-for-the-original-cleanup-change); [demo](demo.md#5-manual-expiration-cleanup) |
| **ARCH-01 — Architecture and recovery decisions** | Planned/deferred; ownership, isolation, and recovery objectives remain unresolved. | Phase B records the intended topology, not deployed production sites. Hosting/control-plane ownership, management placement/isolation, recovery objectives, `prod1`/`prod2` implementation, identity/account-management hardening, and deeper security/recovery work remain open. | Establishes agreed design boundaries and measurable acceptance criteria, not production readiness. No broader recovery/security validation is claimed. | Record decisions/ADRs with acceptance criteria and implementation dependencies; explicitly resolve or defer SSO/OIDC, network policy, secret rotation, database role separation, retention, PITR, HA, and failover. | [Phase B decision](decisions/phase-b-target-architecture.md); [open decisions and deferred scope](#open-decisions-and-deferred-scope); [ARCH-01A (Proposed)](decisions/ARCH-01A-hosting-control-plane-ownership.md); [ARCH-01C (Proposed)](decisions/ARCH-01C-recovery-objectives-restore-order.md) |

## Proposed next sequence

This is a proposal for bounded disposable-target work, not authorization for
production changes:

1. Select a healthy disposable target, verify image/artifact availability, and
   choose an operator-managed durable evidence location. Do not weaken TLS,
   access controls, or ownership checks.
2. Complete the preceding **GITOPS-01** live-evidence gate,
   using the [live drill and evidence handoff](../infra/README.md#git-driven-frontend-upgrade-and-rollback-drill):
   - Resolve the recorded `cdn01.quay.io` DNS/image-download blocker and verify
     controller startup and exact artifact availability on the disposable K3s
     target. The prior blocked attempt and offline tests are not successful
     live-validation evidence.
   - Retain one successful run showing three distinct synchronized Git commits:
     baseline → frontend upgrade → new revert commit. Each phase must be
     `Synced`, `Healthy`, and operation `Succeeded`, with the expected desired
     and runtime frontend digests and served release identity.
   - Require unchanged backend/PostgreSQL specs, Deployment/pod UIDs and restart
     counts, PVC/Secret UIDs, Flyway history, and authenticated environment and
     operation history, with backend database readiness `UP`.
   - Close the gate only after the passing evidence JSON, test log, release
     catalog and commit references are retained in operator-managed storage
     outside the task environment, alongside the rollback artifacts. Record
     target, date, checked-out revision, outcome and sanitized durable evidence
     reference in this roadmap. Until then, the gate remains open.
3. Run the preceding **PERSIST-01** evidence gate using
   the [opt-in same-PVC restart drill](../infra/README.md#disposable-k3s-restart-persistence).
   Retain known ready/deleted fixtures and complete operation histories across
   graceful PostgreSQL and backend pod replacement; verify accepted replay and
   conflict behavior, database readiness, and subsequent create/ready/delete with
   namespace absence. Require unchanged PVC/PV identities and no manual database
   repair. The gate remains pending until passing evidence is durably archived
   and linked here; this is not backup recovery, HA, failover, node loss, or PITR.
4. Execute **CAPACITY-01**, the next focused validation gate, using the
   [opt-in capacity runbook and evidence contract](../automation/README.md#opt-in-lifecycle-capacity-baseline).
   Retain one successful run of 10 existing `sample-service` environment
   lifecycles with at most 2 concurrent workflows including cleanup on a named
   disposable target. Archive the manifest, summary, invocation/exit record,
   configuration/cache/worker settings, timings, failures, cleanup outcomes,
   and measurement availability outside the task environment; link the dated
   target/revision/outcome and durable evidence reference here. Harness tests
   alone do not close this gate, and this is not production capacity or
   autoscaling proof.

Separately, refine **ARCH-01** through the [open decision register and proposed
decision sequence](#open-decisions-and-deferred-scope) in parallel with validation
work, before production-facing expansion. Decision preparation does not close
those gates or authorize implementation.

This is a priority order, not a hard dependency on GitOps. Automated K3s
lifecycle and retained logical-restore evidence can be completed independently,
as can persistence and capacity checks on a healthy target; inspect and resolve
leftovers first. Reconcile the historical cleanup/demo evidence against current
CI and review results separately. Documentation consolidation is complete;
maintaining this roadmap remains ongoing.

GITOPS-01 is opt-in and limited to a dedicated disposable single-node K3s
target. It does not close restart persistence, backup recovery, or production
continuity/readiness gates.

## Open decisions and deferred scope

### ARCH-01 scope and prior work

The roadmap consolidation in [PR #28](https://github.com/dcfaight/developer-platform-lab/pull/28)
and the validation-gate definitions/evidence contracts in
[PR #29](https://github.com/dcfaight/developer-platform-lab/pull/29) (GitOps),
[PR #30](https://github.com/dcfaight/developer-platform-lab/pull/30) (restart persistence),
and [PR #31](https://github.com/dcfaight/developer-platform-lab/pull/31) (capacity baseline)
are complete as merged documentation work. Their live-validation and durable
evidence obligations remain exactly as recorded in the gate table above.
[PR #32](https://github.com/dcfaight/developer-platform-lab/pull/32) framed the
remaining open decisions and proposed their review order. The checklist and
record template below make that framing actionable; they do not claim that
those validation gates passed.

**All decisions below are open.** The accepted
[Phase B decision](decisions/phase-b-target-architecture.md) remains unchanged:
it records intended population and continuity topology, not the architecture
choices below. These are decision prompts, not selected designs, implementation
commitments, or production-readiness criteria already met.
The separate [ARCH-01 recommendation note](decisions/ARCH-01-recommendations.md)
offers a proposed A–G direction for review; it does not change any ADR status.

### Open decision register

The identifiers group future decision records under ARCH-01; they are not
additional validation gates. The last column describes follow-on sequencing,
not work authorized by this refinement.

| Decision needed (all open) | Questions to resolve in a decision record | Implementation sequencing after an explicit decision |
|---|---|---|
| [**ARCH-01A — Hosting, control-plane ownership, and isolation (Proposed)**](decisions/ARCH-01A-hosting-control-plane-ownership.md) | Managed or self-operated hosting/control planes? Who owns upgrades, patching, incidents, access, and failure-domain isolation? What availability, cost, and operating constraints drive the choice? | Establish ownership and hosting boundaries before choosing cluster layouts or provisioning shared infrastructure. |
| [**ARCH-01B — Management placement and environment boundaries (Proposed)**](decisions/ARCH-01B-management-placement-environment-boundaries.md) | Where do the portal/API, workers, GitOps, identity dependencies, and platform database run? How are management, dev/QA/UAT, and production separated? Which credentials and dependencies cross boundaries, and can management survive a workload-site outage? | Use A and C to define trust/failure boundaries before designing cross-cluster access, promotion paths, or worker permissions. |
| [**ARCH-01C — Recovery objectives and restore order (Proposed)**](decisions/ARCH-01C-recovery-objectives-restore-order.md) | What RPO (acceptable data loss) and RTO (acceptable recovery time) apply to platform state and workload data for pod, node, cluster, and site loss? Who declares recovery, and in what order must identity, secrets, storage/database, management, and workloads return? | Agree objectives and a dependency/recovery order before selecting backup, replication, or failover mechanisms. Define required recovery evidence separately from the existing bounded drills. |
| [**ARCH-01D — prod1/prod2 continuity model (Proposed)**](decisions/ARCH-01D-prod1-proxy-continuity-model.md) | How will intended site independence be achieved? Active/passive or active/active? What data ownership/replication, traffic switching, fencing/split-brain protection, planned switchover, failover, and failback model fits the objectives? | Depends on A–C; implement only in separately scoped work with explicit safety, rollback, and site-loss acceptance criteria. The continuity-pair intent does not select a mechanism. |
| [**ARCH-01E — SSO/OIDC and account management (Proposed)**](decisions/ARCH-01E-identity-account-management.md) | Which identity provider and trust model? How do claims map to operator/developer/cleanup permissions and team membership? Who owns provisioning, revocation, service accounts, break-glass access, and audit? | Use B/C trust and recovery boundaries; decide identity/account lifecycle before production-facing access expansion. Preserve current role/team safeguards until a separately reviewed migration is validated. |
| [**ARCH-01F — Network and credential/data-access hardening (Proposed)**](decisions/ARCH-01F-network-credential-data-access-hardening.md) | What network-policy enforcement and permitted flows fit the boundaries? How will worker blast radius, secret encryption/rotation, certificate renewal, and separate runtime/migration/backup database roles be handled and audited? | Depends on B/E and recovery dependencies in C; specify least-privilege policies and rotation/revocation tests before rollout, including recovery access without bypassing controls. |
| [**ARCH-01G — Retention, PITR, HA, and failover (Proposed)**](decisions/ARCH-01G-retention-pitr-ha-failover.md) | What backup scope, schedule, retention/deletion policy, off-site protection, and restore verification are required? Is point-in-time recovery (PITR) or high availability (HA) needed, and for which components? How do database recovery, replication, and failover meet C/D without treating replicas as backups? | Use C/D objectives and F access/secret requirements; decide mechanisms before implementing scheduled backups, PITR, HA, or automated failover. Require separate restore/site-loss drills and retained evidence. |

### Architecture/recovery decision checklist

Use this checklist during ARCH-01 review, alongside the questions in the
register. Check an item only when a reviewed decision record is linked from the
corresponding register entry (or from the sequencing item below). An explicit
deferral must name an owner, revisit condition, and work that remains blocked;
a checked item means the disposition is recorded, not that it is implemented
or validated. All items start open.

- [ ] **Hosting / control plane / isolation (A/B):** record hosting and
  control-plane ownership, management-component operators, and required
  management/workload isolation.
- [ ] **Environment boundaries (B/D):** define disposable-target versus
  longer-lived placement, dev/QA/UAT lifecycle and promotion/retirement
  boundaries, and the detailed `prod1`/`prod2` separation. Preserve the accepted
  continuity-pair intent without assuming a replication or failover mechanism.
- [ ] **Recovery objectives (C):** identify required recovery outcomes and
  prioritized failure modes, RPO/RTO by state/component, restore order, and
  measurable success criteria with retained evidence requirements.
- [ ] **Implementation sequencing (A–G):** link the reviewed dependency plan;
  distinguish decision-needed, deferred/blocked, and implementation-ready work.
  Identify which accepted records unblock each proposed implementation PR.
- [ ] **Identity / access hardening (E):** record SSO/OIDC, account provisioning
  and revocation, approval flow, role separation, service-account ownership,
  break-glass access, and audit expectations.
- [ ] **Data / platform hardening (F/G, informed by C/D):** record network
  policy, secret rotation, database role separation, retention, PITR, and
  HA/failover boundaries, including explicit deferrals and recovery dependencies.

### Decision record template

For each bounded decision, copy this template into a descriptively named
Markdown file in `docs/decisions/`, consistent with
`phase-b-target-architecture.md`. Replace placeholders, keep the initial status
**Proposed**, and link the proposal from the register. Acceptance requires
explicit review; neither this template nor a suggested option selects a design.
Keep current gate status in the table above and link evidence rather than
copying runbook histories into decision records.

```markdown
# ARCH-01<letter>: <Decision title>

**Status:** Proposed (Accepted / Deferred / Superseded after review)
**Date:** <YYYY-MM-DD>
**Owner / reviewers:** <Accountable owner and required reviewers>
**Roadmap reference:** <ARCH-01 register entry; related decision links>

## Context

<Decision needed, constraints, assumptions, affected environments/components,
failure scenarios, and what remains outside scope.>

## Options considered

- <Option, benefits, trade-offs, risks, and operational/cost implications>
- <Alternative, including retaining the current approach or deferring>

## Decision

<Pending review. After review, record the selected option or explicit deferral.>

## Rationale

<Why the disposition fits the constraints; why alternatives were not selected.>

## Dependencies

<Required decisions, identity/secret/data recovery order, evidence prerequisites,
and implementation work blocked until these are satisfied.>

## Consequences

<Positive and negative effects, remaining risks, and rollback/safety boundaries.>

## Acceptance criteria and evidence

<Measurable outcomes, target/failure scope, required checks, and sanitized durable
evidence references. Distinguish planned checks from recorded results.>

## Follow-up work

- [ ] <Scoped implementation/validation issue or PR, owner, and prerequisites>
- [ ] <Deferred question, owner, revisit condition, and work that remains blocked>
```

### Proposed decision and implementation sequence

This is a proposed review order, not a selection of solutions or a requirement
to finish live gates before discussing architecture:

1. Start with **A and C together**: identify operating ownership, constraints,
   failure scenarios, and candidate recovery objectives. Revisit hosting if
   objectives cannot be supported; do not invent RPO/RTO targets from the
   disposable logical-restore timings.
2. Use those constraints to frame **B**, then **D and E**, then **F and G**.
   Iterate when identity recovery, database dependencies, site independence,
   or cost exposes a conflict. No option becomes accepted by appearing here.
3. For each bounded decision, propose a record in `docs/decisions/` using the
   [decision record template](#decision-record-template), following
   the existing Phase B pattern (`Status`, decision, assumptions/scope, meaning,
   and explicit non-claims). Mark it **Proposed** until explicitly accepted;
   include alternatives/trade-offs, dependencies, accountable owner/reviewer,
   measurable acceptance criteria, and unresolved or explicitly deferred items
   with revisit conditions. Link accepted/deferred outcomes from this register
   while keeping the gate table as the sole current-status record.
4. Only after the relevant decisions are accepted, propose separate,
   narrowly scoped implementation and validation PRs in dependency order.
   Define target, safety/rollback boundaries, expected measurements, and durable
   evidence requirements for each. Decision acceptance is not implementation,
   successful recovery, or authorization for production deployment.

### Candidate DB-role slice: blocking versus can-wait checklist

**Scope, not authorization:** separate PostgreSQL application runtime identity/grants
from migration/schema-owner identity on a disposable target only. All seven
ARCH-01 ADRs remain **Proposed**. Merged documentation is not decision acceptance,
implemented controls, passed validation, or production authorization. This
checklist supports review under the canonical register above; it changes no
status or ADR acceptance criteria.

Use the [consolidated DB-role disposition and implementation work order](decisions/db-role-separation-prerequisite-review.md)
as the single proposed review package. Its completion is preparation only: no
ADR disposition is accepted there and every readiness item below remains open.

Classifications below are relative to this candidate slice, not universal
priorities. **Blocking decision** requires relevant explicit acceptance before
separate implementation/validation PRs. **Dependency review / bounded disposition
needed** means reviewers must resolve the applicable constraints, not ignore the
dependency. **Mechanism can wait** applies only where the ADR permits deferral,
with blocked follow-ons preserved. B/E/F cannot be classified wholesale as
deferrable. A deferral alone does not satisfy a dependency requiring acceptance;
any narrower accepted scope requires explicit review against the ADR criteria,
not an exception invented by this checklist.

| ADR | Must be decided/dispositioned before the slice | What can wait, subject to permitted explicit deferral | Work still blocked / revisit trigger |
|---|---|---|---|
| [A — Hosting/ownership](decisions/ARCH-01A-hosting-control-plane-ownership.md) | **Blocking decision, jointly with C:** accountable ownership, hosting/control-plane boundaries, management/workload isolation, administrative authority, and operating/failure-domain constraints relevant to the target. | Detailed production hosting products/provisioning; no waiver of ownership or isolation requirements. | Ownership/isolation-dependent shared infrastructure and production expansion remain blocked; revisit with C if recovery constraints conflict. |
| [B — Placement/boundaries](decisions/ARCH-01B-management-placement-environment-boundaries.md) | **Blocking decision:** management, database, and workload lifecycle/trust boundaries; owned API/worker, migration, administrative, and recovery flows/credentials, reconciled with A/C. | Production placement implementation and promotion tooling, not the boundary/ownership disposition required by F. | Boundary-dependent access and role rollout remain blocked pending relevant acceptance; revisit when placement, lifecycle, or cross-boundary flows change. |
| [C — Recovery](decisions/ARCH-01C-recovery-objectives-restore-order.md) | **Blocking decision, jointly with A:** platform-data ownership, relevant objectives or permitted owned/time-bounded deferrals, restore order, recovery authority, and credential/key/bootstrap access without circular dependencies. | Detailed backup/PITR/HA mechanisms belong to D/G; no numeric objectives are inferred here. | Objective/order-dependent recovery choices remain blocked; revisit at deferral expiry or when identity, key, data, or failure scope changes. |
| [D — Site continuity](decisions/ARCH-01D-prod1-proxy-continuity-model.md) | **Dependency review / bounded disposition needed:** reconcile F's site-scoped authority and C/E recovery access with D; record applicable constraints or permitted deferrals without selecting a continuity model. | Site provisioning, replication, routing, fencing implementation, and failover/failback mechanisms. | Dependent continuity rollout remains blocked by A–C/E/F/G prerequisites; revisit before site-scoped access, replication, or promotion work. |
| [E — Identity/accounts](decisions/ARCH-01E-identity-account-management.md) | **Blocking decision for F's prerequisite:** machine/DB credential ownership and authority, membership-administration approval/execution, recovery/break-glass access, and revocation responsibilities within A/B/C boundaries; review D assumptions. | Actual human OIDC integration/provider rollout may wait under E's constraints; not service-identity lifecycle, authorization ownership, or recovery authority. | Dependent credential/data hardening and production access expansion remain blocked pending relevant acceptance; revisit before federation, entitlement, or credential-lifecycle changes. |
| [F — Credential/data access](decisions/ARCH-01F-network-credential-data-access-hardening.md) | **Blocking decision:** approved runtime/migration/backup/restore grant and responsibility matrix; separate runtime from schema ownership, separately approved/time-bounded migration privilege, backup/restore interfaces, and post-restore grant/revocation authority. Requires A/B/E/C dispositions, D reconciliation, and C/E/G bootstrap/key constraints. | Broader network enforcement tooling, worker controls, and secret/certificate automation only with valid disposition; deferring grants leaves privileged-role migration blocked. | Role migration and dependent live hardening checks remain blocked until relevant acceptance and separately reviewed implementation/validation work; revisit for new flows, grants, or recovery consumers. |
| [G — Data protection](decisions/ARCH-01G-retention-pitr-ha-failover.md) | **Dependency review / bounded disposition needed:** data scope/ownership, backup/export/restore authority, independently recoverable identities/keys, restore-target isolation, grant reconciliation and stale-credential revocation with C/E/F; review D dependencies. | Scheduled backups, retention automation, PITR, HA, and failover mechanisms only under G's permitted owned deferrals; no topology or protection capability is assumed. | Dependent protection/recovery rollout and drills remain blocked; revisit C/D/F acceptance and bootstrap/key handoffs before dependent G acceptance or mechanism work. |

Readiness remains unchecked:

- [ ] Link reviewed, explicitly accepted decisions for the relevant scope from
  the register, with dependencies reconciled in the canonical sequence.
- [ ] Record each permitted explicit deferral with accountable owner, revisit
  condition, expiry where required, residual risk, and work still blocked;
  do not substitute deferral for required acceptance.
- [ ] Approve the runtime/migration/backup/restore responsibility and grant
  matrix, including authorization-membership administration, required runtime
  reads, credential custody/revocation, and post-restore verification.
- [ ] Clarify whether migrations still run on application startup with privileged
  credentials or through an independently executed migration path; review how
  runtime is kept from migration credentials and schema-owner authority.
- [ ] Review a separate disposable-target implementation/validation proposal:
  positive/negative tests, safety/stop and rollback boundaries preserving access
  restrictions, and sanitized durable evidence (target, revision, configuration,
  commands, expected/actual results, approvals; no secrets).

**Future tests, planned—not run here:** migrations succeed with the migration
identity; runtime API/worker persistence, idempotency, and audit paths still work.
Runtime cannot mutate schema, administer roles, or write membership-administration
data, but retains required membership reads. Check effective privileges, role
membership, and ownership for unintended escalation. Preserve existing
authorization/team boundaries, expiry-only cleanup, worker fencing, and
ownership/deletion guards. Account for backup/restore privilege interfaces
without claiming a recovery mechanism has been implemented or validated.

**Finite next action:** the maintainer completes the approval sequence in the
[consolidated DB-role review package](decisions/db-role-separation-prerequisite-review.md#finite-maintainer-review-action):
confirm the proposed accountable maintainer and actual credential/recovery
authority, review A/C jointly and then B → D/E → F/G against their existing
criteria, and record accepted or deferred dispositions in those ADRs and this
roadmap. This is not another general investigation or a code rollout. If an
applicable criterion or required authority remains unmet, leave that ADR
Proposed and the implementation blocked. Independently, GITOPS-01, PERSIST-01,
CAPACITY-01, and RESTORE-01 may proceed under their existing runbooks and safety
boundaries. Passing those gates is not ARCH-01 acceptance; their status and
historical evidence remain unchanged.

### ARCH-01A + ARCH-01C joint review checklist

Review [ARCH-01A](decisions/ARCH-01A-hosting-control-plane-ownership.md) and
[ARCH-01C](decisions/ARCH-01C-recovery-objectives-restore-order.md) jointly and
iteratively, not as independent serial approvals. Both remain **Proposed**;
all readiness actions below remain unchecked.

#### Settled context

- Phase B records approximately 3,000 developers as the target population,
  dev/QA/UAT as the lifecycle focus, and `prod1`/`prod2` as intended separate
  continuity sites/clusters—not measured capacity or deployed production.
- Architecture decisions remain separate from GITOPS-01, PERSIST-01,
  CAPACITY-01, and RESTORE-01 validation. Merge is not validation; bounded
  evidence is not production readiness or an accepted architecture.

#### Review actions

##### A — Ownership, boundaries, and constraints

- [ ] Record named accountable owners and a responsibility matrix for hosting
  and control planes: operations, upgrades, patching, monitoring, incidents,
  privileged access, and recovery.
- [ ] Define hosting/control-plane boundaries, provider/operator dependencies,
  failure domains, and management/workload isolation across administrative
  identity, credentials, network paths, and blast radius.
- [ ] Compare managed, self-operated, and split/hybrid options and permitted
  deferral, recording trade-offs, risks, mitigations, and dependent work.
- [ ] Record approved availability, operating, and cost constraints, with units,
  measurement/estimation methods, and accountable approvers.

##### C — Objectives, authority, and evidence

- [ ] Inventory in-scope platform/workload services and state, including
  identity, keys, secrets, databases, and storage; name owners and loss impacts.
- [ ] Review a service/state-by-pod/node/cluster/site-loss matrix with approved
  RPO/RTO values and units, or permitted owned, time-bounded deferrals for each
  applicable entry; do not infer targets from disposable drill timings.
- [ ] Define declaration and verification authority, dependency order, and safe
  parallel recovery across foundation/access, identity, secrets/keys, storage/
  databases, management, and workloads; resolve break-glass bootstrap cycles.
- [ ] Define each objective's start/end events, measurement sources, integrity
  checks, pass/fail rules, and sanitized durable evidence to retain.

##### Joint feasibility and explicit deferrals

- [ ] Reconcile ownership, isolation, failure domains, cost, recovery objectives,
  and bootstrap dependencies together; iterate A/C when constraints conflict
  and record reviewed dispositions before dependent proposals.
- [ ] For each permitted deferral, record the deferred item, named owner,
  required expiry/review date, revisit condition, residual risk, blocked work,
  and narrowly bounded allowed interim work. Deferral does not substitute for
  required acceptance.

Next: use the reviewed A/C constraints for **B → D/E → F/G**, revisiting
conflicts jointly. This checklist authorizes no implementation, live drill,
gate-status change, or production deployment.

### Outside the current validation gates

GITOPS-01 tests bounded frontend reconciliation/revert, PERSIST-01 tests graceful
same-PVC pod restarts, and CAPACITY-01 tests a small fixed lifecycle baseline.
RESTORE-01 is a fixture-based logical round trip. None decides ownership,
isolation, identity assurance, RPO/RTO, retention, PITR, HA, or site failover;
passing them cannot close ARCH-01 or establish production readiness.
Existing runbooks and historical attempts remain in
[infra/README.md](../infra/README.md); the harness and measurement caveats remain
in [automation/README.md](../automation/README.md). The
[pre-Kubernetes assessment](pre-kubernetes.md) remains archived context, not a
new backlog or evidence of current gaps.

The following broader scope remains deferred; the register frames decisions
without silently promoting these items to implementation:

- **Phase B:** target population and `prod1`/`prod2` continuity topology are
  recorded; hosting/control-plane ownership, management placement/isolation,
  service objectives, and production site design remain unresolved.
- **Phase C:** local choices exist (including Argo CD, K3s, and PostgreSQL), but
  overall tool selection against the unresolved requirements is incomplete.
  Accepted decisions and local tool choices do not imply that the topology is
  deployed in runtime.
- **Deferred:** SSO/OIDC and account lifecycle; rate limits; network isolation
  and worker-credential blast-radius reduction; database role separation,
  secret encryption/rotation, and certificate renewal; scheduled backups,
  retention, PITR and node-loss objectives; representative workload profiles
  and scaling; dev/QA/UAT promotion and independent `prod1`/`prod2` switchover.
  Optional service catalog, templates, and AI ideas are candidates, not
  acceptance gates.

## Maintainer status-update checklist

When a relevant PR merges or a live run completes:

1. Read this roadmap and its linked evidence/runbooks before asking for status
   already available in the repository.
2. Update implementation and validation separately; a merge does not close an
   evidence gate.
3. Record target, date, revision, command/check, outcome, sanitized durable
   artifact reference, blocker, and next action. Say explicitly when a check is
   unknown, skipped, task-local, or not run.
4. Preserve dated historical results; clarify their scope and link back here
   rather than silently rewriting old outcomes.
5. Keep this table as the status record; README is the entry point, infra and
   automation are runbooks/evidence, and the pre-Kubernetes baseline is archival.
   Update links rather than copying a second roadmap into those documents.
