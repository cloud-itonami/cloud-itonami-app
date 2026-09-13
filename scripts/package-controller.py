#!/usr/bin/env python3
"""Package a JVM controller without copying user state or credentials."""
import argparse, hashlib, json, pathlib, re, shutil, subprocess
p = argparse.ArgumentParser()
p.add_argument('output', type=pathlib.Path)
a = p.parse_args()
root = pathlib.Path(__file__).resolve().parent.parent
out = a.output.resolve()
if out.exists():
    raise SystemExit('Output already exists; choose a fresh release directory')
cp = subprocess.check_output(['clojure', '-Spath', '-M:server'], cwd=root, text=True).strip().split(':')
out.mkdir(parents=True)
entries, absent = [], []
# The resident JVM still resolves .clj/.cljc. Keep source names canonical and
# materialize the recorded pre-rename extensions only in the release artifact.
origins = dict(re.findall(r'"([^"\n]+\.cljk)"\s+"(\.clj[sc]?)"',
                         (root / 'cljk-origin.edn').read_text()))
for i, source in enumerate(cp):
    src = pathlib.Path(source)
    if not src.is_absolute(): src = root / src
    if not src.exists():
        absent.append(source)  # tools.deps can declare optional resource directories
        continue
    dest = pathlib.Path('classpath') / (str(i) + ('.jar' if src.is_file() else ''))
    if src.is_dir():
        shutil.copytree(src, out / dest)
        for renamed in src.rglob('*.cljk'):
            if not renamed.is_relative_to(root):
                continue  # dependency fixtures may intentionally use .cljk
            relative = str(renamed.relative_to(root))
            extension = origins.get(relative)
            if extension is None:
                raise SystemExit(f'Missing cljk origin: {relative}')
            original = renamed.name[:-5]
            if not original.endswith(extension): original += extension
            target = out / dest / renamed.relative_to(src).parent / original
            if target.exists() and target.read_bytes() != renamed.read_bytes():
                raise SystemExit(f'Conflicting JVM source: {target}')
            shutil.copy2(renamed, target)
    else:
        (out / dest).parent.mkdir(exist_ok=True)
        shutil.copy2(src, out / dest)
    entries.append(str(dest))
if not entries: raise SystemExit('Empty runtime classpath')
(out / 'classpath.txt').write_text(':'.join(entries))
shutil.copy2(root / 'scripts' / 'run-controller.sh', out / 'run-controller.sh')
files = {str(f.relative_to(out)): hashlib.sha256(f.read_bytes()).hexdigest()
         for f in out.rglob('*') if f.is_file()}
(out / 'release.json').write_text(json.dumps({'sourceCommit': subprocess.check_output(
    ['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
    'dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=root)),
    'optionalMissingClasspathEntries': absent, 'files': files}, indent=2) + '\n')
print(json.dumps({'output': str(out), 'classpathEntries': len(entries), 'missingOptionalEntries': len(absent), 'files': len(files)}))
