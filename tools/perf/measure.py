#!/usr/bin/env python3
"""Measures what the app costs over a time window on an emulator or device (docs/08_test_plan.md §2).

    python tools/perf/measure.py --seconds 60 --fix 55.7558 37.6173      # feed 1 Hz fixes while measuring
    python tools/perf/measure.py --seconds 30                            # idle: whatever is on screen

Reports CPU time of the process and of its main thread (from /proc/<pid>/stat and /proc/<pid>/task/<pid>/stat,
clock ticks of 10 ms) and frames from `dumpsys gfxinfo` (reset at the start): frames rendered, janky share,
90th/99th percentile frame time. With --fix the emulator gets a fix per second moving at --speed m/s (`adb emu
geo fix`, speed in knots for the Doppler speed).
"""
import argparse
import math
import os
import re
import subprocess
import sys
import time

ENV = dict(os.environ, MSYS_NO_PATHCONV="1")
TICK_S = 0.01


def adb(adb_path, *args):
    return subprocess.run([adb_path, *args], capture_output=True, env=ENV).stdout.decode(errors="replace")


def cpu_ticks(adb_path, pid, tid=None):
    path = f"/proc/{pid}/stat" if tid is None else f"/proc/{pid}/task/{tid}/stat"
    raw = adb(adb_path, "shell", "cat", path)
    fields = raw[raw.rfind(")") + 2:].split()
    return int(fields[11]) + int(fields[12])  # utime + stime


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--package", default="com.justtracker.app.debug")
    ap.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    ap.add_argument("--seconds", type=int, default=60)
    ap.add_argument("--fix", nargs=2, type=float, metavar=("LAT", "LON"))
    ap.add_argument("--speed", type=float, default=1.6, help="m/s along a straight line heading north-east")
    ap.add_argument("--label", default="")
    args = ap.parse_args()

    pid = adb(args.adb, "shell", "pidof", args.package).strip()
    if not pid:
        sys.exit(f"{args.package} is not running")
    adb(args.adb, "shell", "dumpsys", "gfxinfo", args.package, "reset")
    p0, m0, t0 = cpu_ticks(args.adb, pid), cpu_ticks(args.adb, pid, pid), time.time()

    lat, lon = args.fix if args.fix else (None, None)
    step = args.speed / 111_195.0 / math.sqrt(2)
    knots = args.speed * 1.943844
    while time.time() - t0 < args.seconds:
        tick = time.time()
        if lat is not None:
            adb(args.adb, "emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}", "150", "8", f"{knots:.2f}")
            lat += step
            lon += step / math.cos(math.radians(lat))
        time.sleep(max(0.0, 1.0 - (time.time() - tick)))

    p1, m1, t1 = cpu_ticks(args.adb, pid), cpu_ticks(args.adb, pid, pid), time.time()
    gfx = adb(args.adb, "shell", "dumpsys", "gfxinfo", args.package)
    wall = t1 - t0

    def grab(pattern):
        m = re.search(pattern, gfx)
        return m.group(1) if m else "?"

    proc_s, main_s = (p1 - p0) * TICK_S, (m1 - m0) * TICK_S
    frames = grab(r"Total frames rendered: (\d+)")
    janky = grab(r"Janky frames: \d+ \(([\d.]+)%\)")
    p90 = grab(r"90th percentile: (\d+)ms")
    p99 = grab(r"99th percentile: (\d+)ms")
    print(
        f"{args.label or args.package}: wall {wall:.0f} s | process CPU {proc_s:.2f} s ({100 * proc_s / wall:.1f} %) | "
        f"main thread {main_s:.2f} s ({100 * main_s / wall:.1f} %) | frames {frames} | janky {janky} % | "
        f"p90 {p90} ms | p99 {p99} ms"
    )


if __name__ == "__main__":
    main()
