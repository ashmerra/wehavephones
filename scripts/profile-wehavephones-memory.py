#!/usr/bin/env python3
"""Profile Android memory during a WeHavePhones (Battle.net + WoW) session.

Read-only host-side sampler. It never writes to app data and never requires the
app to be modified. Per sample it reads /proc/meminfo, /proc/pressure/memory,
selected /proc/vmstat counters and `ps -A`. On a slower cadence it also captures
`dumpsys meminfo <pkg>` (per-process PSS, app summary). Optionally it streams the
events log (lmkd/am_kill) and records a Perfetto trace (meminfo/PSI/vmstat plus
optional heapprofd).

Phase markers:
    Run the sampler in one terminal::

        python3 scripts/profile-wehavephones-memory.py --minutes 10 --output out/run.jsonl

    Then label phases from anywhere while it runs::

        python3 scripts/profile-wehavephones-memory.py --output out/run.jsonl --mark bnet-signed-in

    Markers are appended to <output>.marks.jsonl; the sampler also attaches them
    in its console output. A FIFO can be used instead with --marker-fifo.

Analysis:
    The sampler prints a compact report when it finishes and writes
    <output>.summary.json. Re-run the report later with --report.

Examples:
    # 15 minutes at 1 Hz, Perfetto trace, fail if MemAvailable drops below 600 MB
    python3 scripts/profile-wehavephones-memory.py --minutes 15 --perfetto \
        --min-memavailable-mb 600 --fail-on-lmkd-kill --output out/wehavephones.jsonl

    # self test without a device (synthetic samples)
    python3 scripts/profile-wehavephones-memory.py --self-test

ADB_BIN, ADB_SERIAL and ANDROID_DEVICE are respected (also from local.env, see
scripts/wehavephones-device.py); --device wins. With none of them set, the only
device adb lists is used. WEHAVEPHONES_PACKAGE sets the default --package.
"""

import argparse
import datetime
import importlib.util
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("wehavephones_device", ROOT / "scripts/wehavephones-device.py")
dev = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(dev)

DEFAULT_PACKAGE = dev.PKG
DEFAULT_PROCESS_FILTER = (
    r"(battle|agent|wow|world of warcraft|wine|wehavephones|winhandler|proot|box64|"
    r"fex|explorer|services|plugplay|rpcss|winedevice|svchost|conhost|"
    r"cmd\.exe|start\.exe|steam)"
)

CURATED_MEMINFO = (
    "MemTotal", "MemFree", "MemAvailable", "Buffers", "Cached", "SwapCached",
    "AnonPages", "Mapped", "Shmem", "Slab", "SReclaimable", "SUnreclaim",
    "KernelStack", "PageTables", "SwapTotal", "SwapFree",
)
CURATED_VMSTAT = (
    "pswpin", "pswpout", "pgfault", "pgmajfault", "pgscan_kswapd",
    "pgscan_direct", "pgsteal_kswapd", "pgsteal_direct", "oom_kill",
)
KILL_PATTERN = re.compile(r"(am_kill|lowmemorykiller|lmkd|am_proc_died)", re.I)

SAMPLE_COMMAND = (
    "cat /proc/meminfo; echo ==PSI==; cat /proc/pressure/memory 2>/dev/null; "
    "echo ==VMSTAT==; cat /proc/vmstat; echo ==PS==; ps -A -o PID,PPID,RSS,NAME 2>/dev/null"
)
DEVICE_INFO_COMMAND = (
    "getprop ro.product.model; getprop ro.build.version.release; "
    "getprop ro.build.version.sdk; getprop ro.soc.model; cat /proc/meminfo | head -n 3"
)


def find_adb():
    override = os.environ.get("ADB_BIN")
    if override:
        return override
    local = ROOT / ".tools/android-sdk/platform-tools/adb"
    if local.is_file():
        return str(local)
    return "adb"


def resolve_device(explicit):
    return dev.resolve_serial(explicit, flag="--device")


def adb_command(adb, device, shell_command):
    return [adb, "-s", device, "shell", shell_command]


def run_adb(adb, device, shell_command, timeout):
    return subprocess.run(
        adb_command(adb, device, shell_command),
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=timeout,
    )


def parse_meminfo(text):
    values = {}
    for line in text.splitlines():
        if ":" not in line:
            continue
        key, rest = line.split(":", 1)
        key = key.strip()
        if key not in CURATED_MEMINFO:
            continue
        fields = rest.split()
        if not fields:
            continue
        try:
            values[key] = int(fields[0])
        except ValueError:
            continue
    return values


def parse_psi(text):
    result = {}
    for line in text.splitlines():
        fields = line.split()
        if not fields or fields[0] not in ("some", "full"):
            continue
        entry = {}
        for token in fields[1:]:
            if "=" not in token:
                continue
            key, value = token.split("=", 1)
            try:
                entry[key] = float(value)
            except ValueError:
                continue
        result[fields[0]] = entry
    return result


def parse_vmstat(text):
    values = {}
    for line in text.splitlines():
        fields = line.split()
        if len(fields) != 2 or fields[0] not in CURATED_VMSTAT:
            continue
        try:
            values[fields[0]] = int(fields[1])
        except ValueError:
            continue
    return values


