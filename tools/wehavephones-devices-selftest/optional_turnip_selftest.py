#!/usr/bin/env python3
"""Check optional Turnip provenance, and optionally every downloaded upstream ZIP.

Pass --archives DIR to recheck the actual packages without fetching during offline host tests.
DIR holds one batch's ZIPs. The check covers every manifest whose archives are all in DIR.
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
RUTABAGA = "turnip-gamesir-rutabaga-v3"
V38 = {"turnip-gen8-stevenmxz-v38": ("Turnip_Gen8_V38.zip", "Turnip Gen8 V38"),
       "turnip-gen8-stevenmxz-v38-patched": ("Turnip_Gen8_V38_Patched.zip", "Turnip Gen8 V38-patched")}
PRE = "a8xx-850-845-pre1"
A8XX = ["adreno-8xx", "adreno-84x"]
# Requested releases per manifest. v27.0.0-20261025 is left out: its library equals 20261024-r7.
BATCH_20261027 = ([f"v27.0.0-20261016-r{r}" for r in (5, 6, 7)] +
    ["v27.0.0-20261017", "v27.0.0-20261017-r2"] +
    [f"v27.0.0-20261018{s}" for s in ("", "-r2", "-r3")] +
    [f"v27.0.0-20261019{s}" for s in ("", "-r2", "-r3")] +
    [f"v27.0.0-20261020{s}" for s in ("", "-r2", "-r3", "-r4", "-r5")] +
    [f"v27.0.0-20261021{s}" for s in ("", "-r2", "-r3", "-r4", "-r5", "-r6")] +
    ["v27.0.0-20261022"] +
    [f"v27.0.0-20261023-r{r}" for r in (3, 4, 5, 6)] +
    [f"v27.0.0-20261024{s}" for s in ("", "-r2", "-r3", "-r4", "-r5", "-r6", "-r7")] +
    [f"v27.0.0-20261025{s}" for s in ("-r2", "-r3", "-r5")] +
    [f"v27.0.0-20261026{s}" for s in ("", "-r2", "-r3")] +
    [f"v27.0.0-20261027{s}" for s in ("", "-r2", "-r3")])
MANIFESTS = {
    "turnip-optional-20261016.json": [f"v26.3.0-20261015-r{r}" for r in range(4, 8)] + [
        "v27.0.0-20261016-r3", "v27.0.0-20261016-r4"],
    "turnip-optional-20261027.json": BATCH_20261027,
}


def banner_id(tag):
    return f"turnip-banner-{tag[1:]}-a8xx"


def expected_ids(name, tags):
    if name == "turnip-optional-20261016.json":
        return [RUTABAGA] + [banner_id(tag) for tag in tags]
    return list(V38) + [banner_id(tag) for tag in tags] + ["turnip-banner-" + PRE]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archives", type=Path)
    args = parser.parse_args()
    rules = json.loads((ASSETS / "device-rules.json").read_text())
    archive_hashes, library_hashes = set(), set()
    checked = []
    for name, tags in MANIFESTS.items():
        manifest = json.loads((ASSETS / "graphics" / name).read_text())
        expected = expected_ids(name, tags)
        assert list(manifest["drivers"]) == expected, (name, "exactly the requested releases")
        archives = args.archives
        if archives is not None and not all((archives / p["url"].rsplit("/", 1)[1]).is_file()
                                            for p in manifest["drivers"].values()):
            archives = None
        if archives is not None:
            checked.append(name)
        for driver_id, provenance in manifest["drivers"].items():
            check_driver(rules, driver_id, provenance, tags, archives)
            archive_hashes.add(provenance["sha256"])
            library_hashes.add(provenance["librarySha256"])
    # Picker order: each batch is appended after the earlier choices, in manifest order.
    for profile in A8XX:
        choices = rules["profiles"][profile]["choices"]
        batch = [d for d in expected_ids("turnip-optional-20261027.json", BATCH_20261027)
                 if profile in rules_profiles(rules, d)]
        assert choices[-len(batch):] == batch, (profile, "new choices appended in order")
    total = sum(len(expected_ids(n, t)) for n, t in MANIFESTS.items())
    assert len(archive_hashes) == total and len(library_hashes) == total, "no byte-identical releases to deduplicate"
    if args.archives is not None and not checked:
        raise SystemExit(f"no complete manifest batch in {args.archives}")
    print(f"Optional Turnip provenance OK ({total} distinct releases" +
          (f", archive/library/metadata/ELF checks passed for {', '.join(checked)})" if checked else ")"))


def rules_profiles(rules, driver_id):
    return {name for name, p in rules["profiles"].items() if driver_id in p.get("choices", [])}


def check_driver(rules, driver_id, provenance, tags, archives):
    driver = rules["drivers"][driver_id]
    for key in ("url", "sha256", "libraryName", "librarySha256"):
        assert driver[key] == provenance[key], (driver_id, key)
    assert driver["asset"] is None, driver_id
    assert driver["libraryName"] == "libvulkan_freedreno.so", driver_id
    meta = provenance["upstreamMeta"]
    assert meta["libraryName"] == driver["libraryName"], driver_id
    assert meta["schemaVersion"] == 1 and meta["minApi"] == 28, driver_id
    assert meta["vendor"] == "Mesa", driver_id
    profiles = rules_profiles(rules, driver_id)
    assert profiles == set(provenance["profiles"]), (driver_id, profiles)
    assert "Experimental" in driver["description"] and "unverified" in driver["description"], driver_id
    assert all(driver_id not in p["drivers"] for p in rules["profiles"].values()), driver_id
    if driver_id == RUTABAGA:
        assert provenance["url"].endswith("/gamesir/Turnip-Rutabaga-v3.zip")
        assert meta["name"] == "Rutabaga-26.10.10"
    elif driver_id in V38:
        assert provenance["url"].endswith("/v27.0.0-R1/" + V38[driver_id][0]), driver_id
        assert meta["name"] == V38[driver_id][1], driver_id
        assert profiles == set(A8XX), driver_id
    elif driver_id == "turnip-banner-" + PRE:
        assert provenance["url"].endswith(f"/{PRE}/Turnip-{PRE}-A8xx.zip")
        assert meta["name"] == "Mesa Turnip A8xx 850-845 test pre1"
        assert profiles == {"adreno-84x"}, "the A850/A845 test build is offered only where 840-859 match"
    else:
        tag = "v" + driver_id.removeprefix("turnip-banner-").removesuffix("-a8xx")
        assert tag in tags
        assert provenance["url"].endswith(f"/{tag}/Turnip-{tag}-A8xx.zip")
        assert meta["name"] == f"Mesa Turnip {tag}-A8xx"
        assert profiles == set(A8XX), driver_id
    if archives is None:
        return
    path = archives / provenance["url"].rsplit("/", 1)[1]
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
    with tempfile.TemporaryDirectory(prefix="elf-check-", dir=archives) as work:
        extracted = Path(work) / driver["libraryName"]
        extracted.write_bytes(library)
        elf = subprocess.check_output(["readelf", "-d", "-n", "--dyn-syms", "--wide", str(extracted)], text=True)
    assert " HMI" in elf and "[libc.so]" in elf and "libc.so.6" not in elf, path
    assert "Build ID: " + provenance["elfBuildId"] in elf, path


if __name__ == "__main__":
    main()
