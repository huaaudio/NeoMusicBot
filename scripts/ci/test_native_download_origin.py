import unittest
from install_canvas_native import validate_download_origin, definition_for, DEFINITION

class NativeOriginTests(unittest.TestCase):
    def test_current_platform_assets_have_allowed_origins(self):
        for platform in ('linux-x86-64', 'windows-x86-64'):
            _, spec, _ = definition_for(DEFINITION, platform)
            validate_download_origin(spec['url'], platform)

    def test_unrelated_repository_and_host_are_rejected(self):
        for url in ('https://github.com/other/NeoMusicBot/releases/download/native-linux-canvas-r1/a',
                    'https://github.com.evil.test/Automattic/node-canvas/releases/download/a',
                    'https://github.com/huaaudio/NeoMusicBot/releases/download/unrelated/a'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                validate_download_origin(url, 'linux-x86-64')

    def test_windows_cannot_select_linux_source_build(self):
        _, spec, _ = definition_for(DEFINITION, 'linux-x86-64')
        with self.assertRaises(ValueError):
            validate_download_origin(spec['url'], 'windows-x86-64')
