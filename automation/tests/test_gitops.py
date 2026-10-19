"""Offline GitOps contract checks and an opt-in, externally driven rollback drill."""

import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import time
import unittest
import urllib.parse


ROOT = Path(__file__).resolve().parents[2]
GITOPS = ROOT / "infra/gitops"


def render(path):
    return subprocess.check_output(
        ["kubectl", "kustomize", str(path)], text=True, timeout=120)


@unittest.skipUnless(shutil.which("kubectl"), "kubectl is required to render Kustomize")
class GitOpsConfigurationTests(unittest.TestCase):
    def test_target_excludes_bootstrap_and_environment_resources(self):
        rendered = render(GITOPS / "local-k3s")
        self.assertEqual(3, rendered.count("kind: Deployment\n"))
        self.assertEqual(3, rendered.count("kind: Service\n"))
        for kind in ("Namespace", "PersistentVolumeClaim", "Secret", "ServiceAccount",
                     "ClusterRole", "ClusterRoleBinding", "Application"):
            self.assertNotIn(f"kind: {kind}\n", rendered)
        self.assertNotIn("env-", rendered)
        self.assertEqual(2, rendered.count("type: Recreate"))
        self.assertEqual(2, rendered.count("imagePullPolicy: Never"))
        self.assertIn("Prune=false,Delete=false", rendered)

    def test_bootstrap_owns_storage_and_worker_but_not_deployments(self):
        rendered = render(GITOPS / "bootstrap")
        for kind in ("Namespace", "PersistentVolumeClaim", "ServiceAccount",
                     "ClusterRole", "ClusterRoleBinding", "AppProject", "Application"):
            self.assertIn(f"kind: {kind}\n", rendered)
        self.assertNotRegex(rendered, r"(?m)^kind: Deployment$")
        self.assertNotRegex(rendered, r"(?m)^kind: Service$")
        self.assertIn("storage: 5Gi", rendered)
        self.assertIn("storageClassName: local-path", rendered)

    def test_reconciliation_and_permissions_are_bounded(self):
        rendered = render(GITOPS / "bootstrap")
        self.assertIn("path: infra/gitops/local-k3s", rendered)
        self.assertIn("targetRevision: main", rendered)
        self.assertIn("prune: false", rendered)
        self.assertIn("selfHeal: true", rendered)
        self.assertNotIn("resources-finalizer", rendered)
        self.assertNotIn("CreateNamespace", rendered)
        application = (GITOPS / "bootstrap/application.yaml").read_text()
        self.assertIn("clusterResourceWhitelist: []", application)
        for kind in ("Secret", "Namespace", "PersistentVolumeClaim", "Role", "ClusterRole"):
            self.assertNotIn(f"kind: {kind}\n", application)
        access = (GITOPS / "bootstrap/access.yaml").read_text()
        role = access.split("---", 1)[0]
        for denied in ("delete", "secrets", "persistentvolumeclaims", "namespaces", "*"):
            self.assertNotIn(denied, role)
        self.assertIn("namespace: platform-lab", role)
        self.assertIn('clusterResources: "false"', access)

    def test_controller_is_commit_pinned_and_namespaced(self):
        text = (GITOPS / "controller/kustomization.yaml").read_text()
        self.assertIn("a5038b0e2bbac1715754380ae0084df8e3d5b48c", text)
        self.assertIn("/manifests/namespace-install.yaml", text)
        self.assertNotIn("/manifests/install.yaml", text)
        self.assertIn("resource.respectRBAC: strict", text)

    def test_catalog_and_selected_images_are_immutable(self):
        def unique_keys(pairs):
            keys = [key for key, _ in pairs]
            self.assertEqual(len(keys), len(set(keys)), "Duplicate release catalog keys")
            return dict(pairs)

        catalog = json.loads((GITOPS / "local-k3s/releases.json").read_text(),
                             object_pairs_hook=unique_keys)
        self.assertEqual("linux/amd64", catalog["platform"])
        self.assertEqual(["gitops-a", "gitops-b"],
                         [release["releaseId"] for release in catalog["frontend"]])
        releases = [catalog["backend"], *catalog["frontend"]]
        self.assertEqual(1, len({release["sourceRevision"] for release in releases}))
        self.assertEqual(3, len({release["image"] for release in releases}))
        for release in releases:
            self.assertRegex(release["sourceRevision"], r"^[0-9a-f]{40}$")
            self.assertRegex(release["image"],
                             r"^docker.io/library/platform-lab-(backend|frontend)@sha256:[0-9a-f]{64}$")
            self.assertRegex(release["configDigest"], r"^sha256:[0-9a-f]{64}$")
            self.assertRegex(release["archiveSHA256"], r"^[0-9a-f]{64}$")
            self.assertEqual(release["archive"], Path(release["archive"]).name)
        rendered = render(GITOPS / "local-k3s")
        backend_images = re.findall(r"(?m)^\s+(?:- )?image: (.*platform-lab-backend.*)$", rendered)
        current_backend = catalog["currentBackend"]
        self.assertEqual({"releaseId", "image"}, set(current_backend))
        self.assertEqual("pr53-ca2e5c9", current_backend["releaseId"])
        self.assertEqual(
            "docker.io/library/platform-lab-backend@sha256:"
            "8e601480ced03a127d658006cccdffce6c46817463f799b67de98d64661b2811",
            current_backend["image"])
        self.assertEqual([current_backend["image"]], backend_images)
        self.assertNotIn("image: " + catalog["backend"]["image"], rendered)
        frontend_images = re.findall(r"(?m)^\s+(?:- )?image: (.*platform-lab-frontend.*)$", rendered)
        self.assertEqual(1, len(frontend_images))
        current = catalog["currentFrontend"]
        self.assertEqual("k3s-v2", current["releaseId"])
        self.assertEqual("0b89a5a64e3893d671280b85eb05349b9c3dd5bd", current["sourceRevision"])
        self.assertEqual(
            "docker.io/library/platform-lab-frontend@sha256:"
            "9f6ae09bcc76b786ced130ef2e11d11976fe4e97bd62dfd99934dfdc8ee6bb06",
            current["image"])
        self.assertEqual(current["image"], frontend_images[0])

    @unittest.skipUnless(os.environ.get("PLATFORM_GITOPS_ARTIFACTS"),
                         "set PLATFORM_GITOPS_ARTIFACTS to verify retained OCI archives")
    def test_retained_archives_match_catalog_and_frontend_identity(self):
        import tarfile

        catalog = json.loads((GITOPS / "local-k3s/releases.json").read_text())
        for release in [catalog["backend"], *catalog["frontend"]]:
            path = Path(os.environ["PLATFORM_GITOPS_ARTIFACTS"]) / release["archive"]
            with path.open("rb") as stream:
                self.assertEqual(release["archiveSHA256"], hashlib.file_digest(stream, "sha256").hexdigest())
            with tarfile.open(path) as archive:
                index = json.load(archive.extractfile("index.json"))
                self.assertEqual(1, len(index["manifests"]))
                digest = release["image"].split("@")[1]
                self.assertEqual(digest, index["manifests"][0]["digest"])
                self.assertEqual({"architecture": "amd64", "os": "linux"},
                                 index["manifests"][0]["platform"])
                manifest_bytes = archive.extractfile("blobs/sha256/" + digest.split(":")[1]).read()
                self.assertEqual(digest, "sha256:" + hashlib.sha256(manifest_bytes).hexdigest())
                manifest = json.loads(manifest_bytes)
                self.assertEqual(release["configDigest"], manifest["config"]["digest"])
                config_bytes = archive.extractfile(
                    "blobs/sha256/" + release["configDigest"].split(":")[1]).read()
                self.assertEqual(release["configDigest"], "sha256:" + hashlib.sha256(config_bytes).hexdigest())
                if "releaseId" in release:
                    config = json.loads(config_bytes)
                    self.assertEqual(release["sourceRevision"],
                                     config["config"]["Labels"]["org.opencontainers.image.revision"])
                    layer = manifest["layers"][-1]["digest"].split(":")[1]
                    with tarfile.open(fileobj=archive.extractfile("blobs/sha256/" + layer),
                                      mode="r|gz") as content:
                        identity = None
                        for entry in content:
                            if entry.name.lstrip("./") == "usr/share/nginx/html/release.json":
                                identity = json.load(content.extractfile(entry))
                        self.assertEqual({"sourceRevision": release["sourceRevision"],
                                          "releaseId": release["releaseId"]}, identity)


