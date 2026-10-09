#!/usr/bin/env python3
"""Adds a long synthetic track to the database of a debuggable JustTracker build (performance checks, docs/08_test_plan.md §2).

    python tools/perf/make_track_db.py --points 10000 --status RECORDING
    python tools/perf/make_track_db.py --points 100000 --status FINISHED --segments 3 --lat 59.93 --lon 30.33
    python tools/perf/make_track_db.py --points 100000 --status FINISHED --profile drive

--profile walk (default) is a walk at --speed with a fix stored every second. --profile drive is stop-and-go driving
(standing, speeding up at 1.5-3 m/s², cruising at 12-25 m/s, braking at 2-4 m/s²) with the receiver's Doppler speed
(noise 0.15 m/s) and its accuracy, thinned by the app's storage rule (>= 2 m or >= 30 s) — a track with acceleration
episodes for the track detail (US-24). The speed accuracy is written when the schema has it (3, JustTracker 1.2.0).

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


class DriveProfile:
    """Stop-and-go driving, one true speed per second: stand, speed up, cruise, brake to a stop, again."""

    def __init__(self, rnd):
        self.rnd = rnd
        self.speed = 0.0
        self.phase, self.left = "stand", rnd.randint(5, 30)
        self.rate, self.target = 0.0, 0.0

    def next(self):
        rnd = self.rnd
        if self.phase == "stand":
            self.speed = 0.0
            self.left -= 1
            if self.left <= 0:
                self.phase, self.rate, self.target = "up", rnd.uniform(1.5, 3.0), rnd.uniform(12, 25)
        elif self.phase == "up":
            self.speed = min(self.target, self.speed + self.rate)
            if self.speed >= self.target:
                self.phase, self.left = "cruise", rnd.randint(30, 90)
        elif self.phase == "cruise":
            self.speed = max(5.0, self.speed + rnd.gauss(0, 0.2))
            self.left -= 1
            if self.left <= 0:
                self.phase, self.rate = "down", rnd.uniform(2.0, 4.0)
        else:
            self.speed = max(0.0, self.speed - self.rate)
            if self.speed == 0.0:
                self.phase, self.left = "stand", rnd.randint(5, 40)
        return self.speed


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--package", default="com.justtracker.app.debug")
    ap.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    ap.add_argument("--points", type=int, default=10_000)
    ap.add_argument("--segments", type=int, default=1)
    ap.add_argument("--status", choices=["RECORDING", "FINISHED"], default="RECORDING")
    ap.add_argument("--lat", type=float, default=55.7558)
    ap.add_argument("--lon", type=float, default=37.6173)
    ap.add_argument("--speed", type=float, default=1.6, help="mean speed of a walk, m/s")
    ap.add_argument("--profile", choices=["walk", "drive"], default="walk")
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
    has_speed_accuracy = any(row[1] == "speedAccuracyMps" for row in con.execute("PRAGMA table_info(track_points)"))
    now_ms = int(time.time() * 1000)
    started = now_ms - 60_000 if args.newest else now_ms - args.points * 1000 - 60_000
    if args.status == "RECORDING":
        con.execute("UPDATE tracks SET status='FINISHED', finishedAt=COALESCE(finishedAt, ?) WHERE status IN ('RECORDING','PAUSED')", (now_ms,))
    cur = con.execute(
        "INSERT INTO tracks (name,status,activityType,activityManual,startedAt,finishedAt,distanceM,movingTimeMs,totalTimeMs,"
        "pausedTimeMs,avgSpeedMps,maxSpeedMps,elevationGainM,elevationLossM,pointCount) VALUES (?,?,?,?,?,?,0,0,0,0,0,0,0,0,0)",
        (f"Perf {args.points} pts", args.status, "CAR" if args.profile == "drive" else "WALK", 0, started,
         None if args.status == "RECORDING" else started + args.points * 1000),
    )
    track_id = cur.lastrowid

    lat, lon, heading = args.lat, args.lon, rnd.uniform(0, 2 * math.pi)
    alt = 150.0
    per_segment = max(1, args.points // args.segments)
    rows, distance, moving_ms, max_speed = [], 0.0, 0, 0.0
    prev = None
    t = started
    drive = DriveProfile(rnd) if args.profile == "drive" else None
    since_stored, last_stored_t = 0.0, None
    while len(rows) < args.points:
        segment = min(len(rows) // per_segment, args.segments - 1)
        if prev is not None and segment != prev[0]:
            t += 120_000  # a pause between segments
            since_stored, last_stored_t = 0.0, None
        if drive is None:
            speed = max(0.0, rnd.gauss(args.speed, args.speed * 0.25))
            reported = speed
        else:
            speed = drive.next()
            reported = max(0.0, speed + rnd.gauss(0, 0.15))
        heading += rnd.gauss(0, 0.08 if drive is None else 0.01)
        step = speed  # one fix per second
        nlat = lat + step * math.cos(heading) / 111_195.0
        nlon = lon + step * math.sin(heading) / (111_195.0 * math.cos(math.radians(lat)))
        alt += rnd.gauss(0, 0.4)
        moved = haversine(lat, lon, nlat, nlon)
        lat, lon = nlat, nlon
        since_stored += moved
        # The app stores a fix that moved >= 2 m from the last stored one, or after 30 s (docs/06_system_analysis.md §3.1).
        if drive is not None and last_stored_t is not None and since_stored < 2.0 and t - last_stored_t < 30_000:
            t += 1000
            continue
        if prev is not None and prev[0] == segment:
            distance += since_stored
            if since_stored / max(1.0, (t - last_stored_t) / 1000) > 0.5:
                moving_ms += t - last_stored_t
        max_speed = max(max_speed, reported)
        accuracy = round(rnd.uniform(0.1, 0.5), 2) if drive is not None else 0.3
        rows.append((track_id, segment, t, lat, lon, alt, rnd.uniform(3, 12), reported, math.degrees(heading) % 360, rnd.uniform(2, 8),
                     accuracy))
        prev = (segment,)
        since_stored, last_stored_t = 0.0, t
        t += 1000
    columns = "trackId,segment,timestamp,lat,lon,altitudeM,accuracyM,speedMps,bearingDeg,verticalAccuracyM"
    if has_speed_accuracy:
        con.executemany(f"INSERT INTO track_points ({columns},speedAccuracyMps) VALUES (?,?,?,?,?,?,?,?,?,?,?)", rows)
    else:
        con.executemany(f"INSERT INTO track_points ({columns}) VALUES (?,?,?,?,?,?,?,?,?,?)", [r[:-1] for r in rows])
    total_ms = rows[-1][2] - started
    con.execute(
        "UPDATE tracks SET distanceM=?, movingTimeMs=?, totalTimeMs=?, avgSpeedMps=?, maxSpeedMps=?, pointCount=?, "
        "finishedAt=CASE WHEN finishedAt IS NULL THEN NULL ELSE ? END WHERE id=?",
        (distance, moving_ms, total_ms, distance / (moving_ms / 1000) if moving_ms else 0.0, max_speed, len(rows), rows[-1][2], track_id),
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
