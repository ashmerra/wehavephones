#!/usr/bin/env python3
"""Host self-test for the bundled WeHavePhones runtime profile JSON + assets.

Parses app/src/wehavephones/assets/wehavephones/wehavephones-runtime-profiles.json and
asserts the verified single-runtime contract:

  * mode/default/single/handoff profile names
  * every ntdll/files asset exists under the bundled assets and its sha256
    matches the profile entry
  * p10-ge34 carries the verified Proton 10 copysafe ntdll pair and the
    three WoW DLLs with the expected targets
  * no profile replaces the imagefs GnuTLS library
  * the p10-ge34 layer download URL/sha256 match
    out/wehavephones-runtime/artifacts.json when that manifest is present

Run via scripts/test-wehavephones-runtime.sh. Exits non-zero on any failed check.
"""

import hashlib
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
PROFILE_JSON = ROOT / "app/src/wehavephones/assets/wehavephones/wehavephones-runtime-profiles.json"
ASSET_ROOT = ROOT / "app/src/wehavephones/assets/wehavephones"
ARTIFACTS_JSON = ROOT / "out/wehavephones-runtime/artifacts.json"

PASSED = 0
FAILED = 0


def check(condition, label, detail=""):
    global PASSED, FAILED
    if condition:
        PASSED += 1
        print(f"  PASS {label}")
    else:
        FAILED += 1
        print(f"  FAIL {label}" + (f" -- {detail}" if detail else ""))
    return condition


def expect(value, wanted, label):
    return check(value == wanted, label, f"got {value!r}, want {wanted!r}")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


EXPECTED_TOP = {
    "schemaVersion": 2,
    "mode": "single",
    "defaultProfile": "p10-ge34",
    "singleStageProfile": "p10-ge34",
}

EXPECTED_P10_GE34_NTDLL = [
    ("ntdll/ntdll-grace-copysafe-10.0-34.dll",
     "opt/{wineVersion}/lib/wine/aarch64-windows/ntdll.dll",
     "09adc21f8400fe3d2c46e227d1c423e0e06a91b338b5343beddc0da014df9cd8"),
    ("ntdll/ntdll-grace-copysafe-10.0-34.dll",
     "container:.wine/drive_c/windows/system32/ntdll.dll",
     "09adc21f8400fe3d2c46e227d1c423e0e06a91b338b5343beddc0da014df9cd8"),
    ("ntdll/ntdll-grace-copysafe-10.0-34.so",
     "opt/{wineVersion}/lib/wine/aarch64-unix/ntdll.so",
     "9f3e55a032b2a22fb025676b9c010ec97a454c84b8d955036d452699b3b0cd16"),
]

EXPECTED_P10_GE34_FILES = [
    ("dll/arm64-vkd3d.dll",
     "container:.wine/drive_c/arm64-vkd3d.dll",
     "f1c11399de6db045840b8a3334a6770c8ca5f706e8110c89b9846266f6770709"),
    ("dll/arm64-libc++.dll",
     "container:.wine/drive_c/arm64-libc++.dll",
     "ed9edb58ea9f8ed633082e3636e130bac5b05b5d5c1aa4ed05f53780725b6126"),
    ("dll/arm64-libunwind.dll",
     "container:.wine/drive_c/arm64-libunwind.dll",
     "535c6c8626c75f2b57cba17e0b550131d5fd699119d274290116fbe31e5b6046"),
]


def artifact_tuples(profile, key):
    return [(entry.get("asset", ""), entry.get("target", ""), entry.get("sha256", ""))
            for entry in profile.get(key, [])]


