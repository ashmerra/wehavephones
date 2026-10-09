#!/usr/bin/env python3
"""Reject a missing or substituted Mesa driver before building WeHavePhones."""

import hashlib
import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = "app/src/wehavephones/assets/wehavephones/graphics/turnip.json"


def verify(root=ROOT):
    manifest = json.loads((root / MANIFEST).read_text(encoding="utf-8"))
    driver_id = "turnip" + manifest["mesaVersion"] + "-" + manifest["mesaRevision"]
    if manifest["driverId"] != driver_id:
        raise ValueError("Turnip driver ID does not identify the pinned Mesa revision")
    asset = manifest["archiveAsset"]
    expected_asset = "graphics_driver/adrenotools-" + manifest["driverId"] + ".tzst"
    if asset != expected_asset:
        raise ValueError("Turnip archive path does not match its pinned driver ID")
    archive = root / "app/src/main/assets" / asset
    actual = hashlib.sha256(archive.read_bytes()).hexdigest()
    if actual != manifest["archiveSha256"]:
        raise ValueError("Turnip archive SHA-256 mismatch: " + str(archive))
    profiles = json.loads((root / "app/src/wehavephones/assets/wehavephones/wehavephones-runtime-profiles.json")
                          .read_text(encoding="utf-8"))["profiles"]
    if profiles["p10-ge34"].get("graphics", {}).get("version") != driver_id:
        raise ValueError("p10-ge34 does not select the pinned Turnip driver")
    return manifest


def main():
    try:
        manifest = verify()
    except (OSError, ValueError, KeyError) as error:
        print("[verify-wehavephones-turnip] FAIL: " + str(error), file=sys.stderr)
        return 1
    print("[verify-wehavephones-turnip] Verified " + manifest["driverId"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