def parse_ps_rows(text, pattern):
    rows = []
    for line in text.splitlines():
        fields = line.split(None, 3)
        if len(fields) != 4 or fields[0] == "PID":
            continue
        pid, ppid, rss, name = fields
        if not (pid.isdigit() and ppid.isdigit() and rss.isdigit()):
            continue
        if pattern.search(name):
            rows.append({"pid": int(pid), "ppid": int(ppid), "rss_kb": int(rss), "name": name})
    return rows


def parse_dumpsys_meminfo(text):
    result = {"app_summary": {}, "processes": []}
    summary = re.search(
        r"TOTAL PSS:\s*(\d+)\s+TOTAL RSS:\s*(\d+)\s+TOTAL SWAP PSS:\s*(\d+)", text)
    if summary:
        result["app_summary"] = {
            "total_pss_kb": int(summary.group(1)),
            "total_rss_kb": int(summary.group(2)),
            "total_swap_pss_kb": int(summary.group(3)),
        }
    current = None
    for line in text.splitlines():
        header = re.match(r"\*\* MEMINFO in pid (\d+) \[(.*)\]\s*\*\*", line)
        if header:
            current = {"pid": int(header.group(1)), "name": header.group(2),
                       "pss_kb": 0, "private_dirty_kb": 0, "private_clean_kb": 0,
                       "swap_pss_kb": 0, "rss_kb": 0}
            result["processes"].append(current)
            continue
        total = re.match(r"^\s*TOTAL\s*:?\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)", line)
        if total and current is not None:
            current["pss_kb"] = int(total.group(1))
            current["private_dirty_kb"] = int(total.group(2))
            current["private_clean_kb"] = int(total.group(3))
            current["swap_pss_kb"] = int(total.group(4))
            current["rss_kb"] = int(total.group(5))
    return result


def split_sections(output):
    sections, current, lines = {}, "meminfo", []
    for line in output.splitlines():
        if line in ("==PSI==", "==VMSTAT==", "==PS==", "==MEMINFO=="):
            sections[current] = "\n".join(lines)
            current = {"==PSI==": "psi", "==VMSTAT==": "vmstat",
                       "==PS==": "ps", "==MEMINFO==": "dumpsys"}[line]
            lines = []
        else:
            lines.append(line)
    sections[current] = "\n".join(lines)
    return sections


def check_device(adb, device, timeout):
    try:
        result = subprocess.run([adb, "-s", device, "get-state"],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True, timeout=timeout)
    except (subprocess.SubprocessError, OSError) as error:
        return False, str(error)
    state = (result.stdout or "").strip()
    if result.returncode != 0 or state != "device":
        detail = state or (result.stderr or "").strip() or "adb exit %d" % result.returncode
        return False, detail
    return True, state


def collect_device_info(adb, device, timeout):
    info = {"serial": device}
    try:
        result = run_adb(adb, device, DEVICE_INFO_COMMAND, timeout)
        lines = (result.stdout or "").splitlines()
        keys = ["model", "android", "sdk", "soc", "meminfo_line1", "meminfo_line2", "meminfo_line3"]
        for key, value in zip(keys, lines):
            info[key] = value.strip()
    except (subprocess.SubprocessError, OSError) as error:
        info["error"] = str(error)
    return info


def take_sample(adb, device, package, pattern, want_meminfo, timeout):
    sample = {"time": time.time()}
    try:
        command = SAMPLE_COMMAND
        if want_meminfo:
            command += "; echo ==MEMINFO==; dumpsys meminfo " + package
        result = run_adb(adb, device, command, timeout)
        if result.returncode != 0:
            sample["error"] = (result.stderr or "adb shell exit %d" % result.returncode).strip()
        sections = split_sections(result.stdout or "")
        sample["mem"] = parse_meminfo(sections.get("meminfo", ""))
        sample["psi"] = parse_psi(sections.get("psi", ""))
        sample["vmstat"] = parse_vmstat(sections.get("vmstat", ""))
        rows = parse_ps_rows(sections.get("ps", ""), pattern)
        sample["procs"] = rows
        if want_meminfo:
            sample["meminfo"] = parse_dumpsys_meminfo(sections.get("dumpsys", ""))
    except (subprocess.SubprocessError, OSError) as error:
        sample["error"] = str(error)
    return sample


def summarize_ps(rows):
    if not rows:
        return 0
    return sum(row["rss_kb"] for row in rows)


def append_mark(output_path, name, source="cli"):
    marks_path = pathlib.Path(str(output_path) + ".marks.jsonl")
    marks_path.parent.mkdir(parents=True, exist_ok=True)
    record = {"time": time.time(), "name": name, "source": source}
    with marks_path.open("a") as handle:
        handle.write(json.dumps(record) + "\n")
        handle.flush()
    return record


def write_fifo_mark(fifo_path, name):
    if not fifo_path:
        return False
    if not os.path.exists(fifo_path):
        return False
    try:
        fd = os.open(fifo_path, os.O_WRONLY | os.O_NONBLOCK)
    except OSError:
        return False
    try:
        os.write(fd, (name + "\n").encode())
        return True
    finally:
        os.close(fd)


def load_jsonl(path):
    records, bad = [], 0
    if not path.exists():
        return records, bad
    with path.open() as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError:
                bad += 1
    return records, bad


def marker_time(record):
    return float(record.get("time", 0))


