#!/usr/bin/env python3
"""Install a verified .wcp Wine layer into Winlator's /opt on the phone.

The archive is extracted into a staging directory and renamed only after its
Wine executable and profile are present. Existing layers are never replaced.
"""

import argparse
import hashlib
import importlib.util
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("wehavephones_device", ROOT / "scripts/wehavephones-device.py")
dev = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(dev)

ADB = dev.adb_bin()
PACKAGE = dev.PKG
BASE = "/data/user/0/" + PACKAGE + "/files/imagefs/opt"


def adb(*args, stdin=None, check=True):
    # ADB_SERIAL, ANDROID_DEVICE (also from local.env), else the only attached device.
    return subprocess.run([ADB, "-s", dev.resolve_serial(), *args], stdin=stdin, capture_output=True, check=check)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=pathlib.Path)
    parser.add_argument("identifier")
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--resume", action="store_true", help="Re-extract into a prior incomplete staging directory")
    args = parser.parse_args()
    if not args.identifier.startswith("proton-") or not args.identifier.replace("-", "").replace(".", "").isalnum():
        parser.error("Use a simple proton-* identifier")
    dev.resolve_serial()  # stop before hashing the archive when no phone is configured
    digest = hashlib.sha256(args.archive.read_bytes()).hexdigest()
    if digest != args.sha256.lower():
        raise RuntimeError("Archive SHA-256 does not match the expected hash")
    destination = BASE + "/" + args.identifier
    staging = destination + ".installing"
    if adb("shell", "run-as", PACKAGE, "test", "-e", destination, check=False).returncode == 0:
        raise RuntimeError("Layer already installed: " + destination)
    staging_exists = adb("shell", "run-as", PACKAGE, "test", "-e", staging, check=False).returncode == 0
    if staging_exists and not args.resume:
        raise RuntimeError("Staging path already exists: " + staging + "; use --resume")
    if args.resume and not staging_exists:
        raise RuntimeError("No staging path to resume: " + staging)
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", staging)
    decompressor = subprocess.Popen(["zstd", "-dc", str(args.archive)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        result = adb("exec-in", "run-as", PACKAGE, "tar", "-xf", "-", "-C", staging, stdin=decompressor.stdout)
    finally:
        decompressor.stdout.close()
    decompressor_error = decompressor.stderr.read().decode(errors="replace")
    if decompressor.wait() != 0:
        raise RuntimeError("Archive decompression failed: " + decompressor_error)
    if result.stderr:
        print(result.stderr.decode(errors="replace"))
    for required in ("bin/wine", "profile.json"):
        adb("shell", "run-as", PACKAGE, "test", "-f", staging + "/" + required)
    adb("shell", "run-as", PACKAGE, "mv", staging, destination)
    print("Installed", args.identifier, "sha256=" + digest)


if __name__ == "__main__":
    main()
