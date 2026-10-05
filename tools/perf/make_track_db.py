#!/usr/bin/env python3
"""Adds a long synthetic track to the database of a debuggable JustTracker build (performance checks, docs/08_test_plan.md §2).

    python tools/perf/make_track_db.py --points 10000 --status RECORDING
    python tools/perf/make_track_db.py --points 100000 --status FINISHED --segments 3 --lat 59.93 --lon 30.33

The app is force-stopped, its database (with the WAL) is copied out through `run-as`, the track is appended with
Python's sqlite3, and the checkpointed file is copied back without -wal/-shm. Only debuggable builds allow `run-as`
(the default package is the debug one). A RECORDING track is picked up by the app as an interrupted recording:
open it and tap "Continue", then feed fixes with `adb emu geo fix`. Other RECORDING/PAUSED tracks are finished first,
so the new one is the active track; --newest puts a FINISHED track at the top of History. Room's identity hash and
schema are untouched.
"""
import argparse
import math
import os
import random
import sqlite3
import subprocess
import sys
import tempfile
import time

ENV = dict(os.environ, MSYS_NO_PATHCONV="1")


def adb(adb_path, *args, binary=False, check=True):
    r = subprocess.run([adb_path, *args], capture_output=True, env=ENV)
    if check and r.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {r.stderr.decode(errors='replace')}")
    return r.stdout if binary else r.stdout.decode(errors="replace")


def haversine(lat1, lon1, lat2, lon2):
    r = 6_371_008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--package", default="com.justtracker.app.debug")
    ap.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    ap.add_argument("--points", type=int, default=10_000)
    ap.add_argument("--segments", type=int, default=1)
    ap.add_argument("--status", choices=["RECORDING", "FINISHED"], default="RECORDING")
    ap.add_argument("--lat", type=float, default=55.7558)
    ap.add_argument("--lon", type=float, default=37.6173)
    ap.add_argument("--speed", type=float, default=1.6, help="mean speed, m/s")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--newest", action="store_true", help="FINISHED track listed first in History (starts now)")
    args = ap.parse_args()
    rnd = random.Random(args.seed)

    db_dir = "databases"
    work = tempfile.mkdtemp(prefix="jt-db-")
    local = os.path.join(work, "justtracker.db")
    adb(args.adb, "shell", "am", "force-stop", args.package)
    for suffix in ("", "-wal", "-shm"):
        data = adb(args.adb, "exec-out", "run-as", args.package, "cat", f"{db_dir}/justtracker.db{suffix}", binary=True, check=False)
        if data and not data.startswith(b"run-as:") and not data.startswith(b"cat:"):
            open(local + suffix, "wb").write(data)
    if not os.path.exists(local):
        sys.exit("no database: start the app once (and grant location) before generating a track")

    con = sqlite3.connect(local)
    con.execute("PRAGMA foreign_keys=ON")
    now_ms = int(time.time() * 1000)
    started = now_ms - 60_000 if args.newest else now_ms - args.points * 1000 - 60_000
    if args.status == "RECORDING":
        con.execute("UPDATE tracks SET status='FINISHED', finishedAt=COALESCE(finishedAt, ?) WHERE status IN ('RECORDING','PAUSED')", (now_ms,))
    cur = con.execute(
        "INSERT INTO tracks (name,status,activityType,activityManual,startedAt,finishedAt,distanceM,movingTimeMs,totalTimeMs,"
        "pausedTimeMs,avgSpeedMps,maxSpeedMps,elevationGainM,elevationLossM,pointCount) VALUES (?,?,?,?,?,?,0,0,0,0,0,0,0,0,0)",
        (f"Perf {args.points} pts", args.status, "WALK", 0, started, None if args.status == "RECORDING" else started + args.points * 1000),
    )
    track_id = cur.lastrowid

    lat, lon, heading = args.lat, args.lon, rnd.uniform(0, 2 * math.pi)
    alt = 150.0
    per_segment = max(1, args.points // args.segments)
    rows, distance, moving_ms, max_speed = [], 0.0, 0, 0.0
    prev = None
    t = started
    for i in range(args.points):
        segment = min(i // per_segment, args.segments - 1)
        if prev is not None and segment != prev[0]:
            t += 120_000  # a pause between segments
        speed = max(0.0, rnd.gauss(args.speed, args.speed * 0.25))
        heading += rnd.gauss(0, 0.08)
        step = speed  # one fix per second
        nlat = lat + step * math.cos(heading) / 111_195.0
        nlon = lon + step * math.sin(heading) / (111_195.0 * math.cos(math.radians(lat)))
        alt += rnd.gauss(0, 0.4)
        if prev is not None and prev[0] == segment:
            d = haversine(lat, lon, nlat, nlon)
            distance += d
            if speed > 0.5:
                moving_ms += 1000
        lat, lon = nlat, nlon
        max_speed = max(max_speed, speed)
        rows.append((track_id, segment, t, lat, lon, alt, rnd.uniform(3, 12), speed, math.degrees(heading) % 360, rnd.uniform(2, 8)))
        prev = (segment,)
        t += 1000
    con.executemany(
        "INSERT INTO track_points (trackId,segment,timestamp,lat,lon,altitudeM,accuracyM,speedMps,bearingDeg,verticalAccuracyM) "
        "VALUES (?,?,?,?,?,?,?,?,?,?)",
        rows,
    )
    total_ms = rows[-1][2] - started
    con.execute(
        "UPDATE tracks SET distanceM=?, movingTimeMs=?, totalTimeMs=?, avgSpeedMps=?, maxSpeedMps=?, pointCount=? WHERE id=?",
        (distance, moving_ms, total_ms, distance / (moving_ms / 1000) if moving_ms else 0.0, max_speed, len(rows), track_id),
    )
    con.commit()
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.close()

    tmp_remote = "/data/local/tmp/justtracker_perf.db"
    adb(args.adb, "push", local, tmp_remote)
    adb(args.adb, "shell", "chmod", "644", tmp_remote)
    adb(args.adb, "shell", "run-as", args.package, "cp", tmp_remote, f"{db_dir}/justtracker.db")
    adb(args.adb, "shell", "run-as", args.package, "rm", "-f", f"{db_dir}/justtracker.db-wal", f"{db_dir}/justtracker.db-shm")
    adb(args.adb, "shell", "rm", "-f", tmp_remote)
    print(f"track {track_id}: {len(rows)} points, {args.segments} segment(s), {distance / 1000:.1f} km, status {args.status}, "
          f"last point {rows[-1][3]:.6f} {rows[-1][4]:.6f}")


if __name__ == "__main__":
    main()