def associate_markers(samples, marks):
    result = []
    for mark in marks:
        stamp = marker_time(mark)
        index = None
        for i, sample in enumerate(samples):
            if sample.get("time", 0) >= stamp:
                index = i
                break
        if index is None and samples:
            index = len(samples) - 1
        entry = dict(mark)
        entry["sample_index"] = index
        if index is not None and samples:
            sample = samples[index]
            entry["sample_time"] = sample.get("time")
            entry["mem_available_kb"] = sample.get("mem", {}).get("MemAvailable")
            entry["swap_free_kb"] = sample.get("mem", {}).get("SwapFree")
            entry["tracked_rss_kb"] = summarize_ps(sample.get("procs", []))
        result.append(entry)
    result.sort(key=marker_time)
    previous = None
    baseline = result[0] if result else None
    for entry in result:
        mem = entry.get("mem_available_kb")
        swap = entry.get("swap_free_kb")
        if previous is not None and mem is not None:
            entry["delta_memavailable_kb_vs_prev"] = mem - previous.get("mem_available_kb", mem)
            entry["delta_swapfree_kb_vs_prev"] = swap - previous.get("swap_free_kb", swap)
        elif previous is None:
            entry["delta_memavailable_kb_vs_prev"] = 0
            entry["delta_swapfree_kb_vs_prev"] = 0
        if baseline is not None and mem is not None and entry is not baseline and \
                baseline.get("mem_available_kb") is not None:
            entry["delta_memavailable_kb_vs_baseline"] = mem - baseline["mem_available_kb"]
            entry["delta_swapfree_kb_vs_baseline"] = swap - baseline.get("swap_free_kb", swap)
        previous = entry
    return result


def scan_kills(events_path, limit=25):
    found = []
    if not events_path.exists():
        return found
    try:
        with events_path.open(errors="replace") as handle:
            for line in handle:
                if KILL_PATTERN.search(line):
                    found.append(line.rstrip("\n"))
    except OSError:
        return found
    return found[-limit:]


def series_stats(samples, key):
    values = [(sample.get("time"), sample.get("mem", {}).get(key))
              for sample in samples if sample.get("mem", {}).get(key) is not None]
    if not values:
        return None
    numbers = [value for _, value in values]
    min_time, min_value = min(values, key=lambda item: item[1])
    return {
        "min": min_value, "min_time": min_time,
        "max": max(numbers), "mean": sum(numbers) // len(numbers),
        "first": values[0][1], "last": values[-1][1],
    }


def summarize(samples, marks, events_path, args):
    samples = [sample for sample in samples if sample.get("mem")]
    marks = associate_markers(samples, marks)
    summary = {
        "created": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "output": str(args.output),
        "samples": len(samples),
        "span_seconds": round(samples[-1]["time"] - samples[0]["time"], 1) if len(samples) > 1 else 0,
        "memavailable_kb": series_stats(samples, "MemAvailable"),
        "swapfree_kb": series_stats(samples, "SwapFree"),
        "memtotal_kb": series_stats(samples, "MemTotal"),
        "psi": {},
        "vmstat_delta": {},
        "processes": [],
        "app_pss": {},
        "meminfo_processes": [],
        "markers": marks,
        "kills": scan_kills(events_path),
    }

    for kind in ("some", "full"):
        stamps = []
        for sample in samples:
            entry = sample.get("psi", {}).get(kind)
            if entry:
                stamps.append((sample["time"], entry))
        if stamps:
            summary["psi"][kind] = {
                "avg10_max": max(entry.get("avg10", 0.0) for _, entry in stamps),
                "avg60_max": max(entry.get("avg60", 0.0) for _, entry in stamps),
                "avg300_max": max(entry.get("avg300", 0.0) for _, entry in stamps),
                "total_delta": round(stamps[-1][1].get("total", 0) - stamps[0][1].get("total", 0), 2),
            }

    for key in CURATED_VMSTAT:
        values = [(sample["time"], sample.get("vmstat", {}).get(key))
                  for sample in samples if sample.get("vmstat", {}).get(key) is not None]
        if len(values) > 1:
            summary["vmstat_delta"][key] = values[-1][1] - values[0][1]

    per_name = {}
    for sample in samples:
        for row in sample.get("procs", []):
            entry = per_name.setdefault(row["name"], {"samples": 0, "max_rss_kb": 0,
                                                      "sum_rss_kb": 0, "last_rss_kb": 0})
            entry["samples"] += 1
            entry["max_rss_kb"] = max(entry["max_rss_kb"], row["rss_kb"])
            entry["sum_rss_kb"] += row["rss_kb"]
            entry["last_rss_kb"] = row["rss_kb"]
    for name, entry in per_name.items():
        entry["mean_rss_kb"] = entry["sum_rss_kb"] // max(1, entry["samples"])
    summary["processes"] = sorted(per_name.items(), key=lambda item: -item[1]["max_rss_kb"])

    meminfo_samples = [sample for sample in samples if sample.get("meminfo")]
    if meminfo_samples:
        latest = meminfo_samples[-1]["meminfo"]
        summary["app_pss"] = latest.get("app_summary", {})
        summary["meminfo_processes"] = latest.get("processes", [])
        pss_values = [sample["meminfo"].get("app_summary", {}).get("total_pss_kb", 0)
                      for sample in meminfo_samples]
        if pss_values:
            summary["app_pss"]["max_total_pss_kb"] = max(pss_values)

    checks = []
    minimum = args.min_memavailable_mb
    if minimum and summary["memavailable_kb"]:
        floor = summary["memavailable_kb"]["min"] / 1024.0
        checks.append({"name": "min_memavailable", "ok": floor >= minimum,
                       "detail": "%.0f MB floor (target %.0f MB)" % (floor, minimum)})
    minimum_swap = args.min_swapfree_mb
    if minimum_swap and summary["swapfree_kb"]:
        floor = summary["swapfree_kb"]["min"] / 1024.0
        checks.append({"name": "min_swapfree", "ok": floor >= minimum_swap,
                       "detail": "%.0f MB swap floor (target %.0f MB)" % (floor, minimum_swap)})
    if args.fail_on_lmkd_kill:
        checks.append({"name": "no_lmkd_kills", "ok": not summary["kills"],
                       "detail": "%d kill/low-memory event lines" % len(summary["kills"])})
    summary["checks"] = checks
    summary["pass"] = all(check["ok"] for check in checks)
    return summary


