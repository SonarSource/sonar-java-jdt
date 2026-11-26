#!/usr/bin/env python3
"""Maintain Eclipse replacement sources using an ordered, history-independent patch series."""

import argparse
import base64
import difflib
import hashlib
import http.client
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
CORE = "org.eclipse.jdt.core"
MANIFEST = "patches/upstream.json"
SERIES = "patches/series"
VERSION = re.compile(r"\d+\.\d+\.\d+")
PATCH_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*\.patch")
CENTRAL = "https://repo.maven.apache.org/maven2/"
RETRY_DELAYS = (1, 2, 4)  # seconds to wait before each retry of a failed download


class PatchError(Exception):
    pass


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, indent=2) + "\n").encode()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def safe_path(value):
    path = PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts or str(path) != value or not path.parts:
        raise PatchError(f"Invalid relative path: {value}")
    return value


def read_series(directory):
    names = [line.split("#", 1)[0].strip()
             for line in (directory / "series").read_text(encoding="utf-8").splitlines()]
    names = [name for name in names if name]
    if any(not PATCH_NAME.fullmatch(name) for name in names) or len(names) != len(set(names)):
        raise PatchError("Patch series contains an invalid or duplicate filename")
    actual = {path.name for path in directory.glob("*.patch")}
    if set(names) != actual:
        raise PatchError(f"Patch series/file mismatch: {sorted(set(names) ^ actual)}")
    return names


def change_series(directory, *, add=None, remove=None):
    """Edit patch entries while preserving comments and formatting on other lines."""
    path = directory / "series"
    data = path.read_bytes()
    if remove is not None:
        data = "".join(line for line in data.decode("utf-8").splitlines(keepends=True)
                       if line.split("#", 1)[0].strip() != remove).encode("utf-8")
    if add is not None:
        if data and not data.endswith((b"\n", b"\r")):
            data += b"\n"
        data += add.encode("utf-8") + b"\n"
    path.write_bytes(data)


def validate_manifest(manifest):
    if manifest.get("schemaVersion") != 1 or set(manifest["artifacts"]) != {CORE, "ecj"}:
        raise PatchError("Unsupported upstream manifest")
    for artifact in manifest["artifacts"].values():
        if not VERSION.fullmatch(artifact["version"]):
            raise PatchError("Only published release versions are supported")
        for key in ("pomSha256", "sourcesSha256"):
            if not re.fullmatch(r"[0-9a-f]{64}", artifact[key]):
                raise PatchError(f"Invalid {key}")
    destinations = []
    for entry in manifest["files"]:
        safe_path(entry["source"])
        destination = safe_path(entry["destination"])
        if entry["artifact"] not in manifest["artifacts"] or not destination.startswith("src/main/java/org/eclipse/jdt/"):
            raise PatchError(f"Invalid replacement source: {destination}")
        destinations.append(destination)
    if not destinations or len(destinations) != len(set(destinations)):
        raise PatchError("Replacement source list is empty or contains duplicates")


def select_repository(url=None, environment=None):
    """Pick the remote Maven repository and, when it is the CI-provided one, its credentials.

    CI (SonarSource build-maven) exports SONARSOURCE_REPOSITORY_URL, the repox repository used as the Maven mirror,
    with ARTIFACTORY_ACCESS_TOKEN (and ARTIFACTORY_USERNAME). Credentials are only ever sent over HTTPS to that host.
    """
    environment = os.environ if environment is None else environment
    configured = environment.get("SONARSOURCE_REPOSITORY_URL", "")
    url = url or configured or CENTRAL
    url = url if url.endswith("/") else url + "/"
    token = environment.get("ARTIFACTORY_ACCESS_TOKEN", "")
    if not token or not configured or urlsplit(url).scheme != "https" or urlsplit(url).netloc != urlsplit(configured).netloc:
        return url, None
    username = environment.get("ARTIFACTORY_USERNAME", "")
    if username:
        return url, "Basic " + base64.b64encode(f"{username}:{token}".encode()).decode()
    return url, "Bearer " + token


def download(url, authorization=None):
    """Download with retries on transient failures (network errors, timeouts, HTTP 429 and 5xx)."""
    request = urllib.request.Request(url)
    if authorization:
        # Unredirected: never forward credentials if the repository redirects to another host.
        request.add_unredirected_header("Authorization", authorization)
    for delay in (*RETRY_DELAYS, None):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code != 429 and error.code < 500:
                raise PatchError(f"Cannot download {url}: HTTP {error.code}") from error
            failure = f"HTTP {error.code}"
        except (OSError, http.client.HTTPException) as error:
            failure = str(error)
        if delay is None:
            raise PatchError(f"Cannot download {url} after {len(RETRY_DELAYS) + 1} attempts: {failure}")
        time.sleep(delay)


