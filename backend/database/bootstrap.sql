-- Administrator only. Existing V7 targets require explicit, rehearsed transition opt-in.
BEGIN;
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_namespace WHERE nspname NOT IN ('public', 'information_schema')
               AND nspname !~ '^pg_') THEN
        RAISE EXCEPTION 'Unexpected schema; stop bootstrap or transition';
    END IF;
    IF EXISTS (SELECT FROM pg_class WHERE relnamespace = 'public'::regnamespace)
       OR EXISTS (SELECT FROM pg_proc WHERE pronamespace = 'public'::regnamespace) THEN
        IF current_setting('platform.role_split_transition', true) IS DISTINCT FROM 'on' THEN
            RAISE EXCEPTION 'Bootstrap requires an empty public schema or reviewed transition opt-in';
        END IF;
        IF (SELECT datdba <> (SELECT oid FROM pg_roles WHERE rolname = current_user)
            FROM pg_database WHERE datname = current_database())
           OR EXISTS (SELECT FROM pg_proc WHERE pronamespace = 'public'::regnamespace)
           OR EXISTS (
               SELECT FROM pg_class WHERE relnamespace = 'public'::regnamespace
               AND (relowner <> (SELECT oid FROM pg_roles WHERE rolname = current_user)
                    OR (relkind IN ('r', 'p', 'v', 'm', 'f', 'S') AND relname NOT IN (
                        'environments', 'operations', 'idempotency_requests', 'audit_events',
                        'operation_events', 'platform_teams', 'account_team_memberships',
                        'flyway_schema_history', 'operation_events_id_seq')))) THEN
            RAISE EXCEPTION 'Unexpected ownership or objects; stop transition';
        END IF;
        IF (SELECT count(*) FROM public.flyway_schema_history) <> 7
           OR EXISTS (SELECT FROM public.flyway_schema_history
                      WHERE NOT success OR version IS NULL
                         OR version NOT IN ('1', '2', '3', '4', '5', '6', '7')) THEN
            RAISE EXCEPTION 'Transition requires the existing V7 migration history';
        END IF;
    END IF;
END $$;

-- Existing role names deliberately fail rather than trusting unknown effective privileges.
CREATE ROLE platform_migration NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE platform_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOINHERIT NOREPLICATION NOBYPASSRLS;

DO $$
BEGIN
    EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO platform_migration, platform_runtime',
                   current_database());
END $$;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO platform_migration;
GRANT USAGE ON SCHEMA public TO platform_runtime;

-- Transfer only the inventoried application objects, never REASSIGN OWNED or data/history.
DO $$
DECLARE
    object_name text;
BEGIN
    IF current_setting('platform.role_split_transition', true) = 'on' THEN
        FOREACH object_name IN ARRAY ARRAY['environments', 'operations', 'idempotency_requests',
                'audit_events', 'operation_events', 'platform_teams', 'account_team_memberships',
                'flyway_schema_history'] LOOP
            EXECUTE format('ALTER TABLE public.%I OWNER TO platform_migration', object_name);
            EXECUTE format('REVOKE ALL ON TABLE public.%I FROM PUBLIC', object_name);
        END LOOP;
        -- The identity sequence follows its table's owner.
        REVOKE ALL ON SEQUENCE public.operation_events_id_seq FROM PUBLIC;
    END IF;
END $$;

-- Future objects are deny-by-default, including PostgreSQL's default PUBLIC EXECUTE.
ALTER DEFAULT PRIVILEGES FOR ROLE platform_migration REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE platform_migration REVOKE ALL ON SEQUENCES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE platform_migration REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
COMMIT;