def main():
    print("[wehavephones-runtime-selftest] profile:", PROFILE_JSON.relative_to(ROOT))

    if not check(PROFILE_JSON.is_file(), "bundled profile JSON exists"):
        return 1
    try:
        data = json.loads(PROFILE_JSON.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        check(False, "bundled profile JSON parses", str(error))
        return 1
    check(True, "bundled profile JSON parses")

    print("[wehavephones-runtime-selftest] top-level contract")
    for key, wanted in EXPECTED_TOP.items():
        expect(data.get(key), wanted, f"{key}")

    profiles = data.get("profiles", {})
    expect(sorted(profiles), ["p10-ge34"], "bundled profiles")

    p10 = profiles.get("p10-ge34", {})
    print("[wehavephones-runtime-selftest] p10-ge34 (verified single runtime)")
    expect(p10.get("wineVersion"), "proton-10.0-34-arm64ec", "p10-ge34 wineVersion")
    expect(p10.get("bnetFlags"), "--disable-gpu --enable-features=NetworkServiceInProcess2", "p10-ge34 bnetFlags")
    expect(artifact_tuples(p10, "ntdll"), EXPECTED_P10_GE34_NTDLL, "p10-ge34 ntdll pair")
    expect(artifact_tuples(p10, "files"), EXPECTED_P10_GE34_FILES, "p10-ge34 WoW DLL files")

    layer = p10.get("layer", {})
    expect(layer.get("name"), "proton-10.0-34-arm64ec", "p10-ge34 layer name")
    expect(layer.get("archive"), "GE-proton-10.0-34-arm64ec.wcp", "p10-ge34 layer archive")
    expect(layer.get("sizeLabel"), "110 MB", "p10-ge34 layer size label")
    check(layer.get("url", "").startswith("https://github.com/"),
          "p10-ge34 layer download URL", layer.get("url", ""))
    expect(layer.get("sha256"),
           "50344ec9fa1f37ae22f5b1a5d509cb50cac818fe15f0ecc4fd187e8f97969110",
           "p10-ge34 layer sha256")

    print("[wehavephones-runtime-selftest] no profile replaces the imagefs GnuTLS library")
    gnutls_targets = [f"{name}.{kind} {target}"
                      for name, profile in profiles.items()
                      for kind in ("ntdll", "files")
                      for _, target, _ in artifact_tuples(profile, kind)
                      if "libgnutls" in target]
    check(not gnutls_targets, "GnuTLS stays the imagefs library", ", ".join(gnutls_targets))

    print("[wehavephones-runtime-selftest] referenced assets exist and sha256 matches")
    referenced = set()
    for name, profile in profiles.items():
        for kind in ("ntdll", "files"):
            for asset, target, digest in artifact_tuples(profile, kind):
                if not check(bool(asset) and bool(target), f"{name}.{kind} entry fields"):
                    continue
                check(bool(digest), f"{name}.{kind} {asset} pins a sha256")
                path = ASSET_ROOT / asset
                if not check(path.is_file(), f"{name}.{kind} asset exists: {asset}"):
                    continue
                actual = sha256(path)
                expect(actual, digest, f"{name}.{kind} sha256: {asset}")
                referenced.add(asset)

    print("[wehavephones-runtime-selftest] bundled artifacts are all referenced")
    bundled = sorted(
        str(path.relative_to(ASSET_ROOT))
        for folder in ("ntdll", "dll")
        for path in (ASSET_ROOT / folder).glob("*")
        if path.is_file()
    )
    for asset in bundled:
        check(asset in referenced, f"bundled asset referenced by a profile: {asset}")

    if ARTIFACTS_JSON.is_file():
        print("[wehavephones-runtime-selftest] cross-check out/wehavephones-runtime/artifacts.json")
        manifest = json.loads(ARTIFACTS_JSON.read_text(encoding="utf-8"))
        roles = {entry.get("role"): entry for entry in manifest.get("binaries", [])}
        wine_layer = roles.get("wine-layer", {})
        expect(layer.get("url"), wine_layer.get("source_url"), "layer URL matches artifact manifest")
        expect(layer.get("sha256"), wine_layer.get("sha256"), "layer sha256 matches artifact manifest")
        for role, asset, source_name in (
            ("ntdll-pe-patched", "ntdll/ntdll-grace-copysafe-10.0-34.dll",
             "ntdll-grace-copysafe-10.0-34.dll"),
            ("ntdll-unix-patched", "ntdll/ntdll-grace-copysafe-10.0-34.so",
             "ntdll-grace-copysafe-10.0-34.so"),
            ("wow-d3d12", "dll/arm64-vkd3d.dll", "d3d12.dll"),
            ("wow-libcxx", "dll/arm64-libc++.dll", "libc++.dll"),
            ("wow-libunwind", "dll/arm64-libunwind.dll", "libunwind.dll"),
        ):
            entry = roles.get(role, {})
            source = entry.get("source", "")
            if check(source.endswith(source_name),
                      f"manifest {role} names the bundled {asset.split('/')[-1]}"):
                expect(sha256(ASSET_ROOT / asset), entry.get("sha256"),
                       f"manifest {role} sha256")
    else:
        print("[wehavephones-runtime-selftest] skip artifacts.json cross-check (out/ not present)")

    print(f"[wehavephones-runtime-selftest] {PASSED} passed, {FAILED} failed")
    if FAILED:
        print("[wehavephones-runtime-selftest] RESULT: FAIL")
        return 1
    print("[wehavephones-runtime-selftest] RESULT: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
