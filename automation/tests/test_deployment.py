"""Opt-in smoke tests for the real deploy images; no extra Python dependencies."""

import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import ssl
import subprocess
import tempfile
import unittest
import urllib.error
import urllib.request


ROOT = Path(__file__).resolve().parents[2]


class DeploymentConfigurationTests(unittest.TestCase):
    def test_configuration_fails_closed_and_keeps_internal_ports_private(self):
        environment = {key: value for key, value in os.environ.items()
                       if key not in {"POSTGRES_PASSWORD", "DB_PASSWORD", "DEPLOY_OPERATOR_USERNAME",
                                      "DEPLOY_OPERATOR_PASSWORD_HASH", "TLS_CERT_FILE", "TLS_KEY_FILE"}}
        command = ["docker", "compose", "--env-file", "/dev/null",
                   "--file", str(ROOT / "compose.deploy.yaml"), "config", "--format", "json"]
        values = {
            "POSTGRES_PASSWORD": "configuration-test-only",
            "DB_PASSWORD": "runtime-configuration-test-only",
            "DEPLOY_OPERATOR_USERNAME": "config-test",
            "DEPLOY_OPERATOR_PASSWORD_HASH": "configuration-test-only",
            "TLS_CERT_FILE": "/tmp/config-test.crt",
            "TLS_KEY_FILE": "/tmp/config-test.key",
        }
        for missing in values:
            with self.subTest(missing=missing):
                result = subprocess.run(command, env={**environment, **values, missing: ""},
                                        capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(missing, result.stderr)
        configuration = json.loads(subprocess.check_output(
            command, env={**environment, **values}, text=True))
        services = configuration["services"]
        for service in ("backend", "postgres"):
            self.assertFalse(services[service].get("ports"))
        self.assertEqual("127.0.0.1", services["frontend"]["ports"][0]["host_ip"])
        for service in ("backend", "frontend"):
            self.assertTrue(services[service]["read_only"])
            self.assertEqual(["ALL"], services[service]["cap_drop"])
        for network in ("api", "database"):
            self.assertTrue(configuration["networks"][network]["internal"])
        self.assertEqual({"api", "edge"}, set(services["frontend"]["networks"]))
        self.assertNotIn("edge", services["backend"]["networks"])
        self.assertNotIn("edge", services["postgres"]["networks"])
        runtime = services["backend"]["environment"]
        self.assertEqual("platform_runtime", runtime["DB_USERNAME"])
        self.assertEqual(values["DB_PASSWORD"], runtime["DB_PASSWORD"])
        self.assertNotIn("POSTGRES_PASSWORD", runtime)
        self.assertNotIn("MIGRATION_DB_PASSWORD", runtime)


@unittest.skipUnless(os.environ.get("PLATFORM_DEPLOY_TEST") == "true",
                     "set PLATFORM_DEPLOY_TEST=true to build and run the deploy stack")
class DeploymentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="platform-deploy-")
        cls.addClassCleanup(cls.directory.cleanup)
        directory = Path(cls.directory.name)
        certificate = directory / "tls.crt"
        key = directory / "tls.key"
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
            "-keyout", str(key), "-out", str(certificate), "-days", "1",
            "-subj", "/CN=localhost",
            "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1",
        ], check=True, capture_output=True)
        # Only this disposable test key is world-readable for the non-root container.
        key.chmod(0o444)
        cls.password = secrets.token_hex(24)
        salt = secrets.token_bytes(16)
        password_hash = "{pbkdf2@SpringSecurity_v5_8}" + (
            salt + hashlib.pbkdf2_hmac("sha256", cls.password.encode(), salt, 310000, 32)
        ).hex()
        cls.environment = {
            **os.environ,
            "POSTGRES_PASSWORD": secrets.token_hex(24),
            "DB_PASSWORD": secrets.token_hex(24),
            "MIGRATION_DB_PASSWORD": secrets.token_hex(24),
            "DEPLOY_OPERATOR_USERNAME": "deployment-test",
            "DEPLOY_OPERATOR_PASSWORD_HASH": password_hash,
            "TLS_CERT_FILE": str(certificate),
            "TLS_KEY_FILE": str(key),
            "HTTPS_PORT": os.environ.get("HTTPS_PORT", "18443"),
        }
        cls.project = "platform-deploy-test-" + secrets.token_hex(4)
        cls.addClassCleanup(cls.compose, "down", "--volumes", "--remove-orphans")
        cls.compose("config", "--quiet")
        cls.compose("build", timeout=900)
        try:
            cls.compose("up", "-d", "--wait", "postgres", timeout=120)
            cls.sql((ROOT / "backend/database/bootstrap.sql").read_text())
            # Generated values travel over stdin, never argv or Compose logs.
            cls.sql(
                "ALTER ROLE platform_migration LOGIN PASSWORD '"
                + cls.environment["MIGRATION_DB_PASSWORD"] + "';\n"
                + "ALTER ROLE platform_runtime LOGIN PASSWORD '"
                + cls.environment["DB_PASSWORD"] + "';\n")
            cls.compose("run", "--rm", "migration", timeout=120)
            cls.sql((ROOT / "backend/database/runtime-grants.sql").read_text())
            cls.sql("ALTER ROLE platform_migration NOLOGIN;\n"
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    "WHERE usename = 'platform_migration';\n")
            cls.compose("up", "-d", "--wait", "--wait-timeout", "180", timeout=240)
        except RuntimeError:
            print(cls.compose("logs", "--no-color", "--tail", "100"))
            raise
        cls.tls = ssl.create_default_context(cafile=str(certificate))
        cls.url = "https://localhost:" + cls.environment["HTTPS_PORT"]

    @classmethod
    def sql(cls, statement):
        result = subprocess.run([
            "docker", "compose", "--project-name", cls.project,
            "--file", str(ROOT / "compose.deploy.yaml"), "exec", "-T", "postgres",
            "sh", "-c", 'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
        ], cwd=ROOT, env=cls.environment, input=statement, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        if result.returncode:
            raise RuntimeError("Disposable database setup failed (details suppressed).")

    @classmethod
    def compose(cls, *arguments, timeout=60):
        result = subprocess.run([
            "docker", "compose", "--project-name", cls.project,
            "--file", str(ROOT / "compose.deploy.yaml"), *arguments,
        ], cwd=ROOT, env=cls.environment, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        if result.returncode:
            raise RuntimeError(f"Compose {arguments[0]} failed:\n{result.stdout}")
        return result.stdout

    def request(self, path, method="GET", body=None, authenticated=None, headers=None):
        headers = dict(headers or {})
        if authenticated is None:
            authenticated = path.startswith("/api/v1/")
        if authenticated:
            token = base64.b64encode(
                ("deployment-test:" + self.password).encode()).decode()
            headers["Authorization"] = "Basic " + token
        if body is not None:
            body = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.url + path, data=body,
                                         method=method, headers=headers)
        try:
            response = urllib.request.urlopen(request, context=self.tls, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.headers, response.read()

    def test_tls_static_assets_and_private_management(self):
        status, headers, body = self.request("/")
        self.assertEqual(200, status)
        self.assertIn(b'<div id="root">', body)
        self.assertIn("max-age=", headers["Strict-Transport-Security"])
        self.assertEqual("nosniff", headers["X-Content-Type-Options"])
        self.assertEqual(200, self.request("/api/v1/environments")[0])
        self.assertEqual(401, self.request("/api/v1/environments", authenticated=False)[0])
        self.assertEqual(404, self.request("/actuator/health/readiness")[0])
        self.assertEqual(404, self.request("/nginx.conf")[0])

    def test_authenticated_creation_replay_and_delete_guards(self):
        payload = {"name": "Deployment smoke", "templateId": "sample-service", "ttlHours": 1}
        headers = {"Idempotency-Key": secrets.token_hex(16)}
        path = "/api/v1/environments"
        self.assertEqual(401, self.request(path, "POST", payload, False, headers)[0])
        self.assertEqual(401, self.request("/api/v1/me", authenticated=False)[0])
        status, _, body = self.request(path, "POST", payload, True, headers)
        self.assertEqual(202, status)
        created = json.loads(body)
        status, _, replay = self.request(path, "POST", payload, True, headers)
        self.assertEqual(202, status)
        self.assertEqual(created, json.loads(replay))
        environment_path = path + "/" + created["environmentId"]
        self.assertEqual(200, self.request(environment_path)[0])
        for read_path in (environment_path, environment_path + "/operations",
                          "/api/v1/operations/" + created["operationId"],
                          "/api/v1/operations/metrics"):
            self.assertEqual(401, self.request(read_path, authenticated=False)[0])
            self.assertEqual(200, self.request(read_path)[0])
        self.assertEqual(401, self.request(environment_path, "DELETE", authenticated=False)[0])
        # The API-only stack leaves creation pending, so deletion must still reject it.
        self.assertEqual(409, self.request(environment_path, "DELETE", authenticated=True)[0])
        self.assertEqual(409, self.request(
            environment_path + "?expiredBefore=2020-01-01T00:00:00Z",
            "DELETE", authenticated=True)[0])

    def test_runtime_restrictions_and_internal_probes(self):
        for service, user in (("backend", "10001:10001"), ("frontend", "101:101")):
            container = self.compose("ps", "-q", service).strip()
            inspection = json.loads(subprocess.check_output(
                ["docker", "inspect", container], text=True))[0]
            self.assertEqual(user, inspection["Config"]["User"])
            runtime = inspection["HostConfig"]
            self.assertTrue(runtime["ReadonlyRootfs"])
            self.assertIn("ALL", [cap.upper() for cap in runtime["CapDrop"]])
            self.assertIn("no-new-privileges:true", runtime["SecurityOpt"])
            self.assertGreater(runtime["Memory"], 0)
            self.assertGreater(runtime["PidsLimit"], 0)
            self.assertEqual("healthy", inspection["State"]["Health"]["Status"])
        for service in ("backend", "postgres"):
            container = self.compose("ps", "-q", service).strip()
            inspection = json.loads(subprocess.check_output(
                ["docker", "inspect", container], text=True))[0]
            self.assertFalse(any(inspection["HostConfig"]["PortBindings"].values()))
        for probe in ("liveness", "readiness"):
            health = self.compose("exec", "-T", "backend", "curl", "--fail", "--silent",
                                 "http://127.0.0.1:8080/actuator/health/" + probe)
            self.assertEqual({"status": "UP"}, json.loads(health))


if __name__ == "__main__":
    unittest.main()