class Artifacts:
    def __init__(self, repository, downloads, offline=False, remote=CENTRAL, authorization=None):
        self.repository = repository
        self.downloads = downloads
        self.offline = offline
        self.remote = remote
        self.authorization = authorization

    @staticmethod
    def relative(artifact, version, suffix):
        return Path("org/eclipse/jdt") / artifact / version / f"{artifact}-{version}{suffix}"

    def get(self, artifact, version, suffix):
        relative = self.relative(artifact, version, suffix)
        for directory in (self.repository, self.downloads):
            path = directory / relative
            if path.is_file():
                return path.read_bytes()
        if self.offline:
            raise PatchError(f"Missing cached artifact: {relative} (offline mode)")
        data = download(self.remote + relative.as_posix(), self.authorization)
        target = self.downloads / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        return data

    def verify(self, artifact, version, suffix, data):
        """Check data (possibly from a local cache) against the SHA-1 published by the remote repository."""
        relative = self.relative(artifact, version, suffix)
        published = download(self.remote + relative.as_posix() + ".sha1", self.authorization).decode("ascii", "replace").split()
        if not published or published[0].lower() != hashlib.sha1(data).hexdigest():
            raise PatchError(f"{relative} does not match the SHA-1 published by {self.remote}")


def make_artifacts(args, downloads):
    remote, authorization = select_repository(args.repository_url)
    return Artifacts(args.maven_repo, downloads, args.offline, remote, authorization)


def ecj_version(pom):
    project = ET.fromstring(pom)
    versions = [dependency.findtext("m:version", namespaces=NS)
                for dependency in project.findall("m:dependencies/m:dependency", NS)
                if dependency.findtext("m:groupId", namespaces=NS) == "org.eclipse.jdt"
                and dependency.findtext("m:artifactId", namespaces=NS) == "ecj"]
    if len(versions) != 1 or not VERSION.fullmatch(versions[0] or ""):
        raise PatchError("JDT Core POM must select exactly one released ECJ version")
    return versions[0]


def project_version(pom):
    project = ET.fromstring(pom)
    versions = [dependency.findtext("m:version", namespaces=NS)
                for dependency in project.findall("m:dependencyManagement/m:dependencies/m:dependency", NS)
                if dependency.findtext("m:groupId", namespaces=NS) == "org.eclipse.jdt"
                and dependency.findtext("m:artifactId", namespaces=NS) == CORE]
    if len(versions) != 1:
        raise PatchError("Project POM must manage exactly one JDT Core dependency")
    return versions[0]


def replace_version(pom, version):
    pattern = (rb"(<dependency>\s*<groupId>org\.eclipse\.jdt</groupId>\s*"
               rb"<artifactId>org\.eclipse\.jdt\.core</artifactId>\s*<version>)[^<]+(</version>)")
    updated, count = re.subn(pattern, lambda match: match[1] + version.encode() + match[2], pom)
    if count != 1 or project_version(updated) != version:
        raise PatchError("Cannot update the literal JDT Core version in pom.xml")
    return updated


def validate_shade(pom, manifest):
    """Every replaced class must be excluded from the upstream JDT jars that maven-shade-plugin merges."""
    project = ET.fromstring(pom)
    excludes = {exclude.text.strip() for exclude in project.findall(
        ".//m:plugin[m:artifactId='maven-shade-plugin']//m:filter[m:artifact='org.eclipse.jdt:*']/m:excludes/m:exclude", NS)
        if exclude.text}
    prefix, missing = "src/main/java/", []
    for entry in manifest["files"]:
        base = entry["destination"][len(prefix):].removesuffix(".java")
        missing += [required for required in (base + ".class", base + ".java") if required not in excludes]
    if missing:
        raise PatchError("pom.xml maven-shade-plugin filter for org.eclipse.jdt:* lacks excludes for replaced sources:\n  "
                         + "\n  ".join(missing))


