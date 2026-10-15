import hashlib
import io
import json
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

    def test_versions_require_three_components_and_reject_paths(self):
        self.assertEqual(version('v3.2.2'), '3.2.2')
        self.assertEqual(version('v3.2.2-rc.1'), self.version)
        for invalid in ['v3.2', 'v3.2.2.1', '3.2.2', 'v3.2.2/../secret', 'v03.2.2']:
            with self.assertRaises(ValueError): version(invalid)


if __name__ == '__main__':
    unittest.main()
