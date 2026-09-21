"""Emit a fixed diagnostic category without publishing extractor output or tokens."""
import sys
from pathlib import Path


def classify(output: str) -> str:
    message = output.lower()
    categories = (
        ("tool-configuration", ("no such option", "unrecognized arguments", "unknown option")),
        ("authentication-required", ("confirm you're not a bot", "confirm you’re not a bot",
                                     "login required", "sign in to confirm", "use --cookies")),
        ("region-restricted", ("not available in your country", "geo restricted", "geo-restricted")),
        ("rate-limited", ("http error 429", "too many requests")),
        ("access-denied", ("http error 403", "http error 412", "forbidden")),
        ("provider-failure", ("failed to check script", "failed to generate", "potokenprovidererror",
                              "_get_pot_via_script failed")),
        ("javascript-runtime", ("javascript runtime", "challenge solving failed", "nsig extraction failed")),
        ("network-timeout", ("timed out", "timeout", "connection reset", "temporary failure")),
        ("network-tls", ("certificate_verify_failed", "certificate verify failed")),
        ("media-unavailable", ("video unavailable", "private video", "has been removed")),
        ("format-unavailable", ("requested format is not available", "only images are available")),
    )
    for category, markers in categories:
        if any(marker in message for marker in markers):
            return category
    return "unclassified-extractor-failure"


if __name__ == "__main__":
    # Bound reads; raw logs are temporary files, never uploaded as artifacts.
    with Path(sys.argv[1]).open("rb") as stream:
        print(classify(stream.read(262144).decode("utf-8", errors="replace")))