@unittest.skipUnless(os.environ.get("PLATFORM_GITOPS_ACCESS_TEST") == "true",
                     "set PLATFORM_GITOPS_ACCESS_TEST=true for live bootstrap RBAC checks")
class GitOpsAccessTests(unittest.TestCase):
    def test_controller_and_server_cannot_delete_or_manage_environments(self):
        context = os.environ.get("PLATFORM_K3S_CONTEXT")
        if not context:
            raise RuntimeError("PLATFORM_K3S_CONTEXT must select the disposable cluster")
        for account in ("argocd-application-controller", "argocd-server"):
            for verb, resource, namespace, allowed in (
                ("patch", "deployments.apps", "platform-lab", True),
                ("list", "pods", "platform-lab", True),
                ("create", "configmaps", "platform-lab", True),
                ("delete", "deployments.apps", "platform-lab", False),
                ("get", "secrets", "platform-lab", False),
                ("delete", "persistentvolumeclaims", "platform-lab", False),
                ("delete", "namespaces", None, False),
                ("create", "clusterroles.rbac.authorization.k8s.io", None, False),
                ("patch", "deployments.apps", "env-gitops-access-check", False),
            ):
                with self.subTest(account=account, verb=verb, resource=resource, namespace=namespace):
                    command = ["kubectl", "--context", context, "auth", "can-i", verb, resource,
                               "--as", "system:serviceaccount:argocd:" + account]
                    if namespace:
                        command.extend(["-n", namespace])
                    result = subprocess.run(command, text=True, capture_output=True, timeout=30)
                    self.assertEqual("yes" if allowed else "no", result.stdout.strip())
                    self.assertEqual(0 if allowed else 1, result.returncode)


