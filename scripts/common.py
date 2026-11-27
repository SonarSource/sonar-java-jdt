"""Process and Maven utilities shared by the independent JDT command-line tools."""
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]

class WorkflowError(Exception):
    pass

def sha256(data):
    return hashlib.sha256(data).hexdigest()

def save_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')

def run(command, directory, log=None, capture=False, check=True):
    # IDE-provided Git variables must not redirect commands to another checkout.
    environment = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
    environment['GIT_NO_REPLACE_OBJECTS'] = '1'
    if capture:
        result = subprocess.run(command, cwd=directory, env=environment, capture_output=True)
    elif log:
        with log.open('a', encoding='utf-8') as output:
            output.write('+ ' + shlex.join(map(str, command)) + '\n')
            output.flush()
            with subprocess.Popen(command, cwd=directory, env=environment, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True, errors='replace') as process:
                for line in process.stdout:
                    print(line, end='', flush=True)
                    output.write(line)
                result = subprocess.CompletedProcess(command, process.wait())
    else:
        result = subprocess.run(command, cwd=directory, env=environment)
    if check and result.returncode:
        detail = result.stderr.decode('utf-8', 'replace').strip() if capture else str(log or directory)
        raise WorkflowError(f"Command failed ({result.returncode}): {shlex.join(map(str, command))}\n{detail}")
    return result

def check_jdk(minimum=25):
    home = os.environ.get('JAVA_HOME')
    if not home:
        raise WorkflowError(f'Set JAVA_HOME to Java {minimum} or newer')
    executable = Path(home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    version = run([str(executable), '--version'], ROOT, capture=True)
    match = re.search(rb'(?m)^(?:openjdk|java) (?:version )?"?(\d+)(?:[.\s"+-])', version.stdout + version.stderr)
    if not match or int(match[1]) < minimum:
        raise WorkflowError(f'This command requires JAVA_HOME to select Java {minimum} or newer')

def maven(settings, repository):
    return ['mvn.cmd' if os.name == 'nt' else 'mvn', '--batch-mode', '--no-snapshot-updates',
            '--settings', str(settings), '-Dmaven.repo.local=' + str(repository), '-Dskip-sonarsource-repo=true']

def select_jdk(home):
    if home:
        os.environ['JAVA_HOME'] = str(home.resolve())
        os.environ['PATH'] = str(home.resolve() / 'bin') + os.pathsep + os.environ.get('PATH', '')
