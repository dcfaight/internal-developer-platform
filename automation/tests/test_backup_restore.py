"""Offline safety contracts and an explicitly opt-in disposable K3s round trip."""

import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("backup_restore", ROOT / "automation/backup_restore.py")
drill = importlib.util.module_from_spec(spec)
spec.loader.exec_module(drill)


def valid_snapshot():
    snapshot = {table: [] for table in drill.TABLES}
    for index, team in enumerate(("drill-a", "drill-b"), 1):
        environment = f"00000000-0000-0000-0000-{index:012d}"
        operation = f"10000000-0000-0000-0000-{index:012d}"
        rows = {
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
        for table, row in rows.items():
            snapshot[table].append(row)
    snapshot["flyway_schema_history"] = [
        {"script": path.name, "version": path.name.split("__")[0][1:],
         "success": True, "checksum": index}
        for index, path in enumerate(sorted(
            (ROOT / "backend/src/main/resources/db/migration").glob("V*__*.sql")))
    ]
    snapshot["sequence_state"] = {"last_value": 2, "is_called": True}
    return snapshot


class BackupRestoreSafetyTests(unittest.TestCase):
    def arguments(self, directory):
        return ["--confirm-disposable", "--context", "disposable-k3s", "--artifacts", directory]

    def test_requires_both_opt_ins_before_any_command_or_directory_creation(self):
        for environ, flag in (({}, True), ({"PLATFORM_BACKUP_RESTORE_TEST": "TRUE"}, True),
                              ({"PLATFORM_BACKUP_RESTORE_TEST": "true"}, False)):
            arguments = self.arguments("/tmp")
            if not flag:
                arguments.remove("--confirm-disposable")
            with self.subTest(environ=environ, flag=flag), patch.dict(
                    os.environ, environ, clear=True), patch.object(
                    drill.subprocess, "run") as command, patch.object(
                    drill.tempfile, "mkdtemp") as mkdir:
                self.assertEqual(1, drill.main(arguments))
                command.assert_not_called()
                mkdir.assert_not_called()

    def test_rejects_checkout_relative_and_symlinked_artifact_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            link = Path(temporary) / "checkout"
            link.symlink_to(ROOT, target_is_directory=True)
            for path in (str(ROOT), str(ROOT / "automation"), ".", "/", str(link)):
                with self.subTest(path=path), self.assertRaises(drill.DrillError):
                    drill.configuration(self.arguments(path), {"PLATFORM_BACKUP_RESTORE_TEST": "true"})
            args = drill.configuration(self.arguments(temporary), {"PLATFORM_BACKUP_RESTORE_TEST": "true"})
            self.assertEqual(Path(temporary).resolve(), args.artifacts)

    def test_missing_or_option_like_context_is_rejected(self):
        for context in ("", "  ", "--current-context"):
            with self.subTest(context=context), self.assertRaises(drill.DrillError):
                arguments = self.arguments("/tmp")
                arguments[arguments.index("--context") + 1] = context
                if context.startswith("-"):
                    arguments[1:3] = ["--context=" + context]
                drill.configuration(arguments, {"PLATFORM_BACKUP_RESTORE_TEST": "true"})

    def test_resources_never_reference_live_storage_services_or_worker_identity(self):
        resources = drill.resources("backup-drill-test", "token", "generated-test-value")
        self.assertEqual(["NetworkPolicy", "Pod", "Pod"], [r["kind"] for r in resources])
        policy, source, target = resources
        self.assertEqual(["Ingress", "Egress"], policy["spec"]["policyTypes"])
        self.assertEqual([], policy["spec"]["ingress"])
        self.assertEqual([], policy["spec"]["egress"])
        for pod in (source, target):
            self.assertEqual("backup-drill-test", pod["metadata"]["namespace"])
            spec = pod["spec"]
            self.assertFalse(spec["automountServiceAccountToken"])
            self.assertNotIn("serviceAccountName", spec)
            self.assertNotIn("hostNetwork", spec)
            self.assertNotIn("hostPID", spec)
            self.assertEqual([{"name": "data", "emptyDir": {"sizeLimit": "1Gi"}}], spec["volumes"])
            self.assertEqual(900, spec["activeDeadlineSeconds"])
            self.assertEqual(["-c", "listen_addresses=127.0.0.1"], spec["containers"][0]["args"])
            self.assertEqual(["pg_isready", "-h", "127.0.0.1", "-U", "drill", "-d", "drill"],
                             spec["containers"][0]["readinessProbe"]["exec"]["command"])
        self.assertEqual(["postgres"], [c["name"] for c in target["spec"]["containers"]])
        migrator = source["spec"]["containers"][1]
        environment = {entry["name"]: entry["value"] for entry in migrator["env"]}
        self.assertIn("DatabaseMigration", migrator["command"][2])
        self.assertNotIn("DB_PASSWORD", environment)
        self.assertEqual("jdbc:postgresql://127.0.0.1:5432/drill", environment["MIGRATION_DB_URL"])
        self.assertEqual(["test", "-f", "/tmp/migrated"],
                         migrator["readinessProbe"]["exec"]["command"])
        serialized = json.dumps(resources)
        for forbidden in ("persistentVolumeClaim", "hostPath", '"namespace": "platform-lab"', "platform-secrets",
                          "platform-config", "postgres-data", "platform-worker"):
            self.assertNotIn(forbidden, serialized)

    def test_cleanup_is_label_and_uid_fenced(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = drill.Drill(SimpleNamespace(context="disposable", artifacts=Path(directory)))
            instance.uid = "owned-uid"
            for uid, token in (("other", instance.token), ("owned-uid", "other")):
                resource = {"metadata": {"uid": uid, "labels": {drill.OWNER: token}}}
                with self.subTest(uid=uid, token=token), patch.object(
                        instance, "kubectl", return_value=json.dumps(resource).encode()) as command:
                    with self.assertRaises(drill.DrillError):
                        instance.cleanup()
                    self.assertEqual(1, command.call_count)
            resource = {"metadata": {"uid": instance.uid, "labels": {drill.OWNER: instance.token}}}
            with patch.object(instance, "kubectl", side_effect=[
                    json.dumps(resource).encode(), b"{}", b""]) as command:
                instance.cleanup()
                deletion = command.call_args_list[1]
                self.assertEqual(("delete", "--raw=/api/v1/namespaces/" + instance.namespace,
                                  "-f", "-"), deletion.args)
                self.assertEqual({"uid": instance.uid}, json.loads(deletion.kwargs["data"])["preconditions"])
                self.assertEqual("deleted-owned-namespace", instance.evidence["cleanup"])

    def test_command_errors_are_bounded_and_sanitized(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = drill.Drill(SimpleNamespace(context="disposable", artifacts=Path(directory)))
            for error in (subprocess.CalledProcessError(1, "secret", stderr=b"private"),
                          subprocess.TimeoutExpired("secret", 60)):
                with patch.object(drill.subprocess, "run", side_effect=error) as command:
                    with self.assertRaises(drill.DrillError) as raised:
                        instance.kubectl("get", "pods")
                    self.assertNotIn("secret", str(raised.exception))
                    self.assertNotIn("private", str(raised.exception))
                    self.assertEqual(60, command.call_args.kwargs["timeout"])
                    self.assertIn("--request-timeout=15s", command.call_args.args[0])

    def test_non_k3s_cluster_is_rejected_without_mutation_and_evidence_is_private(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = drill.Drill(SimpleNamespace(context="disposable", artifacts=Path(directory)))
            with patch.object(instance, "kubectl", return_value=json.dumps({
                    "serverVersion": {"gitVersion": "v1.35.1"}}).encode()) as command:
                with self.assertRaises(drill.DrillError):
                    instance.run()
                self.assertEqual(1, command.call_count)
            evidence_path = instance.directory / "evidence.json"
            evidence = json.loads(evidence_path.read_text())
            self.assertEqual("failed", evidence["status"])
            self.assertEqual("cluster-check", evidence["failed_stage"])
            self.assertEqual(0o700, instance.directory.stat().st_mode & 0o777)
            self.assertEqual(0o600, evidence_path.stat().st_mode & 0o777)

    def test_failure_after_creation_cleans_owned_namespace_and_records_stage(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = drill.Drill(SimpleNamespace(context="disposable", artifacts=Path(directory)))
            with patch.object(instance, "kubectl", side_effect=[
                    b'{"serverVersion":{"gitVersion":"v1.35.1+k3s1"}}',
                    b'{"metadata":{"uid":"owned"}}', drill.DrillError("private")]), patch.object(
                    instance, "cleanup") as cleanup:
                with self.assertRaises(drill.DrillError):
                    instance.run()
                cleanup.assert_called_once()
            evidence = json.loads((instance.directory / "evidence.json").read_text())
            self.assertEqual("pod-startup", evidence["failed_stage"])
            self.assertNotIn("private", json.dumps(evidence))

    def test_cleanup_failure_is_recorded_without_hiding_failure_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = drill.Drill(SimpleNamespace(context="disposable", artifacts=Path(directory)))
            with patch.object(instance, "kubectl", side_effect=[
                    b'{"serverVersion":{"gitVersion":"v1.35.1+k3s1"}}',
                    b'{"metadata":{"uid":"owned"}}', drill.DrillError("startup")]), patch.object(
                    instance, "cleanup", side_effect=drill.DrillError("cleanup")):
                with self.assertRaises(drill.DrillError):
                    instance.run()
            evidence = json.loads((instance.directory / "evidence.json").read_text())
            self.assertEqual("failed", evidence["status"])
            self.assertEqual("pod-startup", evidence["failed_stage"])
            self.assertEqual("failed-inspect-owned-namespace", evidence["cleanup"])

    def test_schema_normalization_uses_only_source_scratch_database(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = drill.Drill(SimpleNamespace(context="disposable", artifacts=Path(directory)))
            with patch.object(instance, "schema", side_effect=[b"original-ddl", b"normalized-ddl"]) as schema, \
                    patch.object(instance, "sql") as sql:
                self.assertEqual(b"normalized-ddl", instance.normalized_source_schema())
                self.assertEqual([unittest.mock.call("source"),
                                  unittest.mock.call("source", database="schema_reference")],
                                 schema.call_args_list)
                self.assertEqual([
                    unittest.mock.call("source", "CREATE DATABASE schema_reference;"),
                    unittest.mock.call("source", "original-ddl", database="schema_reference"),
                ], sql.call_args_list)

    def test_complete_command_trace_only_mutates_drill_resources_and_emits_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = drill.Drill(SimpleNamespace(context="disposable", artifacts=Path(directory)))
            snapshot = valid_snapshot()
            created_resources = []
            restored = []

            def command(argv, **kwargs):
                self.assertEqual(["kubectl", "--context", "disposable", "--request-timeout=15s"],
                                 argv[:4])
                args = argv[4:]
                data = kwargs["input"]
                self.assertGreater(kwargs["timeout"], 0)
                if args[:2] == ["version", "-o"]:
                    output = b'{"serverVersion":{"gitVersion":"v1.35.1+k3s1"}}'
                elif args[:2] == ["create", "-f"]:
                    resource = json.loads(data)
                    self.assertEqual("Namespace", resource["kind"])
                    self.assertEqual(instance.namespace, resource["metadata"]["name"])
                    output = b'{"metadata":{"uid":"namespace-uid"}}'
                elif args[:3] == ["get", "namespace", instance.namespace]:
                    output = json.dumps({"metadata": {
                        "uid": "namespace-uid", "labels": {drill.OWNER: instance.token}}}).encode()
                elif args[0] == "delete":
                    self.assertEqual(["delete", "--raw=/api/v1/namespaces/" + instance.namespace,
                                      "-f", "-"], args)
                    self.assertEqual({"uid": "namespace-uid"}, json.loads(data)["preconditions"])
                    output = b"{}"
                elif args[0] == "wait":
                    self.assertIn("namespace/" + instance.namespace, args)
                    output = b""
                else:
                    self.assertEqual(["-n", instance.namespace], args[:2])
                    args = args[2:]
                    if args[0] == "create":
                        resource = json.loads(data)
                        self.assertEqual(instance.namespace, resource["metadata"]["namespace"])
                        created_resources.append(resource)
                        output = b"{}"
                    elif args[0] == "wait":
                        self.assertIn("pod/source", args)
                        self.assertIn("pod/restore", args)
                        output = b""
                    elif args[:2] == ["get", "pod"]:
                        self.assertIn(args[2], ("source", "restore"))
                        output = json.dumps({
                            "metadata": {"uid": args[2] + "-uid"},
                            "status": {"containerStatuses": [
                                {"name": "postgres", "image": drill.POSTGRES_IMAGE,
                                 "imageID": "docker-pullable://postgres@sha256:test"}]},
                        }).encode()
                    else:
                        self.assertEqual("exec", args[0])
                        pod = args[2] if args[1] == "-i" else args[1]
                        self.assertIn(pod, ("source", "restore"))
                        self.assertIn("postgres", args)
                        executable = args[args.index("--") + 1]
                        if executable == "psql":
                            sql = data.decode()
                            if sql in ("CREATE DATABASE schema_reference;", "known-schema"):
                                self.assertEqual("source", pod)
                                database = args[args.index("-d") + 1]
                                self.assertEqual("schema_reference" if sql == "known-schema" else "drill",
                                                 database)
                                output = b""
                            elif sql.startswith("BEGIN;"):
                                self.assertEqual("source", pod)
                                self.assertEqual(drill.fixture_sql(), sql)
                                output = b""
                            elif sql.startswith("SHOW"):
                                output = b"18.6\n"
                            elif sql.startswith("SELECT count(*)"):
                                self.assertEqual("restore", pod)
                                output = b"0\n"
                            elif "operation_events_id_seq" in sql:
                                output = json.dumps(snapshot["sequence_state"]).encode()
                            else:
                                table = sql.split("FROM ")[-1].split()[0]
                                self.assertIn(table, drill.TABLES)
                                output = json.dumps(snapshot[table]).encode()
                        elif executable == "pg_dump":
                            if "--format=custom" in args:
                                self.assertEqual("source", pod)
                                kwargs["stdout"].write(b"known-logical-backup")
                                output = None
                            else:
                                self.assertIn("--schema-only", args)
                                output = b"known-schema"
                        else:
                            self.assertEqual("pg_restore", executable)
                            self.assertEqual("restore", pod)
                            self.assertIn("--exit-on-error", args)
                            self.assertIn("--single-transaction", args)
                            self.assertNotIn("--clean", args)
                            restored.append(data)
                            output = b""
                return subprocess.CompletedProcess(argv, 0, output, b"")

            with patch.object(drill.subprocess, "run", side_effect=command):
                instance.run()
            self.assertEqual(["NetworkPolicy", "Pod", "Pod"],
                             [resource["kind"] for resource in created_resources])
            self.assertEqual([b"known-logical-backup"], restored)
            evidence = json.loads((instance.directory / "evidence.json").read_text())
            self.assertEqual("passed", evidence["status"])
            self.assertTrue(all(evidence["verification"].values()))
            self.assertEqual("deleted-owned-namespace", evidence["cleanup"])
            self.assertEqual(hashlib.sha256(restored[0]).hexdigest(), evidence["backup_sha256"])
            for key in ("backup_seconds", "restore_seconds"):
                self.assertGreaterEqual(evidence[key], 0)
            for forbidden in ("drill-user", "shared-key", "DB_PASSWORD", "POSTGRES_PASSWORD"):
                self.assertNotIn(forbidden, json.dumps(evidence))
            self.assertEqual(0o600, (instance.directory / "fixture.dump").stat().st_mode & 0o777)
            for name in ("source-schema.sql", "restore-schema.sql"):
                self.assertEqual(b"known-schema", (instance.directory / name).read_bytes())
                self.assertEqual(0o600, (instance.directory / name).stat().st_mode & 0o777)


class BackupRestoreVerificationTests(unittest.TestCase):
    def test_known_fixture_and_complete_history_pass(self):
        source = valid_snapshot()
        results = drill.verify_restore(source, copy.deepcopy(source), b"schema", b"schema")
        self.assertTrue(all(results.values()))
        self.assertEqual(set((*drill.TABLES, "schema", "sequence_state")), set(results))

    def test_each_missing_table_record_fails(self):
        for table in drill.TABLES:
            with self.subTest(table=table), self.assertRaises(drill.DrillError):
                restored = valid_snapshot()
                restored[table].pop()
                drill.verify_restore(valid_snapshot(), restored, b"schema", b"schema")

    def test_wrong_fixture_relationship_scope_or_status_fails_even_if_source_matches(self):
        for table, field in (("environments", "team_id"), ("operations", "environment_id"),
                             ("operations", "status"), ("audit_events", "actor"),
                             ("idempotency_requests", "scope"), ("operation_events", "operation_id")):
            with self.subTest(table=table, field=field), self.assertRaises(drill.DrillError):
                snapshot = valid_snapshot()
                snapshot[table][0][field] = "wrong"
                drill.verify_restore(snapshot, snapshot, b"schema", b"schema")

    def test_any_restored_column_schema_sequence_or_flyway_change_fails(self):
        for table in (*drill.TABLES, "sequence_state", "schema"):
            with self.subTest(table=table), self.assertRaises(drill.VerificationError) as raised:
                restored = valid_snapshot()
                if table == "sequence_state":
                    restored[table]["last_value"] = 1
                elif table != "schema":
                    restored[table][0]["unexpected"] = "changed"
                drill.verify_restore(valid_snapshot(), restored, b"schema",
                                     b"different" if table == "schema" else b"schema")
            self.assertFalse(raised.exception.results[table])

    def test_failed_incomplete_or_wrong_migration_history_fails(self):
        for field, value in (("success", False), ("checksum", None), ("version", "99"),
                             ("script", "V99__unknown.sql")):
            with self.subTest(field=field), self.assertRaises(drill.DrillError):
                snapshot = valid_snapshot()
                snapshot["flyway_schema_history"][0][field] = value
                drill.verify_fixture(snapshot)


@unittest.skipUnless(os.environ.get("PLATFORM_BACKUP_RESTORE_TEST") == "true",
                     "set PLATFORM_BACKUP_RESTORE_TEST=true for the disposable logical restore drill")
class K3sBackupRestoreTests(unittest.TestCase):
    def test_logical_backup_restore(self):
        self.assertEqual(0, drill.main([
            "--confirm-disposable", "--context", os.environ.get("PLATFORM_K3S_CONTEXT", ""),
            "--artifacts", os.environ.get("PLATFORM_BACKUP_RESTORE_ARTIFACTS", ""),
        ]))
