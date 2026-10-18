# ARCH-01: Recommended Architecture Direction

**Record type:** Recommendation note; not an accepted decision or implementation record
**Date:** 2026-10-18
**Scope:** Recommended direction for ARCH-01A through ARCH-01G

## Purpose and basis

This note provides a concrete, reviewable direction for the open ARCH-01
questions. It is based on the repository's accepted [Phase B target
architecture](phase-b-target-architecture.md), the [operating model and
roadmap](../operating-model-and-sequence.md), the existing ARCH-01 records, and
the portfolio goal and job description supplied for this project. It does not
claim to describe eBay's internal architecture or requirements.

The existing foundation is a platform-team-owned internal developer platform
with guarded developer self-service, React/TypeScript, Java/Spring Boot,
PostgreSQL, Kubernetes, and Python automation. Approximately 3,000 developers
is the design population, not a measured capacity result. The current
implementation and evidence remain a bounded demonstration, not production
readiness.

**None of these recommendations accepts, replaces, or changes the status of
ARCH-01A through ARCH-01G.** Each corresponding ADR remains governed by its own
status, review, acceptance criteria, and follow-up. This note recommends a
direction to review against those records; it is not authorization to deploy,
purchase services, or claim validation.

## Recommended directions

| ADR | Recommended direction | Defer from portfolio release | Future expansion |
|---|---|---|---|
| [A — Hosting and control-plane ownership](ARCH-01A-hosting-control-plane-ownership.md) | Model a platform team as owner and operator of the platform and its control plane; developers consume guarded self-service workflows. Use the existing self-operated, disposable K3s deployment to demonstrate the platform. | Production hosting/provider selection, production support commitments, production failure-domain claims, and a hybrid provider/operator split. | Compare self-operated and managed options against actual ownership, cost, support, and recovery constraints when a real deployment target exists. |
| [B — Management placement and boundaries](ARCH-01B-management-placement-environment-boundaries.md) | Keep management services inside a protected platform boundary and developer workloads in separate namespaces/boundaries. Treat namespace separation as a useful guardrail, not strong tenant isolation; scope identities and permissions and keep production outside the demo boundary. | Production-grade tenant isolation, independent management infrastructure, and claims that the disposable cluster enforces a hardened trust boundary. | Refine the protected management boundary and cross-environment controls against A/C outcomes; use a separate, longer-lived management environment if the eventual operating model warrants it. |
| [C — Recovery objectives and restore order](ARCH-01C-recovery-objectives-restore-order.md) | For the portfolio slice, propose a **24-hour RPO and 4-hour RTO** for restoring platform PostgreSQL state to a replacement disposable target. Use dependency-based recovery, with independently available recovery access and required keys, then database, management API/workers, and workload reconciliation. | Treating these values as accepted requirements, measured outcomes, guarantees, or targets for site loss; recovery of arbitrary workload data; broad cluster/site recovery. | Review per-service and per-failure-scope objectives, safe parallel steps, and recovery authority; measure and revise targets through scoped drills. |
| [D — prod1/prod2 continuity](ARCH-01D-prod1-proxy-continuity-model.md) | Preserve the accepted intent for separate `prod1`/`prod2` continuity sites. For future design, prefer one authoritative writer and operator-controlled transitions over unproven automatic failover. | Replication, site provisioning, traffic switching, fencing implementation, failover/failback, and any continuity claim. | Reconcile a concrete continuity mechanism with accepted C objectives, ownership, and data-safety requirements. |
| [E — Identity and account management](ARCH-01E-identity-account-management.md) | Preserve distinct operator, team-scoped developer, and restricted cleanup responsibilities. Keep current bounded authentication for the portfolio release; organizational OIDC with platform-owned authorization is a next-step direction, not a release prerequisite. | OIDC integration, enterprise account lifecycle, and production identity assurance. | Add federation, lifecycle/revocation, approval, service-identity, and independently recoverable break-glass controls when their dependencies and owner are known. |
| [F — Network and data-access hardening](ARCH-01F-network-credential-data-access-hardening.md) | Separate runtime, schema migration, membership administration, backup, and restore responsibilities. Runtime access must not own the schema or carry backup/restore administration. | A production-wide network-policy and credential-hardening rollout or a claim of strong tenant isolation. | Define least-privilege grants, permitted flows, credential/key lifecycle, and recovery access against the accepted B/C/E boundaries. |
| [G — Retention, PITR, HA, and failover](ARCH-01G-retention-pitr-ha-failover.md) | Prioritize an independent backup and a verified restore path for the in-scope platform database before adding availability mechanisms. Keep backup/restore distinct from replication and HA. | Enterprise retention policy, PITR, database HA, replication, and automated failover. | Select schedules, retention, off-site/key custody, PITR, HA, or continuity mechanisms only where reviewed recovery objectives justify them. |

