import unittest
from classify_media_failure import classify


class MediaDiagnosticsTest(unittest.TestCase):
    def test_distinguishes_configuration_from_authentication(self):
        self.assertEqual("tool-configuration", classify("ERROR: no such option: --example"))
        self.assertEqual("authentication-required", classify("ERROR: Sign in to confirm you're not a bot"))

    def test_never_copies_signed_urls_or_unknown_errors(self):
        secret = "https://example.invalid/audio?token=private-value&sig=secret"
        self.assertEqual("access-denied", classify("HTTP Error 403: " + secret))
        self.assertEqual("unclassified-extractor-failure", classify("Unexpected " + secret))

    def test_classifies_provider_and_network_failures(self):
        self.assertEqual("provider-failure", classify("_get_pot_via_script failed with returncode 1"))
        self.assertEqual("network-timeout", classify("Connection timed out"))


if __name__ == "__main__":
    unittest.main()
