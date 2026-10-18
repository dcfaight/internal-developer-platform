-- Administrator only, after independent migrations and before enabling runtime login.
BEGIN;
GRANT SELECT ON public.environments, public.operations, public.idempotency_requests,
    public.operation_events TO platform_runtime;
GRANT SELECT (id) ON public.platform_teams TO platform_runtime;
GRANT SELECT (username, team_id) ON public.account_team_memberships TO platform_runtime;

GRANT INSERT (id, name, namespace, template_id, status, expires_at, created_at,
    updated_at, team_id, created_by) ON public.environments TO platform_runtime;
GRANT UPDATE (status, updated_at) ON public.environments TO platform_runtime;
GRANT INSERT (id, environment_id, type, status, attempt_count, next_attempt_at,
    created_at, updated_at, correlation_id) ON public.operations TO platform_runtime;
GRANT UPDATE (status, lease_token, lease_expires_at, attempt_count, started_at,
    completed_at, failure_code, failure_message, progress_step, updated_at,
    failure_count, provisioned_at, next_attempt_at) ON public.operations TO platform_runtime;
GRANT INSERT (scope, key, name, template_id, ttl_hours, environment_id, operation_id)
    ON public.idempotency_requests TO platform_runtime;
GRANT INSERT (id, environment_id, operation_id, action, actor, created_at)
    ON public.audit_events TO platform_runtime;
GRANT INSERT (operation_id, type, code, message, correlation_id)
    ON public.operation_events TO platform_runtime;
GRANT USAGE ON SEQUENCE public.operation_events_id_seq TO platform_runtime;
COMMIT;