The portfolio release should remain a small but credible end-to-end slice:
request an environment through the existing self-service workflow, observe its
lifecycle, and safely clean it up; demonstrate only the bounded recovery path
that is actually implemented and evidenced. Do not expand the slice merely to
imply support for the 3,000-developer design population.

## A/C review direction

For A, the recommended hosting answer is platform-team operation of the
demonstration environment, using the existing self-operated K3s deployment.
Self-service describes how developers consume the platform; it does not imply
hybrid hosting. The demo has a single disposable failure domain and makes no
production hosting, support, cost, or availability commitment.

For B, protect management components from ordinary workload access and place
developer workloads separately. Namespaces help organize and scope access but
are not, by themselves, a strong tenant-security boundary. A production-grade
boundary and its network, identity, and failure-domain controls remain future
work.

For C, the 24-hour RPO / 4-hour RTO values above are proposed engineering
targets for one narrow database-recovery scenario: restore platform state to a
replacement disposable target. They are not eBay requirements, accepted ADR
values, achieved results, or enterprise guarantees. The proposed sequence is:

1. Establish recovery access and the replacement disposable foundation using a
   path independent of the failed management service.
2. Make required identity and decryption keys available; resolve any circular
   dependency through an independently controlled bootstrap path.
3. Restore PostgreSQL and verify schema, data integrity, ownership, and grants.
4. Restore or start management API/workers; keep mutating workers stopped until
   the data and permissions are verified.
5. Reconcile only in-scope workloads and verify the end-to-end service.

Independent prerequisites may proceed in parallel when their dependencies are
documented. Evidence for this slice should record recovery start/end events,
elapsed time and recoverable point, integrity and authorization checks, and a
sanitized durable result. Node, cluster, or site loss; broad workload recovery;
and production recovery remain out of scope. Review A and C together so hosting,
access, and failure-domain limits are checked against the proposed recovery
target rather than assumed away.

## Settled and unresolved

**Already settled by existing documentation:** the platform-team operating
intent with developer self-service; React/TypeScript, Java/Spring Boot,
PostgreSQL, Kubernetes, and Python as the documented foundation; dev/QA/UAT as
the lifecycle focus; and `prod1`/`prod2` as intended continuity sites. The
approximately 3,000 figure is a target population, not demonstrated capacity.
Current validation gates and their evidence remain separate from ARCH-01.

**Still unresolved and not silently decided here:** accepted dispositions for
A–G; production hosting and support ownership; enforced isolation and failure
domains; approved recovery authority and targets beyond the proposed bounded
database scenario; identity provider and lifecycle; detailed network/data
controls; and backup schedules, retention, PITR, HA, and failover mechanisms.
The relevant ADRs retain their existing Proposed statuses and review processes.

**Joint A/C direction:** evaluate the self-operated disposable demo against the
bounded database restore target, state the limits of its failure domain, and
identify independent access/key prerequisites. Carry that recommendation into
the A/C review; do not use the demo to claim production objectives are met or
let the deferred production architecture block the bounded portfolio slice.
