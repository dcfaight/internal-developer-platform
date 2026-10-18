"""Opt-in logical round trip using only disposable, socket-isolated PostgreSQL pods."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
import uuid


ROOT = Path(__file__).resolve().parents[1]
POSTGRES_IMAGE = "postgres:18.6"
BACKEND_IMAGE = "platform-lab-backend:k3s-v2"
OWNER = "platform-lab/backup-drill"
TABLES = ("platform_teams", "account_team_memberships", "environments", "operations",
          "audit_events", "idempotency_requests", "operation_events", "flyway_schema_history")
SCHEMA_KEY = "BackupRestoreDrillSchemaComparison"


class DrillError(RuntimeError):
    pass


class VerificationError(DrillError):
    def __init__(self, results):
        super().__init__("Restored data, sequence state or schema differs")
        self.results = results


def configuration(arguments=None, environ=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable", action="store_true")
    parser.add_argument("--context", required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    args = parser.parse_args(arguments)
    environ = os.environ if environ is None else environ
    if environ.get("PLATFORM_BACKUP_RESTORE_TEST") != "true" or not args.confirm_disposable:
        raise DrillError("Requires PLATFORM_BACKUP_RESTORE_TEST=true and --confirm-disposable")
    if not args.context.strip() or args.context.startswith("-"):
        raise DrillError("An explicit K3s context is required")
    if not args.artifacts.is_absolute():
        raise DrillError("Artifacts directory must be absolute and outside the checkout")
    args.artifacts = args.artifacts.resolve()
    # Also reject ancestors of the checkout, where a run directory could contain it.
    if args.artifacts.is_relative_to(ROOT) or ROOT.is_relative_to(args.artifacts):
        raise DrillError("Artifacts directory must be outside the checkout")
    if not args.artifacts.is_dir():
        raise DrillError("Create the external artifacts directory first")
    return args


def resources(namespace, token, password):
    labels = {OWNER: token}
    postgres = {
        "name": "postgres", "image": POSTGRES_IMAGE,
        "args": ["-c", "listen_addresses=127.0.0.1"],
        "env": [{"name": key, "value": value} for key, value in {
            "POSTGRES_DB": "drill", "POSTGRES_USER": "drill",
            "POSTGRES_PASSWORD": password,
        }.items()],
        "volumeMounts": [{"name": "data", "mountPath": "/var/lib/postgresql"}],
        "resources": {"requests": {"cpu": "100m", "memory": "128Mi"},
                      "limits": {"cpu": "1", "memory": "512Mi"}},
        "readinessProbe": {
            "exec": {"command": ["pg_isready", "-h", "127.0.0.1", "-U", "drill", "-d", "drill"]},
            "periodSeconds": 2, "timeoutSeconds": 2,
        },
    }
    migrator = {
        "name": "migrator", "image": BACKEND_IMAGE, "imagePullPolicy": "Never",
        "command": ["sh", "-c",
                    "java -Dloader.main=io.github.dcfaight.platformlab.DatabaseMigration "
                    "-cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher "
                    "&& touch /tmp/migrated && sleep 900"],
        "env": [{"name": key, "value": value} for key, value in {
            "MIGRATION_DB_URL": "jdbc:postgresql://127.0.0.1:5432/drill",
            "MIGRATION_DB_USERNAME": "drill", "MIGRATION_DB_PASSWORD": password,
        }.items()],
        "resources": {"requests": {"cpu": "100m", "memory": "256Mi"},
                      "limits": {"cpu": "1", "memory": "512Mi"}},
        "readinessProbe": {
            "exec": {"command": ["test", "-f", "/tmp/migrated"]},
            "periodSeconds": 2, "timeoutSeconds": 3,
        },
    }
    policy = {
        "apiVersion": "networking.k8s.io/v1", "kind": "NetworkPolicy",
        "metadata": {"name": "deny-all", "namespace": namespace, "labels": labels},
        "spec": {"podSelector": {}, "policyTypes": ["Ingress", "Egress"],
                 "ingress": [], "egress": []},
    }
    pods = []
    for name in ("source", "restore"):
        spec = {
            "automountServiceAccountToken": False, "restartPolicy": "Never",
            "activeDeadlineSeconds": 900, "terminationGracePeriodSeconds": 5,
            "containers": [postgres] + ([migrator] if name == "source" else []),
            "volumes": [{"name": "data", "emptyDir": {"sizeLimit": "1Gi"}}],
        }
        pods.append({"apiVersion": "v1", "kind": "Pod",
                     "metadata": {"name": name, "namespace": namespace, "labels": labels},
                     "spec": spec})
    return [policy, *pods]


def fixture_sql():
    statements = ["BEGIN;"]
    for index, team in enumerate(("drill-a", "drill-b"), 1):
        environment = f"00000000-0000-0000-0000-{index:012d}"
        operation = f"10000000-0000-0000-0000-{index:012d}"
        audit = f"20000000-0000-0000-0000-{index:012d}"
        namespace = "env-" + environment.replace("-", "")
        statements.extend([
            f"INSERT INTO platform_teams VALUES ('{team}');",
            f"INSERT INTO account_team_memberships VALUES ('drill-user', '{team}');",
            "INSERT INTO environments "
            "(id,name,namespace,template_id,status,expires_at,created_at,updated_at,team_id,created_by) "
            f"VALUES ('{environment}','fixture-{index}','{namespace}','sample-service','REQUESTED',"
            f"'2030-01-01T01:00:00Z','2030-01-01T00:00:00Z','2030-01-01T00:00:00Z','{team}','drill-user');",
            "INSERT INTO operations "
            "(id,environment_id,type,status,next_attempt_at,created_at,updated_at) "
            f"VALUES ('{operation}','{environment}','CREATE_ENVIRONMENT','PENDING',"
            "'2030-01-01T00:00:00Z','2030-01-01T00:00:00Z','2030-01-01T00:00:00Z');",
            "INSERT INTO audit_events VALUES "
            f"('{audit}','{environment}','{operation}','ENVIRONMENT_REQUESTED','drill-user',"
            "'2030-01-01T00:00:00Z');",
            "INSERT INTO idempotency_requests "
            "(scope,key,name,template_id,ttl_hours,environment_id,operation_id) VALUES "
            f"('drill-user:{team}','shared-key','fixture-{index}','sample-service',1,"
            f"'{environment}','{operation}');",
            "INSERT INTO operation_events (operation_id,type,code,message,created_at) "
            f"VALUES ('{operation}','PROGRESS','ACCEPTED','Known drill fixture',"
            "'2030-01-01T00:00:00Z');",
        ])
    return "\n".join([*statements, "COMMIT;"])


def verify_fixture(snapshot):
    """Validate expected values and relationships, not just a successful restore."""
    for table in TABLES[:-1]:
        if len(snapshot[table]) != 2:
            raise DrillError("Fixture row count mismatch")
    for index, team in enumerate(("drill-a", "drill-b"), 1):
        environment = f"00000000-0000-0000-0000-{index:012d}"
        operation = f"10000000-0000-0000-0000-{index:012d}"
        expected = {
            "platform_teams": {"id": team},
            "account_team_memberships": {"username": "drill-user", "team_id": team},
            "environments": {"id": environment, "name": f"fixture-{index}",
                             "namespace": "env-" + environment.replace("-", ""),
                             "status": "REQUESTED", "team_id": team, "created_by": "drill-user"},
            "operations": {"id": operation, "environment_id": environment,
                           "type": "CREATE_ENVIRONMENT", "status": "PENDING", "attempt_count": 0},
            "audit_events": {"environment_id": environment, "operation_id": operation,
                             "action": "ENVIRONMENT_REQUESTED", "actor": "drill-user"},
            "idempotency_requests": {"scope": f"drill-user:{team}", "key": "shared-key",
                                     "name": f"fixture-{index}", "ttl_hours": 1,
                                     "environment_id": environment, "operation_id": operation},
            "operation_events": {"operation_id": operation, "type": "PROGRESS", "code": "ACCEPTED"},
        }
        for table, values in expected.items():
            if not any(all(row.get(key) == value for key, value in values.items())
                       for row in snapshot[table]):
                raise DrillError("Fixture values or relationships mismatch")
    history = snapshot["flyway_schema_history"]
    migrations = sorted((ROOT / "backend/src/main/resources/db/migration").glob("V*__*.sql"))
    if (len(history) != len(migrations)
            or {row["script"] for row in history} != {path.name for path in migrations}
            or any(row["success"] is not True or row["checksum"] is None
                   or row["version"] != row["script"].split("__")[0][1:] for row in history)):
        raise DrillError("Flyway history mismatch")


def verify_restore(source, restored, source_schema, restored_schema):
    verify_fixture(source)
    verify_fixture(restored)
    results = {table: source[table] == restored[table] for table in (*TABLES, "sequence_state")}
    results["schema"] = source_schema == restored_schema
    if not all(results.values()):
        raise VerificationError(results)
    return results


class Drill:
    def __init__(self, args):
        self.args = args
        self.token = uuid.uuid4().hex
        self.namespace = "backup-drill-" + self.token
        self.uid = None
        self.directory = Path(tempfile.mkdtemp(prefix="backup-drill-", dir=args.artifacts))
        self.evidence = {"status": "failed", "namespace": self.namespace,
                         "context": args.context, "cleanup": "not-created"}

    def kubectl(self, *args, data=None, output=None, timeout=60):
        try:
            result = subprocess.run(
                ["kubectl", "--context", self.args.context, "--request-timeout=15s", *args],
                input=data, stdout=output if output is not None else subprocess.PIPE,
                stderr=subprocess.PIPE, timeout=timeout, check=True)
            return result.stdout
        except (subprocess.SubprocessError, OSError):
            # Do not expose command output, SQL, credentials or server error bodies.
            raise DrillError("Kubernetes command failed or timed out; inspect drill-owned resources") from None

    def sql(self, pod, sql, database="drill"):
        return self.kubectl("-n", self.namespace, "exec", "-i", pod, "-c", "postgres", "--",
                            "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "drill",
                            "-d", database, data=sql.encode())

    def snapshot(self, pod):
        result = {}
        for table in TABLES:
            result[table] = json.loads(self.sql(
                pod, f"SELECT coalesce(json_agg(r ORDER BY r::text), '[]') "
                     f"FROM (SELECT row_to_json(t) AS r FROM {table} t) s;"))
        result["sequence_state"] = json.loads(self.sql(
            pod, "SELECT row_to_json(s) FROM "
                 "(SELECT last_value, is_called FROM operation_events_id_seq) s;"))
        return result

    def schema(self, pod, database="drill"):
        return self.kubectl("-n", self.namespace, "exec", pod, "-c", "postgres", "--",
                            "pg_dump", "-U", "drill", "-d", database, "--schema-only",
                            "--no-owner", "--no-privileges", "--restrict-key=" + SCHEMA_KEY)

    def normalized_source_schema(self):
        original = self.schema("source")
        # PostgreSQL reparses array casts on restore; compare server-normalized DDL.
        self.sql("source", "CREATE DATABASE schema_reference;")
        self.sql("source", original.decode(), database="schema_reference")
        return self.schema("source", database="schema_reference")

    def identity(self, pod):
        resource = json.loads(self.kubectl("-n", self.namespace, "get", "pod", pod, "-o", "json"))
        return {"pod": pod, "uid": resource["metadata"]["uid"], "database": "drill",
                "storage": "pod-owned emptyDir",
                "images": [{key: container[key] for key in ("name", "image", "imageID")}
                           for container in resource["status"]["containerStatuses"]],
                "postgres_version": self.sql(pod, "SHOW server_version;").decode().strip()}

    def cleanup(self):
        resource = json.loads(self.kubectl("get", "namespace", self.namespace, "-o", "json"))
        metadata = resource["metadata"]
        if metadata["uid"] != self.uid or metadata.get("labels", {}).get(OWNER) != self.token:
            raise DrillError("Cleanup refused: namespace identity changed")
        options = {"apiVersion": "v1", "kind": "DeleteOptions",
                   "preconditions": {"uid": self.uid}}
        self.kubectl("delete", "--raw=/api/v1/namespaces/" + self.namespace, "-f", "-",
                     data=json.dumps(options).encode())
        self.kubectl("wait", "--for=delete", "namespace/" + self.namespace,
                     "--timeout=90s", timeout=100)
        self.evidence["cleanup"] = "deleted-owned-namespace"

    def run(self):
        stage = "cluster-check"
        try:
            version = json.loads(self.kubectl("version", "-o", "json"))["serverVersion"]["gitVersion"]
            if "+k3s" not in version:
                raise DrillError("An explicitly selected disposable K3s server is required")
            self.evidence["kubernetes_version"] = version
            stage = "namespace-create"
            namespace = {"apiVersion": "v1", "kind": "Namespace",
                         "metadata": {"name": self.namespace, "labels": {OWNER: self.token}}}
            # Only create, never apply/adopt an existing namespace.
            self.evidence["cleanup"] = "namespace-create-not-confirmed"
            created = json.loads(self.kubectl("create", "-f", "-", "-o", "json",
                                             data=json.dumps(namespace).encode()))
            self.uid = created["metadata"]["uid"]
            self.evidence["namespace_uid"] = self.uid
            self.evidence["cleanup"] = "pending"
            stage = "pod-startup"
            for resource in resources(self.namespace, self.token, secrets.token_hex(32)):
                self.kubectl("-n", self.namespace, "create", "-f", "-",
                             data=json.dumps(resource).encode())
            self.kubectl("-n", self.namespace, "wait", "--for=condition=Ready",
                         "pod/source", "pod/restore", "--timeout=180s", timeout=195)
            self.evidence["source"] = self.identity("source")
            self.evidence["restore"] = self.identity("restore")
            if self.evidence["source"]["uid"] == self.evidence["restore"]["uid"]:
                raise DrillError("Source and restore targets must differ")
            stage = "fixture"
            self.sql("source", fixture_sql())
            source = self.snapshot("source")
            verify_fixture(source)
            source_schema = self.normalized_source_schema()
            source_schema_path = self.directory / "source-schema.sql"
            source_schema_path.write_bytes(source_schema)
            os.chmod(source_schema_path, 0o600)
            stage = "backup"
            backup = self.directory / "fixture.dump"
            start = time.monotonic()
            with backup.open("xb") as stream:
                os.chmod(backup, 0o600)
                self.kubectl("-n", self.namespace, "exec", "source", "-c", "postgres", "--",
                             "pg_dump", "-U", "drill", "-d", "drill", "--format=custom",
                             "--no-owner", "--no-privileges", output=stream, timeout=120)
            self.evidence["backup_seconds"] = round(time.monotonic() - start, 3)
            checksum = hashlib.sha256(backup.read_bytes()).hexdigest()
            self.evidence["backup_sha256"] = checksum
            (self.directory / "fixture.dump.sha256").write_text(checksum + "  fixture.dump\n")
            stage = "restore"
            if self.sql("restore", "SELECT count(*) FROM pg_tables WHERE schemaname='public';").strip() != b"0":
                raise DrillError("Restore database is not fresh")
            start = time.monotonic()
            self.kubectl("-n", self.namespace, "exec", "-i", "restore", "-c", "postgres", "--",
                         "pg_restore", "-U", "drill", "-d", "drill", "--exit-on-error",
                         "--single-transaction", "--no-owner", "--no-privileges",
                         data=backup.read_bytes(), timeout=120)
            self.evidence["restore_seconds"] = round(time.monotonic() - start, 3)
            stage = "verification"
            restored_schema = self.schema("restore")
            restored_schema_path = self.directory / "restore-schema.sql"
            restored_schema_path.write_bytes(restored_schema)
            os.chmod(restored_schema_path, 0o600)
            self.evidence["verification"] = verify_restore(
                source, self.snapshot("restore"), source_schema, restored_schema)
            self.evidence["schema_sha256"] = hashlib.sha256(source_schema).hexdigest()
            self.evidence["status"] = "passed"
        except BaseException as error:
            if isinstance(error, VerificationError):
                self.evidence["verification"] = error.results
            self.evidence["failed_stage"] = stage
            raise
        finally:
            try:
                if self.uid:
                    self.cleanup()
            except BaseException:
                self.evidence["status"] = "failed"
                self.evidence["cleanup"] = "failed-inspect-owned-namespace"
                self.evidence.setdefault("failed_stage", "cleanup")
                raise
            finally:
                evidence = self.directory / "evidence.json"
                evidence.write_text(json.dumps(self.evidence, indent=2) + "\n")
                os.chmod(evidence, 0o600)
                print("Drill artifacts (retain securely):", self.directory)


def main(arguments=None):
    try:
        Drill(configuration(arguments)).run()
    except (DrillError, ValueError, KeyError, OSError):
        print("Backup/restore drill failed; inspect sanitized evidence and drill-owned resources.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
