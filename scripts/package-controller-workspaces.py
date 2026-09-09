#!/usr/bin/env python3
"""Package tracked working files and complete reachable Git history, without activation.

Preflight stops above 5,000,000,000 estimated bytes. Git bundles contain HEAD,
branches and tags, never .git/config, hooks or reflogs. Untracked files are excluded.
Remote mode exports only operational root paths (no root history/personal subtree)
and full reachable history of explicitly inventoried child repositories. Git-annex
links are retained as metadata; their payloads are not copied. The output is a
private migration artifact, not a public archive or secret audit.
"""
import argparse
import datetime
import hashlib
import json
import os
import re
from pathlib import Path
import stat
import shlex
import tarfile
import uuid
import subprocess

LIMIT = 5_000_000_000
PRIVATE_MARKERS = (b'-----BEGIN PRIVATE KEY-----', b'-----BEGIN RSA PRIVATE KEY-----',
                   b'-----BEGIN EC PRIVATE KEY-----', b'-----BEGIN OPENSSH PRIVATE KEY-----',
                   b'-----BEGIN DSA PRIVATE KEY-----', b'-----BEGIN ENCRYPTED PRIVATE KEY-----')


def git(root, *args):
    return subprocess.run(['git', '-C', str(root), *args], check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          env={**os.environ, 'GIT_OPTIONAL_LOCKS': '0'}).stdout


def stamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def write_manifest(path, manifest):
    path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n')
    path.chmod(0o600)


def mapped(path, mappings):
    for source, target in mappings:
        if path.is_relative_to(source):
            return target / path.relative_to(source)
    return None


def inspect_file(path):
    digest = hashlib.sha256()
    carry = b''
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            probe = carry + chunk
            if any(re.search(re.escape(marker) + rb'\r?\n[A-Za-z0-9+/=]{16,}', probe) for marker in PRIVATE_MARKERS):
                raise ValueError('Private-key material detected; refusing snapshot file: ' + str(path))
            digest.update(chunk)
            carry = probe[-256:]
    return digest.hexdigest()


