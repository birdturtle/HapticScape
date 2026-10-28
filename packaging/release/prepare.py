"""Validate the public asset contract before publishing a complete release."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import tarfile
import zipfile


def version(tag):
    if not re.fullmatch(r'v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-(?:[0-9A-Za-z]+\.)*[0-9A-Za-z]+)?', tag):
        raise ValueError('Expected a tag such as v3.2.2 or v3.2.2-rc.1')
    return tag[1:]


def pinned(root):
    text = (root / 'runelite-bridge-client/RUNTIME.properties').read_text()
    matches = re.findall(r'^runeLiteVersion=([0-9]+(?:\.[0-9]+){2,3})$', text, re.M)
    if len(matches) != 1:
        raise ValueError('Expected one RuneLite runtime pin')
    return matches[0]


def names(v):
    return [f'HapticScape-Launcher-Windows-x64-{v}.exe',
            f'HapticScape-Windows-x64-{v}.zip',
            f'HapticScape-Linux-x64-{v}.run',
            f'HapticScape-Linux-x64-{v}.tar.gz',
            f'hapticscape-launcher_{v.replace("-", "~")}_amd64.deb']


def manifest_version(jar, expected):
    with zipfile.ZipFile(io.BytesIO(jar)) as archive:
        manifest = archive.read('META-INF/MANIFEST.MF').decode('utf-8').replace('\r\n', '\n')
    manifest = manifest.replace('\n ', '')
    match = re.search(r'^HapticScape-RuneLite-Version: (.+)$', manifest, re.M)
    if not match or match[1].strip() != expected:
        raise ValueError('Packaged LumBridge does not match the RuneLite pin')


def verify_bridge(path, expected):
    suffix = 'LumBridge/app/lumbridge.jar'
    if path.name.endswith('.zip'):
        with zipfile.ZipFile(path) as archive:
            entries = [n for n in archive.namelist() if n.replace('\\', '/').endswith('/' + suffix)]
            if len(entries) != 1:
                raise ValueError('Expected exactly one packaged LumBridge JAR')
            manifest_version(archive.read(entries[0]), expected)
    else:
        with tarfile.open(path, 'r:gz') as archive:
            entries = [m for m in archive.getmembers() if m.name.endswith('/' + suffix)]
            if len(entries) != 1 or not entries[0].isfile():
                raise ValueError('Expected exactly one packaged LumBridge JAR')
            manifest_version(archive.extractfile(entries[0]).read(), expected)


def assemble(source, output, v, rune_lite):
    expected = set(names(v))
    expected |= {name + '.sha256' for name in names(v)}
    suite = f'HapticScape-Suite-{v}.json'
    found = {}
    manifests = []
    for path in sorted(source.rglob('*')):
        if not path.is_file():
            continue
        if path.name == suite:
            value = json.loads(path.read_text(encoding='utf-8-sig'))
            if value != {'schemaVersion': 1, 'version': v}:
                raise ValueError('Unexpected suite manifest')
            manifests.append(path)
        elif path.name not in expected or path.name in found:
            raise ValueError(f'Unexpected or duplicate release asset: {path.name}')
        else:
            found[path.name] = path
    if set(found) != expected or len(manifests) != 2:
        raise ValueError('Release is missing required Windows/Linux assets or suite manifests')
    for name in names(v):
        asset = found[name]
        if not asset.stat().st_size:
            raise ValueError(f'Empty release asset: {name}')
        checksum = found[name + '.sha256'].read_text(encoding='utf-8-sig').strip()
        match = re.fullmatch(r'([0-9a-fA-F]{64})\s+\*?(.+)', checksum)
        hasher = hashlib.sha256()
        with asset.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                hasher.update(chunk)
        digest = hasher.hexdigest()
        if not match or match[1].lower() != digest or match[2] != name:
            raise ValueError(f'Invalid checksum: {name}')
    for name in [f'HapticScape-Windows-x64-{v}.zip', f'HapticScape-Linux-x64-{v}.tar.gz']:
        verify_bridge(found[name], rune_lite)
    output.mkdir(parents=True, exist_ok=False)
    for name, path in found.items():
        shutil.copyfile(path, output / name)
    shutil.copyfile(manifests[0], output / suite)


def verify_uploaded(folder, metadata, tag):
    if metadata.get('draft') is not True or metadata.get('tag_name') != tag:
        raise ValueError('Expected the matching unpublished draft release')
    local = {p.name: p for p in folder.iterdir() if p.is_file()}
    assets = metadata.get('assets', [])
    if len(assets) != len(local) or {a['name'] for a in assets} != set(local):
        raise ValueError('Uploaded release assets are incomplete or unexpected')
    for asset in assets:
        hasher = hashlib.sha256()
        with local[asset['name']].open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                hasher.update(chunk)
        if asset.get('digest') != 'sha256:' + hasher.hexdigest():
            raise ValueError('Uploaded release asset digest does not match the built file')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('tag')
    parser.add_argument('--uploaded', type=Path, help='Verify draft release upload metadata')
    parser.add_argument('--package', type=Path, help='Check the RuneLite pin in one packaged suite')
    parser.add_argument('--artifacts', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    v = version(args.tag)
    pin = pinned(root)
    if args.package:
        verify_bridge(args.package, pin)
        print(f'Packaged RuneLite matches {pin}')
        return
    if args.uploaded:
        if not args.output:
            raise ValueError('Provide --output containing the prepared assets')
        verify_uploaded(args.output, json.loads(args.uploaded.read_text()), args.tag)
    if args.artifacts:
        if not args.output:
            raise ValueError('Provide --output for release assembly')
        assemble(args.artifacts, args.output, v, pin)
    print(f'Validated HapticScape {v}, RuneLite {pin}')


if __name__ == '__main__':
    main()