def mb(value_kb):
    return "%.0f" % (value_kb / 1024.0) if value_kb is not None else "?"


def format_summary(summary):
    lines = []
    lines.append("== WeHavePhones memory summary ==")
    lines.append("samples=%d span=%.0fs  output=%s" % (
        summary["samples"], summary.get("span_seconds", 0), summary.get("output", "?")))
    mem = summary.get("memavailable_kb")
    swap = summary.get("swapfree_kb")
    if mem:
        lines.append("MemAvailable MB: min=%s max=%s first=%s last=%s (min at %s)" % (
            mb(mem["min"]), mb(mem["max"]), mb(mem["first"]), mb(mem["last"]),
            datetime.datetime.fromtimestamp(mem["min_time"]).strftime("%H:%M:%S")))
    if swap:
        lines.append("SwapFree MB:     min=%s max=%s first=%s last=%s" % (
            mb(swap["min"]), mb(swap["max"]), mb(swap["first"]), mb(swap["last"])))
    psi = summary.get("psi", {})
    if psi:
        some = psi.get("some", {})
        full = psi.get("full", {})
        lines.append("PSI memory max avg10: some=%.1f%% full=%.1f%% (avg60 some=%.1f%% full=%.1f%%)" % (
            some.get("avg10_max", 0), full.get("avg10_max", 0),
            some.get("avg60_max", 0), full.get("avg60_max", 0)))
    vm = summary.get("vmstat_delta", {})
    if vm:
        lines.append("vmstat delta: pswpout=%s pgmajfault=%s pgsteal_kswapd=%s" % (
            vm.get("pswpout", "?"), vm.get("pgmajfault", "?"), vm.get("pgsteal_kswapd", "?")))
    if summary.get("markers"):
        lines.append("markers:")
        for mark in summary["markers"]:
            lines.append("  %-18s MemAvail=%s MB swapFree=%s MB rss=%s MB (dMem=%s MB)" % (
                mark.get("name", "?"), mb(mark.get("mem_available_kb")),
                mb(mark.get("swap_free_kb")), mb(mark.get("tracked_rss_kb")),
                mb(mark.get("delta_memavailable_kb_vs_prev")) if
                mark.get("delta_memavailable_kb_vs_prev") is not None else "?"))
    if summary.get("processes"):
        lines.append("top tracked processes by max RSS (MB):")
        for name, entry in summary["processes"][:10]:
            lines.append("  %7s max  %7s mean  %s" % (
                mb(entry["max_rss_kb"]), mb(entry["mean_rss_kb"]), name))
    if summary.get("app_pss"):
        app = summary["app_pss"]
        lines.append("app PSS: total=%s MB rss=%s MB swapPss=%s MB maxPss=%s MB" % (
            mb(app.get("total_pss_kb")), mb(app.get("total_rss_kb")),
            mb(app.get("total_swap_pss_kb")), mb(app.get("max_total_pss_kb"))))
    if summary.get("meminfo_processes"):
        lines.append("per-process PSS (latest dumpsys meminfo):")
        for process in summary["meminfo_processes"]:
            lines.append("  pid=%-6d pss=%7s MB swapPss=%6s MB rss=%7s MB %s" % (
                process.get("pid", 0), mb(process.get("pss_kb")),
                mb(process.get("swap_pss_kb")), mb(process.get("rss_kb")),
                process.get("name", "?")))
    if summary.get("kills"):
        lines.append("kill / low-memory events (%d):" % len(summary["kills"]))
        for line in summary["kills"][-5:]:
            lines.append("  " + line[:160])
    for check in summary.get("checks", []):
        lines.append("[%s] %s: %s" % ("PASS" if check["ok"] else "FAIL",
                                      check["name"], check["detail"]))
    if summary.get("checks"):
        lines.append("verdict: " + ("PASS" if summary.get("pass") else "FAIL"))
    return "\n".join(lines)


