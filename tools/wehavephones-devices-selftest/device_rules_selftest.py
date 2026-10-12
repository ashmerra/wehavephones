#!/usr/bin/env python3
"""Checks the bundled device rules (app/src/wehavephones/assets/wehavephones/device-rules.json).

  device_rules_selftest.py                run every check, including the signature
  device_rules_selftest.py --validate     schema and bundled-asset checks only (used by sign-device-rules.sh)
  device_rules_selftest.py --print-embedded-key   base64 public key from DeviceRulesKey.java

The validation mirrors DeviceRules.parse: schema, unique ids, every rule's profile and every
profile's drivers exist, the last rule is the catch-all, chip id and model patterns are well
formed. Bundled adrenotools drivers must exist with matching archive and library sha256.
"""
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "app/src/wehavephones/assets/wehavephones")
RULES = os.path.join(ASSETS, "device-rules.json")
SIGNATURE = RULES + ".sig"
MAIN_ASSETS = os.path.join(ROOT, "app/src/main/assets")
KEY_SOURCE = os.path.join(ROOT, "app/src/wehavephones/java/com/wehavephones/mobile/devices/DeviceRulesKey.java")

SUPPORTED_SCHEMA = 1
ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
SHA256 = re.compile(r"[0-9a-f]{64}$")
ASSET = re.compile(r"graphics_driver/[A-Za-z0-9._-]+\.(tzst|zip)$")
LIBRARY = re.compile(r"[A-Za-z0-9._-]+\.so$")
ENV_KEY = re.compile(r"[A-Za-z_][A-Za-z0-9_]*$")
API_ARG = re.compile(r"-[A-Za-z0-9]{1,16}$")
MODEL = re.compile(r"([0-9]{3})(?:-([0-9]{3}))?$")
CHIP = re.compile(r"0[xX](?:[0-9A-Fa-f]{2}){1,8}$")
VENDORS = {"adreno", "mali", "xclipse", "powervr", "other", "unknown"}
MATCH_KEYS = {"gpuVendor", "adrenoSeries", "adrenoModel", "chipId", "socModel", "manufacturer", "minSdk", "maxSdk"}
BLOCKED_ENV = ("LD_", "ADRENOTOOLS_", "VK_")

failures = []


def fail(message):
    failures.append(message)
    print("FAIL: " + message)


