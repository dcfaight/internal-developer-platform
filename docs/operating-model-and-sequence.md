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
| **AUTH-01 — Basic/team boundary** | Implemented; validation recorded for bounded scope. | Basic-authenticated `OPERATOR`, team-scoped `DEVELOPER`, and exclusive expiry-guarded `CLEANUP` roles [...] |
| **BUDGET-01 — Per-environment guardrails** | Implemented; validation recorded for bounded scope. | ResourceQuota and LimitRange budgets are enforced for provisioned environments. The fixed sam[...] |
| **K3S-01 — K3s platform lifecycle** | Implemented; automated evidence pending. No blocker recorded. | Platform Kustomize package, in-cluster worker, projected credentials, and dedicated RBAC a[...] |
| **GITOPS-01 — GitOps live validation** | Implemented; evidence pending; blocked by `cdn01.quay.io` DNS/image-download failure. | Bounded Argo CD tooling for frontend-only reconciliation and ro[...] |
| **RESTORE-01 — Logical database round trip** | Implemented; durable evidence pending. No blocker recorded. | Opt-in disposable K3s fixture-based logical backup/restore tooling is implemented; [...] |
| **CAPACITY-01 — Lifecycle baseline** | Next focused gate; harness implemented; live validation and retained evidence pending. No live-run blocker recorded. | PR #26 implemented `capacity_basel[...]` |
| **PERSIST-01 — Restart persistence on K3s** | Gate/runbook defined; live validation and retained evidence pending. No live-run blocker recorded. | Opt-in manual drill gracefully replaces Postg[...] |
| **CLEANUP-01 — Expiration cleanup** | Implemented; acceptance reconciliation pending. Historical limits remain in the linked evidence. | A manual API-driven cleanup CLI is implemented; it defa[...] |
| **ARCH-01 — Architecture and recovery decisions** | Planned/deferred; ownership, isolation, and recovery objectives remain unresolved. | Phase B records the intended topology, not deployed pro[...] |

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

### Open decision register

The identifiers group future decision records under ARCH-01; they are not
additional validation gates. The last column describes follow-on sequencing,
not work authorized by this refinement.

| Decision needed (all open) | Questions to resolve in a decision record | Implementation sequencing after an explicit decision |
|---|---|---|
| [**ARCH-01A — Hosting, control-plane ownership, and isolation (Proposed)**](decisions/ARCH-01A-hosting-control-plane-ownership.md) | Managed or self-operated hosting/control planes? Who owns [...] |
| [**ARCH-01B — Management placement and environment boundaries (Proposed)**](decisions/ARCH-01B-management-placement-environment-boundaries.md) | Where do the portal/API, workers, GitOps, iden[...] |
| [**ARCH-01C — Recovery objectives and restore order (Proposed)**](decisions/ARCH-01C-recovery-objectives-restore-order.md) | What RPO (acceptable data loss) and RTO (acceptable recovery time)[...] |
| [**ARCH-01D — prod1/prod2 continuity model (Proposed)**](decisions/ARCH-01D-prod1-proxy-continuity-model.md) | How will intended site independence be achieved? Active/passive or active/active[...] |
| [**ARCH-01E — SSO/OIDC and account management (Proposed)**](decisions/ARCH-01E-identity-account-management.md) | Which identity provider and trust model? How do claims map to operator/develop[...] |
| [**ARCH-01F — Network and credential/data-access hardening (Proposed)**](decisions/ARCH-01F-network-credential-data-access-hardening.md) | What network-policy enforcement and permitted flows [...] |
| [**ARCH-01G — Retention, PITR, HA, and failover (Proposed)**](decisions/ARCH-01G-retention-pitr-ha-failover.md) | What backup scope, schedule, retention/deletion policy, off-site protection, [...] |

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

### Architecture/recovery review checklist for ARCH-01A + ARCH-01C

Use this checklist to review the two records together before moving on to ARCH-01B, then D/E, then F/G.