def build_perfetto_config(duration_ms, heap_cmdline=None):
    config = [
        'buffers { size_kb: 32768 fill_policy: RING_BUFFER }',
        'data_sources { config { name: "linux.sys_stats" sys_stats_config {'
        ' stat_period_ms: 1000 stat_counters: STAT_CPU_TIMES stat_counters: STAT_FORK_COUNT'
        ' meminfo_period_ms: 1000'
        ' meminfo_counters: MEMINFO_MEM_FREE meminfo_counters: MEMINFO_MEM_AVAILABLE'
        ' meminfo_counters: MEMINFO_CACHED meminfo_counters: MEMINFO_SWAP_FREE'
        ' meminfo_counters: MEMINFO_ANON_PAGES meminfo_counters: MEMINFO_SLAB'
        ' meminfo_counters: MEMINFO_PAGE_TABLES'
        ' vmstat_period_ms: 1000'
        ' vmstat_counters: VMSTAT_PGFAULT vmstat_counters: VMSTAT_PGMAJFAULT'
        ' vmstat_counters: VMSTAT_PSWPIN vmstat_counters: VMSTAT_PSWPOUT'
        ' vmstat_counters: VMSTAT_PGSCAN_KSWAPD vmstat_counters: VMSTAT_PGSTEAL_KSWAPD'
        ' } } }',
        'data_sources { config { name: "linux.psi" psi_config { psi_period_ms: 1000 } } }',
        'data_sources { config { name: "android.process_stats" process_stats_config {'
        ' scan_all_processes_on_start: true proc_stats_poll_ms: 1000 } } }',
    ]
    if heap_cmdline:
        config.append(
            'data_sources { config { name: "android.heapprofd" target_buffer: 0'
            ' heapprofd_config { sampling_interval_bytes: 4096 process_cmdline: "%s"'
            ' shmem_size_bytes: 8388608 block_client: true } } }' % heap_cmdline)
    config.append("duration_ms: %d" % duration_ms)
    return "\n".join(config) + "\n"


MINIMAL_PERFETTO_CONFIG = """\
buffers { size_kb: 16384 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.sys_stats" sys_stats_config { meminfo_period_ms: 1000 vmstat_period_ms: 1000 } } }
duration_ms: %d
"""