@unittest.skipUnless(os.environ.get("PLATFORM_GITOPS_TEST") == "true",
                     "set PLATFORM_GITOPS_TEST=true for the externally driven rollback drill")
class GitOpsRollbackTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        required = ("PLATFORM_K3S_CONTEXT", "PLATFORM_K3S_API_URL", "PLATFORM_K3S_CA_FILE",
                    "PLATFORM_K3S_OPERATOR_USERNAME", "PLATFORM_K3S_OPERATOR_PASSWORD",
                    "PLATFORM_GITOPS_ENVIRONMENT_ID", "PLATFORM_GITOPS_EVIDENCE")
        if any(not os.environ.get(name) for name in required):
            raise RuntimeError("GitOps drill requires explicit cluster, HTTPS, credentials, fixture and evidence path")
        cls.context = os.environ["PLATFORM_K3S_CONTEXT"]
        cls.url = urllib.parse.urlsplit(os.environ["PLATFORM_K3S_API_URL"])
        if (cls.url.scheme != "https" or not cls.url.hostname or cls.url.username
                or cls.url.password or cls.url.path not in ("", "/") or cls.url.query or cls.url.fragment):
            raise RuntimeError("PLATFORM_K3S_API_URL must be an HTTPS origin without credentials")
        cls.tls = ssl.create_default_context(cafile=os.environ["PLATFORM_K3S_CA_FILE"])
        cls.authorization = "Basic " + base64.b64encode(
            (os.environ["PLATFORM_K3S_OPERATOR_USERNAME"] + ":" +
             os.environ["PLATFORM_K3S_OPERATOR_PASSWORD"]).encode()).decode()
        cls.environment_id = os.environ["PLATFORM_GITOPS_ENVIRONMENT_ID"]
        if not re.fullmatch(r"[0-9a-f-]{36}", cls.environment_id):
            raise RuntimeError("Fixture must be an environment UUID")
        cls.evidence = Path(os.environ["PLATFORM_GITOPS_EVIDENCE"]).resolve()
        if not cls.evidence.is_relative_to(Path("/tmp")) or cls.evidence.is_relative_to(ROOT):
            raise RuntimeError("Evidence must be outside the checkout, under /tmp")
        cls.releases = json.loads((GITOPS / "local-k3s/releases.json").read_text())

    def kubectl(self, *arguments):
        return subprocess.check_output(
            ["kubectl", "--context", self.context, *arguments], text=True, timeout=30)

    def resource(self, kind, name, namespace="platform-lab"):
        return json.loads(self.kubectl("-n", namespace, "get", kind, name, "-o", "json"))

    def request(self, path, authenticated=True):
        connection = http.client.HTTPSConnection(
            self.url.hostname, self.url.port or 443, context=self.tls, timeout=15)
        try:
            connection.request("GET", path,
                               headers={"Authorization": self.authorization} if authenticated else {})
            response = connection.getresponse()
            self.assertEqual(200, response.status)
            return json.loads(response.read())
        finally:
            connection.close()

    def application_at(self, digest, previous_revision=None):
        application = self.resource("application", "platform-local", "argocd")
        spec = application["spec"]
        self.assertEqual("infra/gitops/local-k3s", spec["source"]["path"])
        self.assertEqual("https://github.com/dcfaight/internal-developer-platform.git",
                         spec["source"]["repoURL"])
        self.assertNotIn("kustomize", spec["source"], "No out-of-Git image overrides allowed")
        status = application.get("status", {})
        revision = status.get("sync", {}).get("revision", "")
        operation = status.get("operationState", {})
        if (status.get("sync", {}).get("status") != "Synced"
                or status.get("health", {}).get("status") != "Healthy"
                or operation.get("phase") != "Succeeded"
                or operation.get("syncResult", {}).get("revision") != revision
                or not re.fullmatch(r"[0-9a-f]{40}", revision)
                or revision == previous_revision):
            return None
        deployment = self.resource("deployment", "frontend")
        if deployment["spec"]["template"]["spec"]["containers"][0]["image"] != digest:
            return None
        generation = deployment["metadata"]["generation"]
        if (deployment.get("status", {}).get("observedGeneration", 0) < generation
                or deployment["status"].get("updatedReplicas") != 1
                or deployment["status"].get("availableReplicas") != 1):
            return None
        pods = json.loads(self.kubectl("-n", "platform-lab", "get", "pods", "-l",
                                      "app=frontend", "-o", "json"))["items"]
        active = [pod for pod in pods if not pod["metadata"].get("deletionTimestamp")]
        if len(active) != 1:
            return None
        containers = active[0].get("status", {}).get("containerStatuses", [])
        if len(containers) != 1 or not containers[0].get("ready"):
            return None
        image_id = containers[0].get("imageID", "")
        self.assertIn(digest.split("@", 1)[1], image_id)
        return {"gitRevision": revision, "image": digest, "imageID": image_id,
                "source": spec["source"],
                "frontendUID": deployment["metadata"]["uid"]}

    def wait_for_release(self, release, previous_revision=None):
        deadline = time.monotonic() + 1200
        print(f"Waiting for Git reconciliation of {release['releaseId']}", flush=True)
        while time.monotonic() < deadline:
            observed = self.application_at(release["image"], previous_revision)
            if observed:
                identity = self.request("/release.json", authenticated=False)
                if identity == {"sourceRevision": release["sourceRevision"],
                                "releaseId": release["releaseId"]}:
                    return observed
            time.sleep(5)
        self.fail("Timed out waiting for a healthy Git-sourced release; inspect Argo CD")

    def persistence(self):
        path = "/api/v1/environments/" + self.environment_id
        environment = self.request(path)
        self.assertEqual("READY", environment["status"])
        history = self.request(path + "/operations")
        readiness = json.loads(self.kubectl(
            "-n", "platform-lab", "exec", "deployment/backend", "--",
            "curl", "--fail", "--silent", "http://127.0.0.1:8080/actuator/health/readiness"))
        self.assertEqual("UP", readiness["status"])
        backend = self.resource("deployment", "backend")
        postgres = self.resource("deployment", "postgres")
        pods = json.loads(self.kubectl("-n", "platform-lab", "get", "pods", "-o", "json"))["items"]
        schema = self.kubectl("-n", "platform-lab", "exec", "deployment/postgres", "--",
                              "sh", "-c", 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At '
                              '-c "SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank"')
        return {
            "environment": environment, "history": history, "schema": schema,
            "backendSpec": backend["spec"], "postgresSpec": postgres["spec"],
            "backendUID": backend["metadata"]["uid"], "postgresUID": postgres["metadata"]["uid"],
            "pvcUID": self.resource("pvc", "postgres-data")["metadata"]["uid"],
            "secretUIDs": {name: self.resource("secret", name)["metadata"]["uid"]
                           for name in ("platform-secrets", "platform-tls")},
            "dataPods": sorted((pod["metadata"]["uid"],
                                [(c["name"], c["restartCount"]) for c in
                                 pod.get("status", {}).get("containerStatuses", [])])
                               for pod in pods if pod["metadata"].get("labels", {}).get("app")
                               in ("backend", "postgres")),
        }

    def test_git_upgrade_and_revert_preserve_backend_and_history(self):
        previous, candidate = self.releases["frontend"]
        baseline = self.wait_for_release(previous)
        preserved = self.persistence()
        records = [{"phase": "baseline", **baseline}]
        # The test never writes Git, patches the Application, or changes workloads.
        for phase, release in (("upgrade", candidate), ("rollback", previous)):
            observed = self.wait_for_release(release, records[-1]["gitRevision"])
            self.assertNotIn(observed["gitRevision"], [r["gitRevision"] for r in records],
                             "Rollback must be a new Git revert commit")
            self.assertEqual(baseline["source"], observed["source"],
                             "The Application source must not change during the drill")
            self.assertEqual(preserved, self.persistence())
            records.append({"phase": phase, **observed})
        self.evidence.write_text(json.dumps({
            "result": "PASS", "observedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "context": self.context, "phases": records,
            "preservedStateSHA256": hashlib.sha256(
                json.dumps(preserved, sort_keys=True).encode()).hexdigest(),
            "checks": ["API readiness", "READY environment and operation history", "Flyway history",
                       "backend/PostgreSQL specs, UIDs, pod UIDs and restart counts",
                       "PVC and external Secret UIDs", "served release identity", "runtime image digest"],
        }, indent=2) + "\n")