def stream_remote(inventory, host, manifest_path):
    """Stream repository-only snapshots; no runtime workspaces or activation."""
    selected = [w for w in inventory['workspaces'] if w['category'] == 'repository-workspace']
    mappings = sorted([(Path(w['source']), Path(w['target'])) for w in selected],
                      key=lambda pair: len(str(pair[0])), reverse=True)
    target_root = Path(inventory['targetRoot'])
    def ssh(command):
        return subprocess.run(['ssh', host, command], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    ssh('test ! -e ' + shlex.quote(str(target_root)))
    run_id = uuid.uuid4().hex
    remote_temp = '/Users/judah/.itonami-workspace-import-' + run_id
    ssh('umask 077 && mkdir ' + shlex.quote(remote_temp))
    manifest = {'schemaVersion': 1, 'status': 'copying', 'startedAt': stamp(),
                'remote': host, 'targetRoot': str(target_root), 'repositories': [],
                'runtimeCopied': False, 'activated': False}
    write_manifest(manifest_path, manifest)
    root_source = min((s for s, _ in mappings), key=lambda p: len(str(p)))
    operational_dirs = ('scripts', 'manifest', '.agents/skills', '.claude/skills')
    operational_files = {'AGENTS.md', 'CLAUDE.md', 'SECURITY.md', 'package.json', 'package-lock.json', 'deps.edn', 'nbb.edn'}
    child_sources = [s for s, _ in mappings if s != root_source]
    def included_root_path(path):
        if not path.is_relative_to(root_source): return False
        relative = path.relative_to(root_source)
        return str(relative) in operational_files or any(relative.is_relative_to(Path(d)) for d in operational_dirs)
    def permitted_target(path):
        return included_root_path(path) or any(path.is_relative_to(s) for s in child_sources)
    manifest['rootScope'] = {'directories': operational_dirs, 'files': sorted(operational_files),
                             'gitHistory': False, 'personalSubtree': False}
    try:
        for source, target in sorted(mappings, key=lambda pair: len(str(pair[0]))):
            head = git(source, 'rev-parse', 'HEAD').decode().strip()
            if git(source, 'rev-parse', '--is-shallow-repository').strip() == b'true':
                raise ValueError('Shallow repository: ' + str(source))
            root_snapshot = source == root_source
            record = {'source': str(source), 'target': str(target), 'head': head, 'files': [], 'kind': 'operational-filesystem-snapshot' if root_snapshot else 'git-repository', 'omittedSymlinks': [], 'excludedTrackedFiles': 0}
            # Resolve and inspect tracked files before exporting this repository.
            pathspec = ['--', *operational_dirs, *sorted(operational_files)] if root_snapshot else []
            tracked_names = git(source, 'ls-files', '-z', *pathspec).split(b'\0')
            if root_snapshot:
                record['excludedTrackedFiles'] = next(r['trackedSnapshot']['files'] for r in inventory['repositories'] if r['root'] == str(source)) - sum(bool(n) for n in tracked_names)
            for raw in tracked_names:
                if not raw:
                    continue
                relative = Path(os.fsdecode(raw)); path = source / relative
                if root_snapshot and not included_root_path(path):
                    record['excludedTrackedFiles'] += 1; continue
                if relative.is_absolute() or '..' in relative.parts or '.git' in relative.parts:
                    raise ValueError('Unsafe tracked path')
                entry = {'path': str(relative)}
                try:
                    info = path.lstat()
                except FileNotFoundError:
                    entry['type'] = 'deleted'; record['files'].append(entry); continue
                if stat.S_ISLNK(info.st_mode):
                    resolved = path.resolve(strict=False)
                    translated = mapped(resolved, mappings) if permitted_target(resolved) else None
                    if translated is None and root_snapshot:
                        record['omittedSymlinks'].append(str(relative)); continue
                    if translated is None:
                        raise ValueError('Unmapped symlink: ' + str(path))
                    entry.update(type='symlink', link=os.path.relpath(translated, (target / relative).parent))
                elif stat.S_ISREG(info.st_mode):
                    entry.update(type='file', sha256=inspect_file(path), bytes=info.st_size,
                                 mode=stat.S_IMODE(info.st_mode))
                else:
                    raise ValueError('Unsupported file or gitlink: ' + str(path))
                record['files'].append(entry)
            record['trackedStatusSha256'] = hashlib.sha256(git(source, 'status', '--porcelain=v1', '-z', '--untracked-files=no', *pathspec)).hexdigest()
            ssh('test ! -e ' + shlex.quote(str(target)))
            bundle = remote_temp + '/history.bundle'
            if root_snapshot:
                ssh('mkdir -p ' + shlex.quote(str(target)))
            else:
                producer = subprocess.Popen(['git', '-C', str(source), 'bundle', 'create', '-', 'HEAD', '--branches', '--tags'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                consumer = subprocess.Popen(['ssh', host, 'umask 077 && cat > ' + shlex.quote(bundle)], stdin=producer.stdout, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                producer.stdout.close()
                _, ce = consumer.communicate(); _, pe = producer.communicate()
                if consumer.returncode or producer.returncode:
                    raise RuntimeError('Bundle streaming failed: ' + (ce + pe).decode(errors='replace')[-1000:])
                record['bundleSha256'] = ssh('shasum -a 256 ' + shlex.quote(bundle)).stdout.decode().split()[0]
                ssh('git clone --no-checkout ' + shlex.quote(bundle) + ' ' + shlex.quote(str(target)) +
                    ' && git -C ' + shlex.quote(str(target)) + ' checkout --detach ' + shlex.quote(head) +
                    ' && git -C ' + shlex.quote(str(target)) + ' remote remove origin')
            # Stream current tracked file bytes, preserving tracked dirty work without untracked files.
            consumer = subprocess.Popen(['ssh', host, 'tar -xf - -C ' + shlex.quote(str(target))], stdin=subprocess.PIPE, stderr=subprocess.PIPE)
            with tarfile.open(fileobj=consumer.stdin, mode='w|') as tar:
                for entry in record['files']:
                    if entry['type'] == 'deleted': continue
                    path = source / entry['path']
                    if entry['type'] == 'file':
                        if inspect_file(path) != entry['sha256']:
                            raise ValueError('Working file changed: ' + str(path))
                        tar.add(path, arcname=entry['path'], recursive=False)
                    else:
                        ti = tarfile.TarInfo(entry['path']); ti.type = tarfile.SYMTYPE; ti.linkname = entry['link']; tar.addfile(ti)
            consumer.stdin.close(); consumer.stdin = None
            _, error = consumer.communicate()
            if consumer.returncode: raise RuntimeError('Tracked overlay failed: ' + error.decode(errors='replace')[-1000:])
            verifier = """import sys,json,hashlib,pathlib,os
r=json.load(sys.stdin); root=pathlib.Path(r['target'])
for f in r['files']:
 p=root/f['path']
 if f['type']=='deleted':
  if p.exists() or p.is_symlink(): p.unlink()
 elif f['type']=='symlink':
  assert p.is_symlink() and os.readlink(p)==f['link'],str(p)
 else:
  assert hashlib.sha256(p.read_bytes()).hexdigest()==f['sha256'],str(p)
print('verified')
"""
            subprocess.run(['ssh', host, 'python3 -c ' + shlex.quote(verifier)], input=json.dumps(record).encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
            if not root_snapshot:
                assert ssh('git -C ' + shlex.quote(str(target)) + ' rev-parse HEAD').stdout.decode().strip() == head
            if git(source, 'rev-parse', 'HEAD').decode().strip() != head:
                raise ValueError('Source HEAD changed: ' + str(source))
            record['verifiedAt'] = stamp(); record['status'] = 'verified'
            manifest['repositories'].append(record); write_manifest(manifest_path, manifest)
            if not root_snapshot: ssh('rm ' + shlex.quote(bundle))
            print(json.dumps({'verified': len(manifest['repositories']), 'source': str(source), 'head': head}), flush=True)
        manifest['status'] = 'verified'; manifest['finishedAt'] = stamp(); write_manifest(manifest_path, manifest)
    except Exception as exc:
        manifest['status'] = 'failed-do-not-activate'; manifest['failure'] = str(exc); write_manifest(manifest_path, manifest); raise


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--remote', help='Stream repository-only snapshots to this SSH host')
    ap.add_argument('--remote-manifest', type=Path, default=Path('/tmp/itonami-workspaces-stream-manifest.json'))
    ap.add_argument('--inventory', type=Path, default=Path('/tmp/itonami-controller-inventory.json'))
    ap.add_argument('--output', type=Path, default=Path('/private/tmp/itonami-workspaces-package'))
    args = ap.parse_args()
    inventory = json.loads(args.inventory.read_text())
    if args.remote:
        stream_remote(inventory, args.remote, args.remote_manifest)
        return 0
    if args.output.exists():
        raise SystemExit('Refusing to overwrite an existing package directory: ' + str(args.output))
    mappings = sorted([(Path(w['source']), Path(w['target'])) for w in inventory['workspaces']
                       if w.get('target')], key=lambda pair: len(str(pair[0])), reverse=True)
    repos = []
    for repo in inventory['repositories']:
        root = Path(repo['root'])
        counts = dict(line.split(': ', 1) for line in git(root, 'count-objects', '-v').decode().splitlines())
        object_bytes = (int(counts['size']) + int(counts['size-pack'])) * 1024
        head = repo['head']
        shallow = git(root, 'rev-parse', '--is-shallow-repository').decode().strip() == 'true'
        if shallow:
            raise SystemExit('Shallow history is not portable full history: ' + str(root))
        target = mapped(root, mappings)
        if target is None:
            raise SystemExit('Repository lacks a known target mapping: ' + str(root))
        repos.append({'source': str(root), 'target': str(target), 'head': head,
                      'gitObjectBytes': object_bytes,
                      'trackedBytes': repo['trackedSnapshot']['bytes'], 'files': []})
    total = sum(r['gitObjectBytes'] + r['trackedBytes'] for r in repos)
    manifest = {'schemaVersion': 1, 'createdAt': stamp(), 'status': 'preflight',
                'estimatedBytes': total, 'maximumEstimatedBytes': LIMIT,
                'inventorySha256': hashlib.sha256(args.inventory.read_bytes()).hexdigest(),
                'repositories': repos,
                'limitations': ['No untracked files, hooks, config, reflogs or unreachable Git objects.',
                                'Original history is private source material, not certified free of historic secrets.',
                                'No remote transfer or activation is performed.']}
    args.output.mkdir(mode=0o700, parents=True)
    output_manifest = args.output / 'manifest.json'
    if total > LIMIT:
        manifest['status'] = 'blocked-size-limit'
        write_manifest(output_manifest, manifest)
        print(json.dumps({'status': manifest['status'], 'estimatedBytes': total,
                          'maximumEstimatedBytes': LIMIT, 'manifest': str(output_manifest)}))
        return 2
    try:
        # Validate all paths and keys before producing any bundle or file snapshot.
        for i, repo in enumerate(repos):
            root = Path(repo['source'])
            if repo['head'] is None or git(root, 'rev-parse', 'HEAD').decode().strip() != repo['head']:
                raise ValueError('Unborn or changed HEAD: ' + str(root))
            statuses = git(root, 'status', '--porcelain=v1', '-z', '--untracked-files=no')
            repo['trackedStatusSha256'] = hashlib.sha256(statuses).hexdigest()
            repo['indexMetadataSha256'] = hashlib.sha256(git(root, 'ls-files', '--stage', '-z')).hexdigest()
            repo['trackedDirty'] = bool(statuses)
            for raw in git(root, 'ls-files', '-z').split(b'\0'):
                if not raw:
                    continue
                relative = Path(os.fsdecode(raw))
                if relative.is_absolute() or '..' in relative.parts or '.git' in relative.parts:
                    raise ValueError('Unsafe tracked path')
                source = root / relative
                entry = {'path': str(relative)}
                try:
                    info = source.lstat()
                except FileNotFoundError:
                    entry['type'] = 'deleted'
                    repo['files'].append(entry)
                    continue
                if stat.S_ISLNK(info.st_mode):
                    resolved = source.resolve(strict=False)
                    translated = mapped(resolved, mappings)
                    if translated is None:
                        raise ValueError('Unmapped symlink target: ' + str(source))
                    entry.update(type='symlink', originalLink=os.readlink(source),
                                 link=os.path.relpath(translated, (Path(repo['target']) / relative).parent))
                elif stat.S_ISREG(info.st_mode):
                    entry.update(type='file', bytes=info.st_size, mode=stat.S_IMODE(info.st_mode),
                                 sha256=inspect_file(source))
                else:
                    raise ValueError('Unsupported tracked file type (including Git links): ' + str(source))
                repo['files'].append(entry)
            repo['bundle'] = 'repos/' + str(i) + '/history.bundle'
            repo['workingTree'] = 'repos/' + str(i) + '/files'
        for repo in repos:
            root = Path(repo['source'])
            bundle = args.output / repo['bundle']
            bundle.parent.mkdir(parents=True, mode=0o700)
            git(root, 'bundle', 'create', str(bundle), 'HEAD', '--branches', '--tags')
            git(root, 'bundle', 'verify', str(bundle))
            repo['bundleSha256'] = hashlib.sha256(bundle.read_bytes()).hexdigest()
            for entry in repo['files']:
                destination = args.output / repo['workingTree'] / entry['path']
                destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                if entry['type'] == 'deleted':
                    continue
                if entry['type'] == 'symlink':
                    # Store link metadata only: never create a link out of this package.
                    continue
                source = root / entry['path']
                data = source.read_bytes()
                if hashlib.sha256(data).hexdigest() != entry['sha256']:
                    raise ValueError('Working file changed during snapshot: ' + str(source))
                destination.write_bytes(data)
                destination.chmod(entry['mode'] & 0o700)
            if git(root, 'rev-parse', 'HEAD').decode().strip() != repo['head']:
                raise ValueError('HEAD changed during snapshot: ' + str(root))
        manifest['status'] = 'packaged'
        manifest['finishedAt'] = stamp()
        manifest['actualPayloadBytes'] = sum(p.stat().st_size for p in args.output.rglob('*') if p.is_file())
        write_manifest(output_manifest, manifest)
        print(json.dumps({'status': 'packaged', 'manifest': str(output_manifest),
                          'actualPayloadBytes': manifest['actualPayloadBytes']}))
        return 0
    except Exception as exc:
        manifest['status'] = 'failed-do-not-activate'
        manifest['failure'] = str(exc)
        write_manifest(output_manifest, manifest)
        raise


if __name__ == '__main__':
    raise SystemExit(main())