def start_perfetto(adb, device, trace_path, config):
    process = subprocess.Popen(
        [adb, "-s", device, "shell", "perfetto", "-c", "-", "--txt", "-o", trace_path],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        process.stdin.write(config)
        process.stdin.close()
    except (BrokenPipeError, OSError):
        pass
    return process


def stop_perfetto(adb, device, process, trace_path, local_path, timeout):
    if process.poll() is None:
        try:
            process.wait(timeout=min(25.0, timeout))
        except subprocess.TimeoutExpired:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
    if process.poll() is None:
        return False, "perfetto did not exit"
    if process.returncode != 0:
        stderr = ""
        try:
            stderr = (process.stderr.read() or "").strip().splitlines()[-1]
        except (OSError, ValueError):
            pass
        return False, "perfetto exited %s: %s" % (process.returncode, stderr)
    if not pull_trace(adb, device, trace_path, local_path, timeout):
        return False, "could not pull trace from %s" % trace_path
    return True, str(local_path)


def pull_trace(adb, device, trace_path, local_path, timeout):
    try:
        with local_path.open("wb") as handle:
            result = subprocess.run([adb, "-s", device, "exec-out", "cat", trace_path],
                                    stdout=handle, stderr=subprocess.PIPE, timeout=timeout)
        return result.returncode == 0 and local_path.stat().st_size > 0
    except (subprocess.SubprocessError, OSError):
        return False


def run_sampler(args):
    adb = find_adb()
    device = resolve_device(args.device)
    output_path = pathlib.Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    marks_path = pathlib.Path(str(output_path) + ".marks.jsonl")
    events_path = pathlib.Path(str(output_path) + ".events.log")
    pattern = re.compile(args.process_filter, re.I)

    ok, state = check_device(adb, device, args.timeout)
    if not ok:
        print("adapter '%s' is not ready for device %s: %s" % (adb, device, state), file=sys.stderr)
        return 3
    info = collect_device_info(adb, device, args.timeout)
    print("device: %s android %s sdk %s (%s)" % (
        info.get("model", "?"), info.get("android", "?"), info.get("sdk", "?"),
        info.get("soc", "?")), flush=True)
    print("package: %s  output: %s" % (args.package, output_path), flush=True)
    print("sampling %.2g Hz for %.1f minutes; dumpsys meminfo every %d samples" % (
        1.0 / args.interval, args.minutes, args.meminfo_every), flush=True)
    print("mark: python3 %s --output %s --mark NAME" % (sys.argv[0], output_path), flush=True)

    seen_marks = 0
    if marks_path.exists():
        seen_marks = sum(1 for line in marks_path.read_text().splitlines() if line.strip())

    events_handle = None
    events_process = None
    if not args.no_events:
        events_handle = events_path.open("w")
        events_process = subprocess.Popen(
            [adb, "-s", device, "logcat", "-b", "events", "-v", "epoch", "-T", "1"],
            stdout=events_handle, stderr=subprocess.DEVNULL, text=True)
        time.sleep(0.5)
        if events_process.poll() not in (None, 0):
            events_process = subprocess.Popen(
                [adb, "-s", device, "logcat", "-b", "events", "-v", "epoch"],
                stdout=events_handle, stderr=subprocess.DEVNULL, text=True)
            print("note: logcat -T unsupported; streaming the full events buffer", flush=True)

    fifo_fd = None
    if args.marker_fifo:
        if not os.path.exists(args.marker_fifo):
            os.mkfifo(args.marker_fifo)
        fifo_fd = os.open(args.marker_fifo, os.O_RDONLY | os.O_NONBLOCK)

    trace_device = None
    trace_local = None
    perfetto = None
    if args.perfetto:
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        trace_device = "/data/local/tmp/wehavephones-%s.perfetto-trace" % stamp
        trace_local = pathlib.Path(args.perfetto)
        trace_local.parent.mkdir(parents=True, exist_ok=True)
        duration_ms = int(args.minutes * 60_000) + 10_000
        config = build_perfetto_config(duration_ms, args.perfetto_heap_cmdline)
        perfetto = start_perfetto(adb, device, trace_device, config)
        time.sleep(1.0)
        if perfetto.poll() is not None:
            print("warning: perfetto did not start (%s)" % (perfetto.stderr.read() or "").strip()[-200:],
                  flush=True)
        else:
            print("perfetto tracing to %s (duration %d ms)" % (trace_device, duration_ms), flush=True)

    samples = []
    deadline = time.monotonic() + args.minutes * 60
    sequence = 0
    try:
        while time.monotonic() < deadline:
            started = time.monotonic()
            forced = False
            marks_seen_now = 0
            if marks_path.exists():
                marks_seen_now = sum(1 for line in marks_path.read_text().splitlines() if line.strip())
                forced = marks_seen_now > seen_marks
                seen_marks = max(seen_marks, marks_seen_now)
            if fifo_fd is not None:
                try:
                    chunk = os.read(fifo_fd, 4096).decode(errors="replace")
                except BlockingIOError:
                    chunk = ""
                for line in chunk.splitlines():
                    if line.strip():
                        append_mark(output_path, line.strip(), source="fifo")
                        forced = True
            want_meminfo = (args.meminfo_every > 0 and sequence % args.meminfo_every == 0) or forced
            sample = take_sample(adb, device, args.package, pattern, want_meminfo, args.timeout)
            sample["seq"] = sequence
            samples.append(sample)
            with output_path.open("a") as handle:
                handle.write(json.dumps(sample) + "\n")
            if sequence % args.print_every == 0 or forced or "error" in sample:
                mem = sample.get("mem", {})
                print("t=%6.1fs MemAvail=%s MB SwapFree=%s MB PSI some=%.1f%% full=%.1f%% rss=%s MB%s" % (
                    time.monotonic() - (deadline - args.minutes * 60),
                    mb(mem.get("MemAvailable")), mb(mem.get("SwapFree")),
                    sample.get("psi", {}).get("some", {}).get("avg10", 0.0),
                    sample.get("psi", {}).get("full", {}).get("avg10", 0.0),
                    mb(summarize_ps(sample.get("procs", []))),
                    " ERROR=" + sample["error"] if "error" in sample else ""), flush=True)
            sequence += 1
            remaining = args.interval - (time.monotonic() - started)
            if remaining > 0:
                time.sleep(remaining)
    except KeyboardInterrupt:
        print("interrupted; writing report for %d samples" % len(samples), flush=True)
    finally:
        if fifo_fd is not None:
            os.close(fifo_fd)
        if events_process is not None:
            events_process.terminate()
            try:
                events_process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                events_process.kill()
            events_handle.close()
        if perfetto is not None:
            ok, detail = stop_perfetto(adb, device, perfetto, trace_device, trace_local, args.timeout)
            if ok:
                print("perfetto trace: %s (%d bytes)" % (trace_local, trace_local.stat().st_size), flush=True)
            else:
                print("warning: %s" % detail, flush=True)
        if not args.no_procstats:
            procstats_path = pathlib.Path(str(output_path) + ".procstats.txt")
            try:
                result = run_adb(adb, device, "dumpsys procstats --hours %g" % args.procstats_hours,
                                 args.procstats_timeout)
                procstats_path.write_text(result.stdout or "")
                print("procstats: %s" % procstats_path, flush=True)
            except (subprocess.SubprocessError, OSError) as error:
                print("warning: procstats failed: %s" % error, flush=True)

    marks, _ = load_jsonl(marks_path)
    summary = summarize(samples, marks, events_path, args)
    summary["device"] = info
    summary["perfetto"] = ({"device_path": trace_device, "local_path": str(trace_local),
                            "size_bytes": trace_local.stat().st_size if trace_local and trace_local.exists() else 0}
                           if trace_device else None)
    summary_path = pathlib.Path(str(output_path) + ".summary.json")
    summary_path.write_text(json.dumps(summary, indent=2) + "\n")
    print(format_summary(summary))
    print("summary: %s" % summary_path)
    if summary.get("checks") and not summary.get("pass"):
        return 2
    return 0


def run_report(args):
    output_path = pathlib.Path(args.output)
    samples, bad = load_jsonl(output_path)
    marks, _ = load_jsonl(pathlib.Path(str(output_path) + ".marks.jsonl"))
    events_path = pathlib.Path(str(output_path) + ".events.log")
    summary = summarize(samples, marks, events_path, args)
    summary_path = pathlib.Path(str(output_path) + ".summary.json")
    summary_path.write_text(json.dumps(summary, indent=2) + "\n")
    print(format_summary(summary))
    print("samples=%d unparsed_jsonl_lines=%d summary=%s" % (len(samples), bad, summary_path))
    if summary.get("checks") and not summary.get("pass"):
        return 2
    return 0


def self_test():
    failures = []
    passed = []

    def check(name, condition, detail=""):
        if condition:
            passed.append(name)
        else:
            failures.append("%s %s" % (name, detail))

    fixture_meminfo = """MemTotal:        7242412 kB
MemFree:          123456 kB
MemAvailable:     689784 kB
Buffers:           12345 kB
Cached:           456789 kB
SwapCached:        10000 kB
AnonPages:       1234567 kB
Mapped:           234567 kB
Shmem:             34567 kB
Slab:             123456 kB
SReclaimable:      45678 kB
SUnreclaim:        77778 kB
KernelStack:       12345 kB
PageTables:        23456 kB
SwapTotal:      12582908 kB
SwapFree:        3261700 kB
"""
    fixture_psi = """some avg10=24.76 avg60=12.34 avg300=5.67 total=123456789
full avg10=16.63 avg60=8.90 avg300=3.21 total=98765432
"""
    fixture_vmstat = """pswpin 123
pswpout 4567
pgfault 890123
pgmajfault 890
pgscan_kswapd 11111
pgsteal_kswapd 22222
oom_kill 0
unrelated_counter 999
"""
    fixture_ps = """PID PPID RSS NAME
100 1 15331 wineserver
200 1 902300 C:\\Program Files (x86)\\World of Warcraft\\_retail_\\Wow-ARM64.exe
300 200 641300 C:\\Program Files (x86)\\Battle.net\\Battle.net.exe
400 1 62000 com.wehavephones.dev
500 1 999 unrelated-daemon
"""
    fixture_dumpsys = """** MEMINFO in pid 200 [Wow-ARM64.exe] **
                   Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap
                 Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free
                ------   ------   ------   ------   ------   ------   ------   ------
           TOTAL   881234   800000    12345     6789   902300   100000    90000    10000
** MEMINFO in pid 400 [com.wehavephones.dev] **
           TOTAL    62000    50000      500      100    63000    40000    30000    10000
 App Summary
                       Pss(KB)                        Rss(KB)
                        ------                       ------
           Java Heap:     1234                           5678
             TOTAL PSS:  943234            TOTAL RSS:  965300       TOTAL SWAP PSS:   6889
"""

    mem = parse_meminfo(fixture_meminfo)
    check("parse_meminfo", mem.get("MemAvailable") == 689784 and mem.get("SwapFree") == 3261700
          and len(mem) == 16, str(mem))
    psi = parse_psi(fixture_psi)
    check("parse_psi", psi["some"]["avg10"] == 24.76 and psi["full"]["total"] == 98765432.0, str(psi))
    vm = parse_vmstat(fixture_vmstat)
    check("parse_vmstat", vm.get("pswpout") == 4567 and "unrelated_counter" not in vm, str(vm))
    pattern = re.compile(DEFAULT_PROCESS_FILTER, re.I)
    rows = parse_ps_rows(fixture_ps, pattern)
    names = [row["name"] for row in rows]
    check("parse_ps_rows", len(rows) == 4 and any("Wow-ARM64" in n for n in names)
          and all("unrelated" not in n for n in names), str(names))
    meminfo = parse_dumpsys_meminfo(fixture_dumpsys)
    check("parse_dumpsys", meminfo["app_summary"]["total_pss_kb"] == 943234
          and len(meminfo["processes"]) == 2
          and meminfo["processes"][0]["rss_kb"] == 902300, str(meminfo))
    sections = split_sections("MemTotal: 1 kB\n==PSI==\nsome avg10=1 total=2\n"
                              "==VMSTAT==\npswpin 1\n==PS==\n1 1 2 wine\n")
    check("split_sections", "MemTotal" in sections.get("meminfo", "")
          and sections.get("psi", "").startswith("some"), str(list(sections)))

    def add_fixture(samples, index, stamp, mem_avail, swap_free, psi_some, psi_full, rss):
        samples.append({
            "seq": index, "time": stamp,
            "mem": {"MemTotal": 7242412, "MemAvailable": mem_avail, "SwapFree": swap_free},
            "psi": {"some": {"avg10": psi_some, "avg60": 0.0, "avg300": 0.0, "total": 1.0},
                    "full": {"avg10": psi_full, "avg60": 0.0, "avg300": 0.0, "total": 2.0}},
            "vmstat": {"pswpout": 100 * index, "pgmajfault": 10 * index},
            "procs": [{"pid": 200, "ppid": 1, "rss_kb": rss, "name": "Wow-ARM64.exe"}],
        })

    with tempfile.TemporaryDirectory() as temp_dir:
        temp = pathlib.Path(temp_dir)
        samples = []
        add_fixture(samples, 0, 1000.0, 980000, 5160000, 1.0, 0.5, 100000)
        add_fixture(samples, 1, 1001.0, 900000, 4500000, 5.0, 2.0, 500000)
        add_fixture(samples, 2, 1002.0, 700000, 3300000, 20.0, 15.0, 900000)
        add_fixture(samples, 3, 1003.0, 800000, 4000000, 10.0, 8.0, 850000)
        samples[2]["meminfo"] = parse_dumpsys_meminfo(fixture_dumpsys)
        output = temp / "run.jsonl"
        with output.open("w") as handle:
            for sample in samples:
                handle.write(json.dumps(sample) + "\n")
        appended = append_mark(output, "one-off")
        check("append_mark", appended["name"] == "one-off"
              and pathlib.Path(str(output) + ".marks.jsonl").is_file(), "")
        marks_path = pathlib.Path(str(output) + ".marks.jsonl")
        marks_path.write_text(
            json.dumps({"time": 1000.5, "name": "idle", "source": "cli"}) + "\n" +
            json.dumps({"time": 1002.5, "name": "wow-login", "source": "cli"}) + "\n")
        marks, _ = load_jsonl(marks_path)
        check("load_marks", len(marks) == 2 and marks[1]["name"] == "wow-login", str(marks))

        args = argparse.Namespace(output=output, min_memavailable_mb=600,
                                  min_swapfree_mb=0, fail_on_lmkd_kill=True)
        events = temp / "run.jsonl.events.log"
        events.write_text("100 1 I am_kill: [0,1234,com.example,900,cached]\n"
                          "100 2 I am_proc_died: [0,1234,com.example]\n")
        summary = summarize(samples, marks, events, args)
        check("summary_min", summary["memavailable_kb"]["min"] == 700000, str(summary["memavailable_kb"]))
        check("summary_psi", abs(summary["psi"]["some"]["avg10_max"] - 20.0) < 0.01, str(summary["psi"]))
        check("summary_checks_fail", summary["checks"] and not summary["pass"], str(summary["checks"]))
        check("summary_kills", len(summary["kills"]) == 2, str(summary["kills"]))
        check("summary_marker_association",
              summary["markers"][0]["sample_index"] == 1
              and summary["markers"][1]["sample_index"] == 3,
              str([(m["name"], m["sample_index"]) for m in summary["markers"]]))
        check("summary_marker_delta",
              summary["markers"][1]["delta_memavailable_kb_vs_prev"] == 800000 - 900000
              and summary["markers"][1]["delta_swapfree_kb_vs_prev"] == 4000000 - 4500000,
              str(summary["markers"][1]))
        check("summary_processes",
              summary["processes"][0][0] == "Wow-ARM64.exe"
              and summary["processes"][0][1]["max_rss_kb"] == 900000,
              str(summary["processes"]))
        report = format_summary(summary)
        check("format_summary", "WeHavePhones memory summary" in report
              and "Wow-ARM64.exe" in report and "FAIL" in report, report[:200])
        summary_path = temp / "summary-out.json"
        summary_path.write_text(json.dumps(summary))
        reloaded = json.loads(summary_path.read_text())
        check("summary_json", reloaded["memavailable_kb"]["min"] == 700000, "")
        config = build_perfetto_config(60000, "com.wehavephones.dev")
        check("perfetto_config", "linux.sys_stats" in config and "linux.psi" in config
              and "android.heapprofd" in config and "duration_ms: 60000" in config, "")
        check("perfetto_minimal", "linux.sys_stats" in (MINIMAL_PERFETTO_CONFIG % 1000), "")

    print("self-test: %d checks passed" % len(passed))
    for name in passed:
        print("  PASS %s" % name)
    for failure in failures:
        print("  FAIL %s" % failure)
    return 1 if failures else 0


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=None,
                        help="ADB serial; defaults to ADB_SERIAL, ANDROID_DEVICE, then the only device adb lists")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--output", type=pathlib.Path, required=False,
                        help="JSONL output path (required except with --self-test)")
    parser.add_argument("--minutes", type=float, default=10.0)
    parser.add_argument("--interval", type=float, default=1.0, help="seconds between samples")
    parser.add_argument("--meminfo-every", type=int, default=15,
                        help="capture dumpsys meminfo every N samples; 0 disables")
    parser.add_argument("--print-every", type=int, default=5)
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--process-filter", default=DEFAULT_PROCESS_FILTER)
    parser.add_argument("--procstats-hours", type=float, default=2.0)
    parser.add_argument("--procstats-timeout", type=float, default=60.0)
    parser.add_argument("--no-procstats", action="store_true")
    parser.add_argument("--no-events", action="store_true",
                        help="do not stream logcat -b events (lmkd/am_kill)")
    parser.add_argument("--perfetto", type=pathlib.Path, default=None,
                        help="also record a Perfetto trace to this local path")
    parser.add_argument("--perfetto-heap-cmdline", default=None,
                        help="add heapprofd for this process cmdline (requires profileable/debuggable app)")
    parser.add_argument("--mark", metavar="NAME", default=None,
                        help="append a labelled phase marker and exit")
    parser.add_argument("--marker-fifo", default=None,
                        help="also accept marker lines from this FIFO while sampling")
    parser.add_argument("--report", action="store_true",
                        help="analyze an existing --output instead of sampling")
    parser.add_argument("--min-memavailable-mb", type=float, default=0.0,
                        help="fail the verdict when the MemAvailable floor is below this")
    parser.add_argument("--min-swapfree-mb", type=float, default=0.0)
    parser.add_argument("--fail-on-lmkd-kill", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if not args.output:
        parser.error("--output is required unless --self-test is used")
    if args.mark:
        record = append_mark(args.output, args.mark)
        fifo = write_fifo_mark(args.marker_fifo, args.mark)
        print("mark %s at %.3f%s" % (record["name"], record["time"],
                                     " (fifo)" if fifo else ""))
        return 0
    if args.interval <= 0 or args.minutes <= 0:
        parser.error("--minutes and --interval must be positive")
    if not re.fullmatch(r"[A-Za-z0-9._]+", args.package):
        parser.error("--package must be a simple package name")
    if args.report:
        return run_report(args)
    return run_sampler(args)


if __name__ == "__main__":
    sys.exit(main())