class GitOpsObservationTests(unittest.TestCase):
    def setUp(self):
        from unittest.mock import Mock

        self.drill = GitOpsRollbackTests("test_git_upgrade_and_revert_preserve_backend_and_history")
        self.image = "docker.io/library/platform-lab-frontend@sha256:" + "a" * 64
        self.revision = "b" * 40
        self.application = {
            "spec": {"source": {
                "repoURL": "https://github.com/dcfaight/internal-developer-platform.git",
                "path": "infra/gitops/local-k3s", "targetRevision": "main"}},
            "status": {
                "sync": {"status": "Synced", "revision": self.revision},
                "health": {"status": "Healthy"},
                "operationState": {"phase": "Succeeded", "syncResult": {"revision": self.revision}},
            },
        }
        self.deployment = {
            "metadata": {"generation": 2, "uid": "frontend-uid"},
            "spec": {"template": {"spec": {"containers": [{"image": self.image}]}}},
            "status": {"observedGeneration": 2, "updatedReplicas": 1, "availableReplicas": 1},
        }
        self.pods = {"items": [{
            "metadata": {},
            "status": {"containerStatuses": [{"ready": True, "imageID": self.image}]},
        }]}
        self.drill.resource = Mock(side_effect=lambda kind, *_: (
            self.application if kind == "application" else self.deployment))
        self.drill.kubectl = Mock(side_effect=lambda *_: json.dumps(self.pods))

    def test_healthy_commit_and_runtime_digest_are_observed(self):
        observed = self.drill.application_at(self.image)
        self.assertEqual(self.revision, observed["gitRevision"])
        self.assertEqual(self.image, observed["imageID"])
        self.assertEqual("main", observed["source"]["targetRevision"])

    def test_stale_sync_health_or_rollout_is_not_accepted(self):
        self.assertIsNone(self.drill.application_at(self.image, self.revision))
        self.application["status"]["operationState"]["syncResult"]["revision"] = "c" * 40
        self.assertIsNone(self.drill.application_at(self.image))
        self.application["status"]["operationState"]["syncResult"]["revision"] = self.revision
        self.application["status"]["health"]["status"] = "Progressing"
        self.assertIsNone(self.drill.application_at(self.image))
        self.application["status"]["health"]["status"] = "Healthy"
        self.deployment["status"]["observedGeneration"] = 1
        self.assertIsNone(self.drill.application_at(self.image))

    def test_unready_pods_and_wrong_runtime_digest_cannot_prove_rollback(self):
        self.pods["items"][0]["status"]["containerStatuses"][0]["ready"] = False
        self.assertIsNone(self.drill.application_at(self.image))
        container = self.pods["items"][0]["status"]["containerStatuses"][0]
        container["ready"] = True
        container["imageID"] = "docker.io/library/platform-lab-frontend@sha256:" + "c" * 64
        with self.assertRaises(AssertionError):
            self.drill.application_at(self.image)

    def test_out_of_git_image_overrides_are_rejected(self):
        self.application["spec"]["source"]["kustomize"] = {"images": [self.image]}
        with self.assertRaises(AssertionError):
            self.drill.application_at(self.image)