## What is already settled
- [ ] Phase B intent is already recorded:
  - [ ] target population is approximately 3,000 developers
  - [ ] dev/QA/UAT are the lifecycle focus
  - [ ] `prod1` and `prod2` are intended as separate continuity sites/clusters
- [ ] Existing validation gates remain separate from architecture decisions:
  - [ ] GITOPS-01
  - [ ] PERSIST-01
  - [ ] CAPACITY-01
  - [ ] RESTORE-01
- [ ] The roadmap still distinguishes:
  - [ ] merge from validation
  - [ ] validation evidence from production readiness
  - [ ] ARCH-01 records from accepted designs

## What is still unresolved

### ARCH-01A — Hosting, control-plane ownership, and isolation
- [ ] Hosting model is not selected:
  - [ ] managed
  - [ ] self-operated
  - [ ] split / hybrid
- [ ] Control-plane ownership is not selected:
  - [ ] who operates it
  - [ ] who patches / upgrades it
  - [ ] who responds to incidents
- [ ] Isolation requirements are not fully defined:
  - [ ] management vs workload boundaries
  - [ ] privileged access boundaries
  - [ ] failure domains
- [ ] Operational constraints are not recorded:
  - [ ] availability expectations
  - [ ] cost expectations
  - [ ] support boundaries
  - [ ] recovery responsibility

### ARCH-01C — Recovery objectives and restore order
- [ ] Recovery objectives are not selected:
  - [ ] RPO
  - [ ] RTO
  - [ ] by service or by failure scope
- [ ] In-scope state is not fully inventoried:
  - [ ] platform state
  - [ ] workload state
  - [ ] identity / keys / secrets
  - [ ] databases / storage
- [ ] Restore order is not finalized:
  - [ ] what comes back first
  - [ ] what can be parallelized
  - [ ] what depends on what
- [ ] Recovery authority is not defined:
  - [ ] who declares recovery
  - [ ] who verifies recovery
  - [ ] what evidence is required

## Questions to answer in the review

### ARCH-01A
- [ ] Who owns hosting and the control plane?
- [ ] Is the platform team self-operating infrastructure, using a managed provider, or splitting responsibility?
- [ ] What isolation is required between management and workload components?
- [ ] What failure domains are acceptable?
- [ ] What are the cost and support tradeoffs of the chosen model?

### ARCH-01C
- [ ] What recovery outcomes must be guaranteed?
- [ ] What RPO / RTO values are acceptable for each in-scope service or state?
- [ ] What failure scenarios matter most?
- [ ] What exact restore order should be used?
- [ ] What can be restored in parallel?
- [ ] What evidence will prove the recovery plan is sound?

### Joint A + C questions
- [ ] Do the hosting and isolation choices support the recovery objectives?
- [ ] Are there recovery objectives the proposed hosting model cannot realistically meet?
- [ ] Are there dependencies that force a different ownership or isolation model?
- [ ] Is there a break-glass path that avoids circular dependencies?

## What a valid explicit deferral would need to include
- [ ] A named owner
- [ ] A revisit condition
- [ ] What work is blocked
- [ ] What remains allowed in the meantime
- [ ] Whether the deferral is time-bounded
- [ ] Any residual risk

### Deferral template
- [ ] Deferred item: <hosting model / recovery objective / restore order / etc.>
- [ ] Owner: <named person or team>
- [ ] Revisit condition: <what must happen before revisiting>
- [ ] Blocked work: <what cannot proceed yet>
- [ ] Allowed interim work: <what can still move forward safely>
- [ ] Expiry or review date: <if time-bounded>

## Decision-quality check
- [ ] The review outcome clearly states:
  - [ ] what the platform owns
  - [ ] what it depends on
  - [ ] what it must recover
  - [ ] in what order it must recover
  - [ ] what implementation work is now unblocked

## Recommended review order
- [ ] Review ARCH-01A first
- [ ] Review ARCH-01C second
- [ ] Reassess ARCH-01B, then D/E, then F/G based on the outcomes

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
