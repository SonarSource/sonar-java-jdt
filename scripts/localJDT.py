#!/usr/bin/env python3
"""Clone, build, and run upstream tests on a local JDT development checkout."""
import argparse
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET

from common import ROOT, WorkflowError, check_jdk, maven, run, save_json, select_jdk, sha256

UPSTREAM = 'https://github.com/eclipse-jdt/eclipse.jdt.core.git'
CORE = 'org.eclipse.jdt.core'
BATCH = 'org.eclipse.jdt.core.compiler.batch'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}

def git(checkout, *arguments, check=True):
    return run(['git', *arguments], checkout, capture=True, check=check)

def git_text(checkout, *arguments):
    return git(checkout, *arguments).stdout.decode('utf-8', 'replace').strip()

def prepare_checkout(workdir, branch=None, fetch=False, upstream=UPSTREAM):
    checkout = workdir / 'eclipse.jdt.core'
    if not checkout.exists():
        if not branch:
            raise WorkflowError('Specify an upstream branch for the first clone, for example: build BETA_JAVA28')
        validate_branch(workdir, branch)
        run(['git', 'clone', '--branch', branch, upstream, str(checkout)], workdir)
        return checkout
    if fetch:
        run(['git', 'fetch', 'origin'], checkout)
    if branch:
        validate_branch(workdir, branch)
        local = git(checkout, 'show-ref', '--verify', '--quiet', 'refs/heads/' + branch, check=False)
        if local.returncode == 0:
            current = git(checkout, 'symbolic-ref', '--quiet', '--short', 'HEAD', check=False)
            if current.stdout.decode().strip() != branch:
                run(['git', 'checkout', branch], checkout)
        else:
            remote_ref = 'refs/remotes/origin/' + branch
            if git(checkout, 'show-ref', '--verify', '--quiet', remote_ref, check=False).returncode:
                run(['git', 'fetch', 'origin', f'refs/heads/{branch}:{remote_ref}'], checkout)
            run(['git', 'checkout', '-b', branch, '--track', 'origin/' + branch], checkout)
    return checkout

def validate_branch(directory, branch):
    result = git(directory, 'check-ref-format', '--branch', branch, check=False)
    if result.returncode or result.stdout.decode().strip() != branch:
        raise WorkflowError(f'Invalid branch name: {branch}')

def input_snapshot(checkout):
    names = git(checkout, 'ls-files', '-z', '--cached', '--others', '--exclude-standard').stdout.split(b'\0')
    files = {}
    for encoded in names:
        if not encoded:
            continue
        name = os.fsdecode(encoded)
        path = checkout / name
        if path.is_symlink():
            files[name] = sha256(os.fsencode(os.readlink(path)))
        elif path.is_file():
            files[name] = sha256(path.read_bytes())
        else:
            files[name] = None  # Includes tracked files deleted by a local fix.
    return {'commit': git_text(checkout, 'rev-parse', 'HEAD'),
            'branch': git(checkout, 'symbolic-ref', '--quiet', '--short', 'HEAD', check=False).stdout.decode().strip(),
            'status': git_text(checkout, 'status', '--porcelain=v1', '--untracked-files=all'), 'files': files}

def artifact_version(checkout, artifact):
    project = ET.parse(checkout / artifact / 'pom.xml').getroot()
    version = project.findtext('m:version', namespaces=NS)
    if not version or not re.fullmatch(r'[A-Za-z0-9_.-]+', version):
        raise WorkflowError(f'Expected a literal version in {artifact}/pom.xml')
    return version

def collect_artifacts(checkout, repository, workspace):
    artifacts = {}
    for artifact in (CORE, BATCH):
        version = artifact_version(checkout, artifact)
        base = repository / 'org/eclipse/jdt' / artifact / version
        output = workspace / 'artifacts' / artifact
        output.mkdir(parents=True)
        paths = {}
        checksums = {}
        for suffix in ('.pom', '.jar', '-sources.jar'):
            name = artifact + '-' + version + suffix
            source = base / name
            if not source.is_file():
                raise WorkflowError(f'JDT did not install the expected artifact: {source}')
            shutil.copy2(source, output / name)
            paths[suffix] = (output / name).relative_to(workspace).as_posix()
            checksums[suffix] = sha256((output / name).read_bytes())
        artifacts[artifact] = {'groupId': 'org.eclipse.jdt', 'artifactId': artifact, 'version': version,
                               'paths': paths, 'sha256': checksums}
    return artifacts