def baseline(manifest, artifacts, version=None):
    """Verify the pinned baseline, or discover and pin a target release."""
    manifest = json.loads(json.dumps(manifest))
    if version is not None:
        if not VERSION.fullmatch(version):
            raise PatchError(f"Invalid JDT Core release version: {version}")
        if artifacts.offline:
            print("Warning: offline mode cannot verify the new baseline against the repository's published checksums.",
                  file=sys.stderr)
        core_pom = artifacts.get(CORE, version, ".pom")
        manifest["artifacts"][CORE]["version"] = version
        manifest["artifacts"]["ecj"]["version"] = ecj_version(core_pom)
    archives = {}
    for name, metadata in manifest["artifacts"].items():
        for suffix, checksum in ((".pom", "pomSha256"), ("-sources.jar", "sourcesSha256")):
            data = artifacts.get(name, metadata["version"], suffix)
            actual = digest(data)
            if version is None and actual != metadata[checksum]:
                raise PatchError(f"Checksum mismatch for {name}:{metadata['version']}{suffix}")
            if version is not None and not artifacts.offline:
                # Pinning trusts these bytes from now on: confirm them (including cached copies) against the repository.
                artifacts.verify(name, metadata["version"], suffix, data)
            metadata[checksum] = actual
            if suffix == "-sources.jar":
                archives[name] = data
            else:
                pom = ET.fromstring(data)
                if (pom.findtext("m:artifactId", namespaces=NS) != name
                        or pom.findtext("m:version", namespaces=NS) != metadata["version"]):
                    raise PatchError(f"Unexpected Maven coordinates for {name}:{metadata['version']}")
                if name == CORE:
                    selected_ecj = ecj_version(data)
    if selected_ecj != manifest["artifacts"]["ecj"]["version"]:
        raise PatchError("Pinned ECJ version does not match JDT Core's dependency")
    files = {}
    for entry in manifest["files"]:
        with zipfile.ZipFile(io.BytesIO(archives[entry["artifact"]])) as archive:
            files[entry["destination"]] = archive.read(entry["source"])
    return manifest, files


def write_tree(directory, files):
    for name, data in files.items():
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)


def read_tree(directory, paths):
    return {name: (directory / name).read_bytes() for name in paths}


def read_work(stage, paths):
    actual = {path.relative_to(stage / "work").as_posix()
              for path in (stage / "work").rglob("*") if path.is_file()}
    if actual != set(paths):
        raise PatchError(f"Staged work contains missing or unmanaged files: {sorted(actual ^ set(paths))}")
    return read_tree(stage / "work", paths)


