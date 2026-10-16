#!/usr/bin/env python3
"""Check optional Turnip provenance, and optionally all seven downloaded upstream ZIPs.

Pass --archives DIR to recheck the actual packages without fetching during offline host tests.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/wehavephones/assets/wehavephones"
TAGS = [f"v26.3.0-20261015-r{r}" for r in range(4, 8)] + [
    "v27.0.0-20261016-r3", "v27.0.0-20261016-r4"
]
RUTABAGA = "turnip-gamesir-rutabaga-v3"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archives", type=Path)
    args = parser.parse_args()
    rules = json.loads((ASSETS / "device-rules.json").read_text())
    manifest = json.loads((ASSETS / "graphics/turnip-optional-20261016.json").read_text())
    expected = {RUTABAGA} | {f"turnip-banner-{tag[1:]}-a8xx" for tag in TAGS}
    assert set(manifest["drivers"]) == expected, "exactly the seven requested releases"
    archive_hashes, library_hashes = set(), set()
    for driver_id, provenance in manifest["drivers"].items():
        driver = rules["drivers"][driver_id]
        for key in ("url", "sha256", "libraryName", "librarySha256"):
            assert driver[key] == provenance[key], (driver_id, key)
        assert driver["asset"] is None, driver_id
        assert driver["libraryName"] == "libvulkan_freedreno.so", driver_id
        meta = provenance["upstreamMeta"]
        assert meta["libraryName"] == driver["libraryName"], driver_id
        assert meta["schemaVersion"] == 1 and meta["minApi"] == 28, driver_id
        assert meta["vendor"] == "Mesa", driver_id
        archive_hashes.add(driver["sha256"])
        library_hashes.add(driver["librarySha256"])
        profiles = {name for name, p in rules["profiles"].items() if driver_id in p.get("choices", [])}
        assert profiles == set(provenance["profiles"]), (driver_id, profiles)
        assert all(driver_id not in p["drivers"] for p in rules["profiles"].values()), driver_id
        if driver_id == RUTABAGA:
            assert provenance["url"].endswith("/gamesir/Turnip-Rutabaga-v3.zip")
            assert meta["name"] == "Rutabaga-26.10.10"
        else:
            tag = "v" + driver_id.removeprefix("turnip-banner-").removesuffix("-a8xx")
            assert tag in TAGS
            assert provenance["url"].endswith(f"/{tag}/Turnip-{tag}-A8xx.zip")
            assert meta["name"] == f"Mesa Turnip {tag}-A8xx"
        if args.archives is None:
            continue
        path = args.archives / provenance["url"].rsplit("/", 1)[1]
        assert hashlib.sha256(path.read_bytes()).hexdigest() == driver["sha256"], path
        with zipfile.ZipFile(path) as archive:
            assert archive.testzip() is None, path
            assert set(archive.namelist()) == {"meta.json", driver["libraryName"]}, path
            assert json.loads(archive.read("meta.json")) == meta, path
            library = archive.read(driver["libraryName"])
        assert hashlib.sha256(library).hexdigest() == driver["librarySha256"], path
        assert library[:6] == b"\x7fELF\x02\x01", path  # ELF64 little endian
        assert struct.unpack_from("<H", library, 18)[0] == 183, path  # AArch64
        for chip in provenance["chipTableEvidence"]:
            assert struct.unpack_from("<Q", library, chip["fileOffset"])[0] == int(chip["chipId"], 16), path
        with tempfile.TemporaryDirectory(prefix="elf-check-", dir=args.archives) as work:
            extracted = Path(work) / driver["libraryName"]
            extracted.write_bytes(library)
            elf = subprocess.check_output(["readelf", "-d", "-n", "--dyn-syms", "--wide", str(extracted)], text=True)
        assert " HMI" in elf and "[libc.so]" in elf and "libc.so.6" not in elf, path
        assert "Build ID: " + provenance["elfBuildId"] in elf, path
    assert len(archive_hashes) == 7 and len(library_hashes) == 7, "no byte-identical releases to deduplicate"
    print("Optional Turnip provenance OK (7 distinct releases" +
          (", archive/library/metadata/ELF checks passed)" if args.archives else ")"))


if __name__ == "__main__":
    main()