def is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def validate(doc):
    """Returns a list of problems."""
    problems = []

    def bad(message):
        problems.append(message)

    if not isinstance(doc, dict):
        return ["root must be an object"]
    if not is_int(doc.get("schema")) or not 1 <= doc["schema"] <= SUPPORTED_SCHEMA:
        bad("schema must be an integer from 1 to %d" % SUPPORTED_SCHEMA)
    if not is_int(doc.get("rulesVersion")) or doc["rulesVersion"] < 1:
        bad("rulesVersion must be an integer >= 1")
    if "minAppVersionCode" in doc and (not is_int(doc["minAppVersionCode"]) or doc["minAppVersionCode"] < 0):
        bad("minAppVersionCode must be an integer >= 0")

    drivers = doc.get("drivers")
    if not isinstance(drivers, dict):
        bad("drivers must be an object")
        drivers = {}
    system = drivers.get("system")
    if not isinstance(system, dict) or system.get("type") != "system":
        bad("drivers.system of type system is required")
    for driver_id, driver in drivers.items():
        where = "drivers." + driver_id
        if not ID.match(driver_id):
            bad(where + ": invalid id")
        if not isinstance(driver, dict):
            bad(where + ": must be an object")
            continue
        kind = driver.get("type")
        if kind == "system":
            if driver_id != "system":
                bad(where + ": only 'system' may be of type system")
            continue
        if kind != "adrenotools":
            bad(where + ": unknown type %r" % (kind,))
            continue
        if driver_id == "system":
            bad(where + ": 'system' must be of type system")
        asset, url = driver.get("asset"), driver.get("url")
        if not asset and not url:
            bad(where + ": needs an asset or a url")
        if asset and not (isinstance(asset, str) and ASSET.match(asset)):
            bad(where + ": invalid asset path")
        if url and not (isinstance(url, str) and url.startswith("https://") and " " not in url):
            bad(where + ": url must be https")
        for field in ("sha256", "librarySha256"):
            if not (isinstance(driver.get(field), str) and SHA256.match(driver[field].lower())):
                bad(where + ": invalid " + field)
        if not (isinstance(driver.get("libraryName"), str) and LIBRARY.match(driver["libraryName"])):
            bad(where + ": invalid libraryName")

    profiles = doc.get("profiles")
    if not isinstance(profiles, dict) or not profiles:
        bad("profiles must be a non-empty object")
        profiles = {}
    for name, profile in profiles.items():
        where = "profiles." + name
        if not ID.match(name):
            bad(where + ": invalid name")
        if not isinstance(profile, dict):
            bad(where + ": must be an object")
            continue
        if profile.get("status") not in ("tested", "community", "untested"):
            bad(where + ": unknown status")
        names = profile.get("drivers")
        if not isinstance(names, list) or not names:
            bad(where + ": drivers must be a non-empty array")
            names = []
        if len(set(map(str, names))) != len(names):
            bad(where + ": duplicate driver")
        for driver_id in names:
            if driver_id not in drivers:
                bad(where + ": unknown driver %r" % (driver_id,))
        env = profile.get("env") or {}
        if not isinstance(env, dict):
            bad(where + ": env must be an object")
            env = {}
        for key, value in env.items():
            if not ENV_KEY.match(key) or key.startswith(BLOCKED_ENV):
                bad(where + ": env name %r not allowed" % key)
            if not isinstance(value, str) or re.search(r"\s", value):
                bad(where + ": env %s must be a string without whitespace" % key)
        if "vulkanCompatLayer" in profile and not isinstance(profile["vulkanCompatLayer"], bool):
            bad(where + ": vulkanCompatLayer must be a boolean")
        api = profile.get("wowApiArg")
        if api is not None and not (isinstance(api, str) and API_ARG.match(api)):
            bad(where + ": invalid wowApiArg")
        choices = profile.get("choices")
        if choices is not None:
            if not isinstance(choices, list):
                bad(where + ": choices must be an array")
                choices = []
            if len(set(map(str, choices))) != len(choices):
                bad(where + ": duplicate choice")
            for driver_id in choices:
                if driver_id not in drivers:
                    bad(where + ": unknown choice %r" % (driver_id,))
                if driver_id in names:
                    bad(where + ": choice %r is already in drivers" % (driver_id,))

    rules = doc.get("rules")
    if not isinstance(rules, list) or not rules:
        bad("rules must be a non-empty array")
        rules = []
    seen = set()
    for index, rule in enumerate(rules):
        where = "rules[%d]" % index
        if not isinstance(rule, dict):
            bad(where + ": must be an object")
            continue
        rule_id = rule.get("id")
        if not (isinstance(rule_id, str) and ID.match(rule_id)):
            bad(where + ": invalid id")
        elif rule_id in seen:
            bad(where + ": duplicate id " + rule_id)
        seen.add(rule_id)
        if rule.get("profile") not in profiles:
            bad("%s (%s): unknown profile %r" % (where, rule_id, rule.get("profile")))
        match = rule.get("match")
        if not isinstance(match, dict):
            bad(where + ": match must be an object")
            continue
        if match and index == len(rules) - 1:
            bad("the last rule must be the catch-all with an empty match")
        if not match and index != len(rules) - 1:
            bad("catch-all rule %s must be last" % rule_id)
        for key, value in match.items():
            if key not in MATCH_KEYS:
                bad("%s: unknown match key %s" % (where, key))
            elif key in ("minSdk", "maxSdk"):
                if not is_int(value):
                    bad("%s: %s must be an integer" % (where, key))
            elif not isinstance(value, list) or not value:
                bad("%s: %s must be a non-empty array" % (where, key))
            elif key == "gpuVendor":
                for item in value:
                    if item not in VENDORS:
                        bad("%s: unknown gpuVendor %r" % (where, item))
            elif key == "adrenoSeries":
                if not all(is_int(item) and 1 <= item <= 99 for item in value):
                    bad(where + ": adrenoSeries must be small positive integers")
            elif key == "adrenoModel":
                for item in value:
                    m = MODEL.match(item) if isinstance(item, str) else None
                    if not m or (m.group(2) and int(m.group(2)) < int(m.group(1))):
                        bad("%s: invalid adrenoModel %r" % (where, item))
            elif key == "chipId":
                for item in value:
                    if not (isinstance(item, str) and CHIP.match(item)):
                        bad("%s: invalid chipId %r" % (where, item))
            else:
                if not all(isinstance(item, str) and item for item in value):
                    bad("%s: %s must be non-empty strings" % (where, key))
    if rules and isinstance(rules[-1], dict) and rules[-1].get("match"):
        pass  # already reported above
    return problems


def check_assets(doc):
    """Every bundled adrenotools driver exists with matching archive and library sha256."""
    problems = []
    unzstd = shutil.which("unzstd")
    for driver_id, driver in doc["drivers"].items():
        if driver.get("type") != "adrenotools" or not driver.get("asset"):
            continue
        path = os.path.join(MAIN_ASSETS, driver["asset"])
        if not os.path.isfile(path):
            problems.append("%s: asset %s is missing" % (driver_id, driver["asset"]))
            continue
        with open(path, "rb") as handle:
            archive_sha = hashlib.sha256(handle.read()).hexdigest()
        if archive_sha != driver["sha256"].lower():
            problems.append("%s: archive sha256 is %s, rules say %s" % (driver_id, archive_sha, driver["sha256"]))
            continue
        if not path.endswith(".tzst"):
            continue
        if not unzstd:
            problems.append("unzstd is needed to check %s" % driver_id)
            continue
        work = tempfile.mkdtemp(prefix="device-rules-driver-")
        try:
            tar_path = os.path.join(work, "driver.tar")
            with open(tar_path, "wb") as out:
                subprocess.run([unzstd, "-c", path], stdout=out, check=True)
            out_dir = os.path.join(work, "x")
            os.mkdir(out_dir)
            subprocess.run(["tar", "-xf", tar_path, "-C", out_dir], check=True)
            library = os.path.join(out_dir, driver["libraryName"])
            if not os.path.isfile(library):
                problems.append("%s: %s is not in the archive" % (driver_id, driver["libraryName"]))
                continue
            with open(library, "rb") as handle:
                library_sha = hashlib.sha256(handle.read()).hexdigest()
            if library_sha != driver["librarySha256"].lower():
                problems.append("%s: library sha256 is %s, rules say %s" % (driver_id, library_sha, driver["librarySha256"]))
            meta = os.path.join(out_dir, "meta.json")
            if not os.path.isfile(meta):
                problems.append("%s: archive has no meta.json" % driver_id)
            elif json.load(open(meta)).get("libraryName") != driver["libraryName"]:
                problems.append("%s: meta.json libraryName differs from the rules" % driver_id)
        finally:
            shutil.rmtree(work, ignore_errors=True)
    return problems


