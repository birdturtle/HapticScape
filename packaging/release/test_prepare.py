import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import textwrap
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile

from prepare import assemble, names, version, verify_uploaded


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'artifacts'
        self.windows = self.source / 'windows'
        self.linux = self.source / 'linux'
        self.windows.mkdir(parents=True)
        self.linux.mkdir()
        self.version = '3.2.2-rc.1'
        self.pin = '1.13.2'
        self.output = self.root / 'release'
        for name in names(self.version):
            path = (self.windows if 'Windows' in name else self.linux) / name
            path.write_bytes(b'package')
        self.jar(self.pin)
        for path in list(self.source.rglob('*')):
            if path.is_file() and not path.name.endswith('.sha256'):
                self.checksum(path)
        for folder in (self.windows, self.linux):
            (folder / f'HapticScape-Suite-{self.version}.json').write_text(json.dumps({'schemaVersion': 1, 'version': self.version}))

    def checksum(self, path):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        Path(str(path) + '.sha256').write_text(f'{digest} *{path.name}\n')

    def jar(self, pin):
        content = io.BytesIO()
        with zipfile.ZipFile(content, 'w') as archive:
            archive.writestr('META-INF/MANIFEST.MF', f'Manifest-Version: 1.0\r\nHapticScape-RuneLite-Version: {pin}\r\n')
        jar = content.getvalue()
        windows = self.windows / f'HapticScape-Windows-x64-{self.version}.zip'
        with zipfile.ZipFile(windows, 'w') as archive:
            archive.writestr('HapticScape/LumBridge/app/lumbridge.jar', jar)
        linux = self.linux / f'HapticScape-Linux-x64-{self.version}.tar.gz'
        with tarfile.open(linux, 'w:gz') as archive:
            info = tarfile.TarInfo('HapticScape/LumBridge/app/lumbridge.jar')
            info.size = len(jar)
            archive.addfile(info, io.BytesIO(jar))
        for path in (windows, linux):
            self.checksum(path)

    def assemble(self):
        assemble(self.source, self.output, self.version, self.pin)

    def test_complete_release_preserves_asset_names_and_one_shared_manifest(self):
        self.assemble()
        self.assertEqual(len(list(self.output.iterdir())), 11)
        self.assertTrue((self.output / 'hapticscape-launcher_3.2.2~rc.1_amd64.deb').exists())

    def test_missing_platform_never_creates_publish_directory(self):
        next(self.windows.glob('*.exe')).unlink()
        with self.assertRaises(ValueError): self.assemble()
        self.assertFalse(self.output.exists())

    def test_corrupt_asset_never_creates_publish_directory(self):
        next(self.linux.glob('*.run')).write_bytes(b'changed')
        with self.assertRaises(ValueError): self.assemble()
        self.assertFalse(self.output.exists())

    def test_platform_manifest_disagreement_is_rejected(self):
        next(self.windows.glob('*.json')).write_text('{"schemaVersion":1,"version":"3.2.1"}')
        with self.assertRaises(ValueError): self.assemble()

    def test_wrong_runelite_in_otherwise_valid_archives_is_rejected(self):
        self.jar('1.13.1')
        with self.assertRaises(ValueError): self.assemble()
        self.assertFalse(self.output.exists())

    def test_duplicate_or_unexpected_files_are_rejected(self):
        (self.windows / 'extra.zip').write_bytes(b'unexpected')
        with self.assertRaises(ValueError): self.assemble()

    def test_draft_upload_must_match_all_local_assets_and_digests(self):
        self.assemble()
        metadata = {'draft': True, 'tag_name': 'v' + self.version, 'assets': [
            {'name': p.name, 'digest': 'sha256:' + hashlib.sha256(p.read_bytes()).hexdigest()}
            for p in self.output.iterdir()]}
        verify_uploaded(self.output, metadata, 'v' + self.version)
        metadata['assets'][0]['digest'] = 'sha256:' + '0' * 64
        with self.assertRaises(ValueError): verify_uploaded(self.output, metadata, 'v' + self.version)
        metadata['assets'].pop()
        with self.assertRaises(ValueError): verify_uploaded(self.output, metadata, 'v' + self.version)
        metadata['draft'] = False
        with self.assertRaises(ValueError): verify_uploaded(self.output, metadata, 'v' + self.version)

    @unittest.skipIf(os.name == 'nt', 'The publish job uses Bash on Linux')
    def test_publish_creates_draft_and_fetches_it_by_id_before_publication(self):
        self.publish_job(notes=True)

    @unittest.skipIf(os.name == 'nt', 'The publish job uses Bash on Linux')
    def test_publish_without_notes_generates_notes_and_verifies_assets(self):
        self.publish_job(notes=False)

    @unittest.skipIf(os.name == 'nt', 'The publish job uses Bash on Linux')
    def test_existing_draft_without_notes_can_finish_publication(self):
        self.publish_job(notes=False, existing=True)

    def publish_job(self, notes, existing=False):
        self.assemble()
        repository = Path(__file__).resolve().parents[2]
        work = self.root / 'publish-job'
        (work / 'build').mkdir(parents=True)
        shutil.copytree(self.output, work / 'build/release')
        (work / 'packaging/release').mkdir(parents=True)
        shutil.copyfile(repository / 'packaging/release/prepare.py', work / 'packaging/release/prepare.py')
        (work / 'runelite-bridge-client').mkdir()
        (work / 'runelite-bridge-client/RUNTIME.properties').write_text('runeLiteVersion=' + self.pin)
        if notes:
            (work / 'docs/releases').mkdir(parents=True)
            (work / f'docs/releases/{self.version}.md').write_text('Release notes')
        if existing:
            (work / 'draft-created').touch()
        metadata = {'draft': True, 'tag_name': 'v' + self.version, 'assets': [
            {'name': p.name, 'digest': 'sha256:' + hashlib.sha256(p.read_bytes()).hexdigest()}
            for p in self.output.iterdir()]}
        (work / 'metadata.json').write_text(json.dumps(metadata))
        tools = work / 'tools'
        tools.mkdir()
        stub = tools / 'gh'
        stub.write_text('#!' + sys.executable + '\n' + textwrap.dedent("""
            import json, pathlib, sys
            args = sys.argv[1:]
            with pathlib.Path('gh-calls.jsonl').open('a') as calls:
                calls.write(json.dumps(args) + '\\n')
            state = pathlib.Path('draft-created')
            if args[:2] == ['release', 'view']:
                if not state.exists(): sys.exit(1)
                print('true' if 'isDraft' in args else '12345')
            elif args[:2] == ['release', 'create']:
                assert '--draft' in args
                if '--notes-file' in args:
                    assert pathlib.Path(args[args.index('--notes-file') + 1]).is_file()
                else:
                    assert '--generate-notes' in args
                state.touch()
            elif args[:2] == ['release', 'upload']:
                assert state.exists()
            elif args[:1] == ['api']:
                assert args[1] == 'repos/fixture/repo/releases/12345', 'Drafts must be fetched by numeric ID'
                print(pathlib.Path('metadata.json').read_text())
            elif args[:2] == ['release', 'edit']:
                if '--draft=false' in args:
                    assert pathlib.Path('uploaded-assets.json').exists()
                    pathlib.Path('published').touch()
            else: sys.exit(1)
        """))
        stub.chmod(0o755)
        workflow = (repository / '.github/workflows/release.yml').read_text()
        script = textwrap.dedent(workflow.rsplit('        run: |\n', 1)[1])
        result = subprocess.run(['bash', '-c', script], cwd=work, env=dict(os.environ, PATH=str(tools) + os.pathsep + os.environ['PATH'], RELEASE_TAG='v' + self.version, GH_REPO='fixture/repo', RUNNER_TEMP=str(work)), capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((work / 'published').is_file())
        calls = [json.loads(line) for line in (work / 'gh-calls.jsonl').read_text().splitlines()]
        creates = [args for args in calls if args[:2] == ['release', 'create']]
        if existing:
            self.assertEqual(creates, [])
        else:
            self.assertEqual(len(creates), 1)
            self.assertIn('--notes-file' if notes else '--generate-notes', creates[0])
        if not notes:
            self.assertFalse(any('--notes-file' in args for args in calls))

    def test_tag_validation_does_not_require_a_notes_file(self):
        repository = Path(__file__).resolve().parents[2]
        script = self.root / 'packaging/release/prepare.py'
        script.parent.mkdir(parents=True)
        shutil.copyfile(repository / 'packaging/release/prepare.py', script)
        (self.root / 'runelite-bridge-client').mkdir()
        (self.root / 'runelite-bridge-client/RUNTIME.properties').write_text('runeLiteVersion=' + self.pin)
        result = subprocess.run([sys.executable, str(script), 'v3.2.4'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Validated HapticScape 3.2.4', result.stdout)

    def test_versions_require_three_components_and_reject_paths(self):
        self.assertEqual(version('v3.2.2'), '3.2.2')
        self.assertEqual(version('v3.2.2-rc.1'), self.version)
        for invalid in ['v3.2', 'v3.2.2.1', '3.2.2', 'v3.2.2/../secret', 'v03.2.2']:
            with self.assertRaises(ValueError): version(invalid)


if __name__ == '__main__':
    unittest.main()