def git_apply(directory, *arguments, text=False):
    # A workspace may be inside another checkout or launched by an IDE with
    # GIT_DIR set. Never let Git redirect application to a different worktree.
    environment = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    environment.update(GIT_CEILING_DIRECTORIES=str(directory.parent.resolve()),
                       GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
    return subprocess.run(["git", "apply", *arguments], cwd=directory, env=environment,
                          capture_output=True, text=text)


def apply_patch(directory, patch, allowed):
    # Check paths before applying: helpers and build files are maintained normally.
    stat = git_apply(directory, "--numstat", "-z", str(patch))
    if stat.returncode or not stat.stdout:
        raise PatchError(f"Invalid or empty patch {patch.name}: {stat.stderr.decode().strip()}")
    try:
        paths = [entry.split(b"\t", 2)[2].decode() for entry in stat.stdout.rstrip(b"\0").split(b"\0")]
    except (IndexError, UnicodeDecodeError) as error:
        raise PatchError(f"Unsupported patch paths in {patch.name}") from error
    if not set(paths) <= set(allowed):
        raise PatchError(f"{patch.name} changes unmanaged paths: {sorted(set(paths) - set(allowed))}")
    for flags in (["--check"], []):
        result = git_apply(directory, *flags, str(patch), text=True)
        if result.returncode:
            raise PatchError(f"{patch.name}:\n{result.stderr.strip()}")


def header(patch):
    return re.split(r"(?m)^(?:diff --git |--- a/)", patch, maxsplit=1)[0].rstrip() + "\n\n"


def make_patch(before, after, description):
    changes = []
    for name in sorted(before):
        if before[name] == after[name]:
            continue
        try:
            # Git patches delimit lines only with LF; other separators are source content.
            before_lines = io.StringIO(before[name].decode("utf-8"), newline="\n").readlines()
            after_lines = io.StringIO(after[name].decode("utf-8"), newline="\n").readlines()
        except UnicodeDecodeError as error:
            raise PatchError(f"Cannot generate patch for {name}: source is not valid UTF-8") from error
        changes.append(f"diff --git a/{name} b/{name}\n")
        for line in difflib.unified_diff(before_lines, after_lines, fromfile="a/" + name, tofile="b/" + name):
            changes.append(line if line.endswith("\n") else line + "\n\\ No newline at end of file\n")
    return (description + "".join(changes)).encode() if changes else None


def check(root, artifacts, workspace):
    manifest = read_json(root / MANIFEST)
    validate_manifest(manifest)
    if project_version((root / "pom.xml").read_bytes()) != manifest["artifacts"][CORE]["version"]:
        raise PatchError("pom.xml JDT Core version differs from patches/upstream.json")
    validate_shade((root / "pom.xml").read_bytes(), manifest)
    names = read_series(root / "patches")
    _, files = baseline(manifest, artifacts)
    workspace.mkdir(parents=True)
    write_tree(workspace, files)
    for name in names:
        apply_patch(workspace, root / "patches" / name, files)
    expected = read_tree(workspace, files)
    mismatches = [name for name in files if not (root / name).is_file() or (root / name).read_bytes() != expected[name]]
    if mismatches:
        raise PatchError("Replacement sources differ from the patch series:\n  " + "\n  ".join(mismatches)
                         + "\nUse 'edit PATCH' to maintain patches and generated sources together.")
    return manifest, files, names


def snapshot(root, files, names):
    paths = ["pom.xml", MANIFEST, SERIES, *files, *("patches/" + name for name in names)]
    return {name: digest((root / name).read_bytes()) for name in paths}


def save_state(stage, state):
    (stage / "state.json").write_bytes(json_bytes(state))


def ensure_unchanged(root, state):
    for name, expected in state["original"].items():
        if not (root / name).is_file() or digest((root / name).read_bytes()) != expected:
            raise PatchError(f"Project changed while the update was staged: {name}; start a fresh update")
    actual = {path.name for path in (root / "patches").glob("*.patch")}
    if actual != set(state["originalNames"]):
        raise PatchError("Project patch files changed while the update was staged; start a fresh update")


def publish(root, updates):
    """Prepare every write first; roll back ordinary I/O failures during publication."""
    originals, prepared = {}, {}
    try:
        for name, data in updates.items():
            path = root / name
            originals[name] = path.read_bytes() if path.exists() else None
            if data is not None:
                descriptor, temporary = tempfile.mkstemp(prefix=".ecj-", dir=path.parent)
                prepared[name] = Path(temporary)
                with os.fdopen(descriptor, "wb") as output:
                    output.write(data)
                os.chmod(temporary, path.stat().st_mode & 0o777 if path.exists() else 0o644)
        changed = []
        try:
            for name, data in updates.items():
                if data == originals[name]:
                    continue
                if data is None:
                    (root / name).unlink()
                else:
                    os.replace(prepared[name], root / name)
                changed.append(name)
        except OSError:
            for name in reversed(changed):
                if originals[name] is None:
                    (root / name).unlink()
                else:
                    (root / name).write_bytes(originals[name])
            raise
    finally:
        for temporary in prepared.values():
            temporary.unlink(missing_ok=True)


def finish(stage, state):
    root = Path(state["root"])
    ensure_unchanged(root, state)
    names = read_series(stage / "patches")
    paths = state["paths"]
    with tempfile.TemporaryDirectory(prefix="ecj-verify-") as temporary:
        verification = Path(temporary)
        write_tree(verification, read_tree(stage / "base", paths))
        for name in names:
            apply_patch(verification, stage / "patches" / name, paths)
        if read_tree(verification, paths) != read_work(stage, paths):
            raise PatchError("Staged sources do not match the refreshed patch series")
    updates = read_work(stage, paths)
    updates.update({MANIFEST: json_bytes(state["manifest"]), SERIES: (stage / SERIES).read_bytes(),
                    "pom.xml": (stage / "pom.xml").read_bytes()})
    for name in set(names) | set(state["originalNames"]):
        updates["patches/" + name] = (stage / "patches" / name).read_bytes() if name in names else None
    publish(root, updates)
    print(f"Updated JDT Core {state['manifest']['artifacts'][CORE]['version']} and {len(names)} patches. No commits created.")
    shutil.rmtree(stage, ignore_errors=True)


def refresh_pending(stage, state, drop=False):
    name = state["pending"]
    patch = stage / "patches" / name
    updated = make_patch(read_tree(stage / "before", state["paths"]),
                         read_work(stage, state["paths"]), header(patch.read_text(encoding="utf-8")))
    if updated is None:
        if not drop:
            raise PatchError(f"{name} has no staged changes. Resolve it before continuing, or explicitly use 'continue --drop WORKSPACE' to remove it.")
        patch.unlink()
        state["names"].remove(name)
        change_series(stage / "patches", remove=name)
        print(f"Removed empty patch {name}")
    else:
        if drop:
            raise PatchError("Cannot drop a patch with staged changes; restore work/ from before/ first")
        patch.write_bytes(updated)
        state["index"] += 1
    state["pending"] = None
    save_state(stage, state)


def continuation_command(stage):
    return shlex.join([sys.executable, str(Path(__file__).resolve()), "continue", str(stage.resolve())])


def advance(stage, state, edit=None, new_patch=None):
    while state["index"] < len(state["names"]):
        name = state["names"][state["index"]]
        write_tree(stage / "before", read_tree(stage / "work", state["paths"]))
        patch = stage / "patches" / name
        state["pending"] = name
        save_state(stage, state)
        if name != new_patch:
            apply_patch(stage / "work", patch, state["paths"])
        if name == edit:
            print(f"Edit replacement sources in {stage / 'work'}")
            print(f"Edit the patch description in {patch} if needed.")
            print(f"Then run: {continuation_command(stage)}")
            return
        refresh_pending(stage, state)
    finish(stage, state)


def start(root, args):
    stage = Path(tempfile.mkdtemp(prefix="ecj-update-"))
    try:
        artifacts = make_artifacts(args, stage / "downloads")
        manifest, _, names = check(root, artifacts, stage / "current")
        original = snapshot(root, [entry["destination"] for entry in manifest["files"]], names)
        target_manifest, files = baseline(manifest, artifacts, getattr(args, "version", None))
        write_tree(stage / "base", files)
        write_tree(stage / "work", files)
        shutil.copytree(root / "patches", stage / "patches")
        edit = getattr(args, "patch", None)
        new_patch = edit if edit and edit not in names else None
        if new_patch:
            if not PATCH_NAME.fullmatch(edit):
                raise PatchError("New patch must have a simple filename ending in .patch")
            names.append(edit)
            (stage / "patches" / edit).write_text(f"Subject: Describe {edit}\n\nPurpose: Explain the fix and relevant upstream context.\nTests: List relevant regression tests.\n\n", encoding="utf-8")
            change_series(stage / "patches", add=edit)
        (stage / "pom.xml").write_bytes(replace_version((root / "pom.xml").read_bytes(), target_manifest["artifacts"][CORE]["version"]))
        state = {"schemaVersion": 1, "root": str(root), "manifest": target_manifest,
                 "paths": list(files), "names": names, "index": 0, "pending": None,
                 "original": original, "originalNames": read_series(root / "patches")}
        save_state(stage, state)
        advance(stage, state, edit, new_patch)
    except Exception:
        if (stage / "state.json").exists():
            print(f"Workspace retained at {stage}. Project files were not published.", file=sys.stderr)
            print(f"Resolve any pending patch in {stage / 'work'}, then run:\n  {continuation_command(stage)}", file=sys.stderr)
        else:
            shutil.rmtree(stage)
        raise


def main(argv=None, root=ROOT):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name, help_text in (("check", "Verify the pinned patch series and committed sources"),
                            ("update", "Stage a JDT Core release and publish after all patches apply"),
                            ("edit", "Edit an existing patch, or append a new patch, in a temporary workspace")):
        command = commands.add_parser(name, help=help_text)
        command.add_argument("--offline", action="store_true", help="Use only cached Maven artifacts")
        command.add_argument("--maven-repo", type=Path, default=Path.home() / ".m2/repository")
        command.add_argument("--repository-url", help="Remote Maven repository for missing artifacts (default: "
                             "$SONARSOURCE_REPOSITORY_URL when set, as in CI, otherwise Maven Central)")
        if name == "update":
            command.add_argument("version", help="Published JDT Core version, e.g. 3.47.0")
        elif name == "edit":
            command.add_argument("patch", help="Patch filename in patches/series, or a new filename to append")
    continuation = commands.add_parser("continue", help="Refresh the pending patch from edited sources and resume")
    continuation.add_argument("workspace", type=Path)
    continuation.add_argument("--drop", action="store_true", help="Explicitly remove an empty/obsolete pending patch")
    args = parser.parse_args(argv)
    try:
        root = root.resolve()
        if args.command == "check":
            with tempfile.TemporaryDirectory(prefix="ecj-check-") as temporary:
                directory = Path(temporary)
                artifacts = make_artifacts(args, directory / "downloads")
                check(root, artifacts, directory / "work")
            print("Patch series reconstructs all committed replacement sources exactly.")
        elif args.command == "continue":
            stage = args.workspace.resolve()
            state = read_json(stage / "state.json")
            if state["schemaVersion"] != 1 or Path(state["root"]) != root:
                raise PatchError("Workspace does not belong to this project")
            ensure_unchanged(root, state)
            if state["pending"]:
                refresh_pending(stage, state, args.drop)
            elif args.drop:
                raise PatchError("There is no pending patch to drop")
            advance(stage, state)
        else:
            start(root, args)
    except (PatchError, OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