def embedded_key():
    with open(KEY_SOURCE, encoding="utf-8") as handle:
        source = handle.read()
    match = re.search(r"PUBLIC_KEY_BASE64\s*=\s*((?:\s*\"[^\"]*\"\s*\+?)+);", source)
    if not match:
        raise SystemExit("PUBLIC_KEY_BASE64 not found in " + KEY_SOURCE)
    return "".join(re.findall(r"\"([^\"]*)\"", match.group(1)))


def verify_signature():
    problems = []
    if not os.path.isfile(SIGNATURE):
        return ["device-rules.json.sig is missing"]
    work = tempfile.mkdtemp(prefix="device-rules-sig-")
    try:
        der_key = os.path.join(work, "pub.der")
        with open(der_key, "wb") as handle:
            handle.write(base64.b64decode(embedded_key()))
        pem = os.path.join(work, "pub.pem")
        subprocess.run(["openssl", "pkey", "-pubin", "-inform", "DER", "-in", der_key, "-out", pem], check=True)
        sig = os.path.join(work, "sig.der")
        with open(SIGNATURE, "rb") as handle:
            with open(sig, "wb") as out:
                out.write(base64.b64decode(handle.read().strip()))
        result = subprocess.run(["openssl", "dgst", "-sha256", "-verify", pem, "-signature", sig, RULES],
                                capture_output=True, text=True)
        if result.returncode != 0:
            problems.append("signature does not verify against the embedded public key: " + result.stdout.strip() + result.stderr.strip())
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return problems


def load_rules():
    with open(RULES, "rb") as handle:
        raw = handle.read()
    return json.loads(raw.decode("utf-8"))


def expect_invalid(label, mutate):
    doc = load_rules()
    mutate(doc)
    if not validate(doc):
        fail("validator accepted an invalid file: " + label)


def negative_cases():
    expect_invalid("schema too high", lambda d: d.update(schema=2))
    expect_invalid("string rulesVersion", lambda d: d.update(rulesVersion="1"))
    expect_invalid("missing system driver", lambda d: d["drivers"].pop("system"))
    expect_invalid("unknown profile in rule", lambda d: d["rules"][0].update(profile="nope"))
    expect_invalid("unknown driver in profile", lambda d: d["profiles"]["mali"].update(drivers=["nope"]))
    expect_invalid("duplicate rule id", lambda d: d["rules"][1].update(id=d["rules"][0]["id"]))
    expect_invalid("no catch-all", lambda d: d["rules"].pop())
    expect_invalid("catch-all not last", lambda d: d["rules"].insert(0, d["rules"].pop()))
    expect_invalid("bad chip id", lambda d: d["rules"][1]["match"].update(chipId=["0x123"]))
    expect_invalid("reversed model range", lambda d: d["rules"][0]["match"].update(adrenoModel=["849-840"]))
    expect_invalid("blocked env", lambda d: d["profiles"]["adreno-7xx"]["env"].update(LD_PRELOAD="x"))
    expect_invalid("unknown choice", lambda d: d["profiles"]["adreno-7xx"].update(choices=["nope"]))
    expect_invalid("choice already in drivers", lambda d: d["profiles"]["adreno-7xx"].update(choices=["system"]))


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "--print-embedded-key":
        print(embedded_key())
        return 0
    try:
        doc = load_rules()
    except (OSError, ValueError) as error:
        print("FAIL: cannot read device-rules.json: %s" % error)
        return 1

    for problem in validate(doc):
        fail(problem)
    if not failures:
        for problem in check_assets(doc):
            fail(problem)
        print("device-rules.json: v%s, %d drivers, %d profiles, %d rules" % (
            doc["rulesVersion"], len(doc["drivers"]), len(doc["profiles"]), len(doc["rules"])))
    if mode != "--validate":
        negative_cases()
        for problem in verify_signature():
            fail(problem)
        if not failures:
            print("signature verifies against the embedded public key")
    if failures:
        print("%d problem(s)" % len(failures))
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