def build(checkout, workdir, settings, repository):
    check_jdk()
    workspace = Path(tempfile.mkdtemp(prefix='build-', dir=workdir))
    provenance = {'schemaVersion': 1, 'checkout': str(checkout), 'repository': str(repository), 'input': input_snapshot(checkout),
                  'status': 'building'}
    save_json(workspace / 'build.json', provenance)
    print(f"Building {provenance['input']['branch'] or '(detached HEAD)'} at {provenance['input']['commit']}", flush=True)
    print(f'Workspace and logs: {workspace}', flush=True)
    try:
        run(maven(settings, repository) + ['clean', 'install', '-DskipTests', '-DlocalEcjVersion=99.99',
            '-Dtycho.mapP2Dependencies=true', '-Dtycho.addMavenDescriptor=true', '-DcompilerBaselineReplace=none', '--projects',
            'org.eclipse.jdt:' + BATCH + ',org.eclipse.jdt:' + CORE, '--also-make'], checkout, workspace / 'build.log')
        if input_snapshot(checkout) != provenance['input']:
            raise WorkflowError('JDT inputs changed during the build; rerun before using these artifacts')
        provenance['artifacts'] = collect_artifacts(checkout, repository, workspace)
        provenance['status'] = 'built'
        save_json(workspace / 'build.json', provenance)
    except Exception as error:
        provenance.update(status='failed', error=str(error))
        save_json(workspace / 'build.json', provenance)
        raise
    return workspace, provenance

def test_checkout(checkout, workdir, settings, repository, test, compliance=25, module='org.eclipse.jdt.core.tests.compiler'):
    check_jdk()
    directory = (checkout / module).resolve()
    if Path(module).is_absolute() or not directory.is_relative_to(checkout.resolve()) or not directory.is_dir():
        raise WorkflowError('The test module must be a directory inside the JDT checkout')
    workspace = Path(tempfile.mkdtemp(prefix='test-', dir=workdir))
    print(f'Test workspace and log: {workspace}', flush=True)
    run(maven(settings, repository) + ['verify', '-Dtest=' + test,
        '-Dtycho.surefire.argLine=-Dcompliance=' + str(compliance)], directory, workspace / 'tests.log')
    return workspace


def main(argv=None, root=ROOT):
    arguments = list(sys.argv[1:] if argv is None else argv)
    if not arguments:
        arguments = ['build']
    elif arguments[0] not in ('build', 'test', 'checkout', '-h', '--help') and not arguments[0].startswith('-'):
        arguments.insert(0, 'build')
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('checkout', 'build', 'test'):
        command = commands.add_parser(name)
        command.add_argument('branch', nargs='?', help='Select a local branch, creating a tracking branch if absent')
        command.add_argument('--fetch', action='store_true', help='Fetch without moving or merging local branches')
        command.add_argument('--java-home', type=Path, help='Override JAVA_HOME, including version-manager shims')
        if name == 'test':
            command.add_argument('--test', required=True, help='Upstream test class, optionally Class#method')
            command.add_argument('--compliance', type=int, default=25)
            command.add_argument('--module', default='org.eclipse.jdt.core.tests.compiler')
    args = parser.parse_args(arguments)
    select_jdk(args.java_home)
    workdir = root / 'workdir/local-jdt'
    repository = workdir / 'm2'
    settings = root / 'scripts/jdt-settings.xml'
    try:
        workdir.mkdir(parents=True, exist_ok=True)
        if args.command != 'checkout':
            check_jdk()
        checkout = prepare_checkout(workdir, args.branch, args.fetch)
        if args.command == 'build':
            workspace, _ = build(checkout, workdir, settings, repository)
            print(f'Build record for patchJDT.py: {workspace / "build.json"}')
        elif args.command == 'test':
            test_checkout(checkout, workdir, settings, repository, args.test, args.compliance, args.module)
        print(f'JDT checkout: {checkout}')
        return 0
    except (WorkflowError, OSError, ValueError, KeyError, ET.ParseError) as error:
        print(f'Error: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
