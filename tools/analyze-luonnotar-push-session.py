#!/usr/bin/env python3
"""Analyze Luonnotar/Android log captures and WhatsApp PUSH_TEST send records.

The analyzer is intentionally read-only. It accepts one or more diagnostic ZIPs
or directories, pairs WhatsApp MNS EOF events with reconnects, extracts
Luonnotar push arrival evidence, detects backlog releases, and compares sources
when multiple devices are supplied.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import re
import shutil
import statistics
import tempfile
import zipfile
from dataclasses import dataclass, asdict
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Any, Iterable, Optional

LOGCAT_TS_RE = re.compile(
    r"^(?P<month>\d{2})-(?P<day>\d{2})\s+"
    r"(?P<hour>\d{2}):(?P<minute>\d{2}):(?P<second>\d{2})\."
    r"(?P<millis>\d{3})\s+"
)
FIELD_RE = re.compile(r"([A-Za-z][A-Za-z0-9_]*)=([^,}]*)")
SESSION_EVENT_RE = re.compile(
    r"\b(experiment_session_started|experiment_session_marked|"
    r"experiment_session_stopped)\b"
)
PUSH_EVENT_MARKER = "push_test_arrival_observed"
EOF_MARKER = "MNSTCPSocketReceive(): Received EOF"
CONNECT_ATTEMPT_MARKERS = (
    "WAJMNSStream/impl/tcpStreamCreate",
    "(initial=>connecting)",
)
CONNECTED_MARKER = "(connecting=>connected)"


@dataclass
class PushDelivery:
    source: str
    sequence: int
    sender_epoch_ms: int
    seen_wall_ms: int
    delay_ms: int
    post_time_ms: int
    observation_source: str
    package_name: str
    log_local_time: str
    log_utc_time: str
    raw_file: str
    send_csv_utc: str = ""
    send_csv_epoch_ms: int = 0
    sender_csv_difference_ms: int = 0
    during_outage: bool = False
    outage_index: int = 0


@dataclass
class ConnectionEvent:
    source: str
    kind: str
    local_time: str
    utc_time: str
    epoch_ms: int
    raw_file: str
    raw_line: str


@dataclass
class Outage:
    source: str
    outage_index: int
    eof_local_time: str
    eof_utc_time: str
    reconnect_attempt_local_time: str
    reconnect_attempt_utc_time: str
    connected_local_time: str
    connected_utc_time: str
    reconnect_attempt_delay_ms: int
    connected_delay_ms: int
    messages_sent_during_outage: int = 0
    messages_arrived_after_outage: int = 0


@dataclass
class ExperimentEvent:
    source: str
    event: str
    local_time: str
    utc_time: str
    session_id: str
    session_name: str
    mark_label: str
    mark_count: int
    screen_interactive: str
    device_idle_mode: str
    wake_lock_held: str
    wifi_lock_held: str
    vpn_present: str
    validated: str
    raw_file: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Analyze Luonnotar PUSH_TEST and WhatsApp MNS logs."
    )
    parser.add_argument(
        "--input",
        action="append",
        required=True,
        help="Diagnostic ZIP or directory. Repeat for multiple devices.",
    )
    parser.add_argument(
        "--send-events",
        default="",
        help="Optional send-events.csv. An embedded CSV is used otherwise.",
    )
    parser.add_argument(
        "--output",
        default="",
        help="Output directory. Defaults beside the first input.",
    )
    parser.add_argument(
        "--source-name",
        action="append",
        default=[],
        help="Optional source label matching each --input in order.",
    )
    parser.add_argument(
        "--backlog-threshold-ms",
        type=int,
        default=10_000,
    )
    parser.add_argument(
        "--backlog-release-gap-ms",
        type=int,
        default=5_000,
    )
    return parser.parse_args()


def safe_source_name(path: Path) -> str:
    name = path.stem if path.is_file() else path.name
    cleaned = re.sub(r"[^\w.-]+", "_", name, flags=re.UNICODE).strip("_.-")
    return cleaned or "source"


def extract_zip_normalized(zip_path: Path, destination: Path) -> None:
    with zipfile.ZipFile(zip_path) as archive:
        for info in archive.infolist():
            normalized = info.filename.replace("\\", "/").lstrip("/")
            if not normalized:
                continue
            target = destination / normalized
            resolved = target.resolve()
            if destination.resolve() not in resolved.parents and resolved != destination.resolve():
                raise ValueError(f"Unsafe ZIP path: {info.filename}")
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info) as src, target.open("wb") as dst:
                shutil.copyfileobj(src, dst)


def choose_logcat_file(root: Path) -> Optional[Path]:
    candidates = [
        p for p in root.rglob("*")
        if p.is_file() and "logcat" in p.name.lower()
    ]
    if not candidates:
        return None

    def score(path: Path) -> tuple[int, int]:
        name = path.name.lower()
        priority = 0
        if "final" in name:
            priority += 100
        if "ring-final" in name:
            priority += 20
        if "relevant" in name:
            priority -= 80
        if "before" in name:
            priority -= 100
        return priority, path.stat().st_size

    return max(candidates, key=score)


def choose_send_csv(root: Path) -> Optional[Path]:
    candidates = [
        p for p in root.rglob("*.csv")
        if "send-events" in p.name.lower()
    ]
    return max(candidates, key=lambda p: p.stat().st_size) if candidates else None


def parse_logcat_local(line: str, year: int) -> Optional[datetime]:
    match = LOGCAT_TS_RE.match(line)
    if not match:
        return None
    try:
        return datetime(
            year,
            int(match.group("month")),
            int(match.group("day")),
            int(match.group("hour")),
            int(match.group("minute")),
            int(match.group("second")),
            int(match.group("millis")) * 1000,
        )
    except ValueError:
        return None


def parse_fields(line: str) -> dict[str, str]:
    return {key: value.strip() for key, value in FIELD_RE.findall(line)}


def int_field(fields: dict[str, str], key: str, default: int = 0) -> int:
    try:
        return int(fields.get(key, ""))
    except (TypeError, ValueError):
        return default


def infer_year_and_offset(lines: Iterable[str]) -> tuple[int, Optional[timedelta]]:
    samples: list[tuple[str, int]] = []
    for line in lines:
        if PUSH_EVENT_MARKER not in line:
            continue
        fields = parse_fields(line)
        seen = int_field(fields, "seenWall")
        if seen > 0 and LOGCAT_TS_RE.match(line):
            samples.append((line, seen))
            if len(samples) >= 50:
                break
    if not samples:
        return datetime.now().year, None

    first_seen = datetime.fromtimestamp(samples[0][1] / 1000, tz=timezone.utc)
    base_year = first_seen.year
    offsets: list[float] = []
    chosen_year = base_year
    for line, seen_ms in samples:
        seen_utc_naive = datetime.fromtimestamp(
            seen_ms / 1000,
            tz=timezone.utc,
        ).replace(tzinfo=None)
        candidates: list[tuple[float, int, datetime]] = []
        for year in (base_year - 1, base_year, base_year + 1):
            local = parse_logcat_local(line, year)
            if local is None:
                continue
            delta = local - seen_utc_naive
            # Real-world timezone offsets are far smaller than half a year.
            penalty = abs(delta.total_seconds())
            candidates.append((penalty, year, local))
        if not candidates:
            continue
        _, year, local = min(candidates, key=lambda item: item[0])
        chosen_year = year
        offsets.append((local - seen_utc_naive).total_seconds())
    if not offsets:
        return chosen_year, None
    median_seconds = statistics.median(offsets)
    # Avoid millisecond noise from adjacent logging operations.
    rounded_seconds = round(median_seconds / 60) * 60
    return chosen_year, timedelta(seconds=rounded_seconds)


def local_to_utc_epoch_ms(
    local_dt: Optional[datetime],
    offset: Optional[timedelta],
) -> int:
    if local_dt is None or offset is None:
        return 0
    utc_naive = local_dt - offset
    return int(utc_naive.replace(tzinfo=timezone.utc).timestamp() * 1000)


def iso_local(value: Optional[datetime]) -> str:
    return value.isoformat(timespec="milliseconds") if value else ""


def iso_utc_epoch(epoch_ms: int) -> str:
    if epoch_ms <= 0:
        return ""
    return datetime.fromtimestamp(epoch_ms / 1000, tz=timezone.utc).isoformat(
        timespec="milliseconds"
    )


def parse_logcat(
    source: str,
    path: Path,
) -> tuple[list[PushDelivery], list[ConnectionEvent], list[ExperimentEvent], dict[str, Any]]:
    text = path.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    year, offset = infer_year_and_offset(lines)
    deliveries: list[PushDelivery] = []
    connection_events: list[ConnectionEvent] = []
    experiment_events: list[ExperimentEvent] = []

    for line in lines:
        local_dt = parse_logcat_local(line, year)
        epoch_ms = local_to_utc_epoch_ms(local_dt, offset)
        local_text = iso_local(local_dt)
        utc_text = iso_utc_epoch(epoch_ms)

        if PUSH_EVENT_MARKER in line:
            fields = parse_fields(line)
            sequence = int_field(fields, "sequence", -1)
            sender = int_field(fields, "senderEpochMs")
            seen = int_field(fields, "seenWall")
            delay = int_field(fields, "endToEndDelayMs", seen - sender)
            if sequence >= 0 and sender > 0 and seen > 0:
                deliveries.append(
                    PushDelivery(
                        source=source,
                        sequence=sequence,
                        sender_epoch_ms=sender,
                        seen_wall_ms=seen,
                        delay_ms=delay,
                        post_time_ms=int_field(fields, "notificationPostTime"),
                        observation_source=fields.get("observationSource", ""),
                        package_name=fields.get("packageName", ""),
                        log_local_time=local_text,
                        log_utc_time=utc_text or iso_utc_epoch(seen),
                        raw_file=str(path),
                    )
                )

        kind = ""
        if EOF_MARKER in line:
            kind = "EOF"
        elif any(marker in line for marker in CONNECT_ATTEMPT_MARKERS):
            kind = "RECONNECT_ATTEMPT"
        elif CONNECTED_MARKER in line:
            kind = "CONNECTED"
        if kind and local_dt is not None:
            connection_events.append(
                ConnectionEvent(
                    source=source,
                    kind=kind,
                    local_time=local_text,
                    utc_time=utc_text,
                    epoch_ms=epoch_ms,
                    raw_file=str(path),
                    raw_line=line,
                )
            )

        event_match = SESSION_EVENT_RE.search(line)
        if event_match:
            fields = parse_fields(line)
            experiment_events.append(
                ExperimentEvent(
                    source=source,
                    event=event_match.group(1),
                    local_time=local_text,
                    utc_time=utc_text,
                    session_id=fields.get("sessionId", ""),
                    session_name=fields.get("sessionName", ""),
                    mark_label=fields.get("markLabel", ""),
                    mark_count=int_field(fields, "markCount"),
                    screen_interactive=fields.get("screenInteractive", ""),
                    device_idle_mode=fields.get("deviceIdleMode", ""),
                    wake_lock_held=fields.get("wakeLockHeld", ""),
                    wifi_lock_held=fields.get("wifiLockHeld", ""),
                    vpn_present=fields.get("vpnPresent", fields.get("vpn", "")),
                    validated=fields.get("validated", ""),
                    raw_file=str(path),
                )
            )

    metadata = {
        "logcat_file": str(path),
        "logcat_year": year,
        "inferred_device_utc_offset_minutes": (
            int(offset.total_seconds() // 60) if offset is not None else None
        ),
        "logcat_line_count": len(lines),
    }
    return deliveries, connection_events, experiment_events, metadata


def parse_jsonl(
    source: str,
    paths: Iterable[Path],
) -> tuple[list[PushDelivery], list[ExperimentEvent]]:
    deliveries: list[PushDelivery] = []
    events: list[ExperimentEvent] = []
    for path in paths:
        with path.open("r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                try:
                    record = json.loads(line)
                except json.JSONDecodeError:
                    continue
                event = str(record.get("event", ""))
                wall = str(record.get("wallTime", ""))
                if event == PUSH_EVENT_MARKER:
                    sequence = int(record.get("sequence", -1))
                    sender = int(record.get("senderEpochMs", 0))
                    seen = int(record.get("seenWall", 0))
                    if sequence >= 0 and sender > 0 and seen > 0:
                        deliveries.append(
                            PushDelivery(
                                source=source,
                                sequence=sequence,
                                sender_epoch_ms=sender,
                                seen_wall_ms=seen,
                                delay_ms=int(
                                    record.get("endToEndDelayMs", seen - sender)
                                ),
                                post_time_ms=int(
                                    record.get("notificationPostTime", 0)
                                ),
                                observation_source=str(
                                    record.get("observationSource", "")
                                ),
                                package_name=str(record.get("packageName", "")),
                                log_local_time="",
                                log_utc_time=wall or iso_utc_epoch(seen),
                                raw_file=str(path),
                            )
                        )
                if event in {
                    "experiment_session_started",
                    "experiment_session_marked",
                    "experiment_session_stopped",
                }:
                    events.append(
                        ExperimentEvent(
                            source=source,
                            event=event,
                            local_time="",
                            utc_time=wall,
                            session_id=str(record.get("sessionId", "")),
                            session_name=str(record.get("sessionName", "")),
                            mark_label=str(record.get("markLabel", "")),
                            mark_count=int(record.get("markCount", 0)),
                            screen_interactive=str(
                                record.get("screenInteractive", "")
                            ),
                            device_idle_mode=str(
                                record.get("deviceIdleMode", record.get("deviceIdle", ""))
                            ),
                            wake_lock_held=str(record.get("wakeLockHeld", "")),
                            wifi_lock_held=str(record.get("wifiLockHeld", "")),
                            vpn_present=str(
                                record.get("vpnPresent", record.get("vpn", ""))
                            ),
                            validated=str(record.get("validated", "")),
                            raw_file=str(path),
                        )
                    )
    return deliveries, events


def deduplicate_deliveries(deliveries: Iterable[PushDelivery]) -> list[PushDelivery]:
    best: dict[tuple[str, int], PushDelivery] = {}
    for item in deliveries:
        key = (item.source, item.sequence)
        current = best.get(key)
        if current is None:
            best[key] = item
            continue
        current_rank = (
            current.seen_wall_ms,
            0 if current.observation_source == "LIVE_CALLBACK" else 1,
        )
        item_rank = (
            item.seen_wall_ms,
            0 if item.observation_source == "LIVE_CALLBACK" else 1,
        )
        if item_rank < current_rank:
            best[key] = item
    return sorted(best.values(), key=lambda item: (item.source, item.sequence))


def parse_send_events(path: Optional[Path]) -> dict[int, dict[str, Any]]:
    if path is None or not path.is_file():
        return {}
    rows: dict[int, dict[str, Any]] = {}
    with path.open("r", encoding="utf-8-sig", newline="", errors="replace") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            if row.get("event") != "SEND_RESULT":
                continue
            if row.get("status") not in {"ENTER_SENT", "OK", "SENT"}:
                continue
            try:
                sequence = int(row.get("sequence", ""))
            except ValueError:
                continue
            utc_raw = (row.get("utc_time") or "").strip()
            try:
                utc_dt = datetime.strptime(utc_raw, "%Y-%m-%d %H:%M:%S.%f").replace(
                    tzinfo=timezone.utc
                )
            except ValueError:
                continue
            rows[sequence] = {
                "sequence": sequence,
                "utc_time": utc_dt.isoformat(timespec="milliseconds"),
                "epoch_ms": int(utc_dt.timestamp() * 1000),
                "message": row.get("message", ""),
                "session_id": row.get("session_id", ""),
            }
    return rows


def pair_outages(source: str, events: list[ConnectionEvent]) -> list[Outage]:
    ordered = sorted(
        events,
        key=lambda item: (
            item.epoch_ms if item.epoch_ms > 0 else math.inf,
            item.local_time,
        ),
    )
    outages: list[Outage] = []
    eof_positions = [index for index, item in enumerate(ordered) if item.kind == "EOF"]
    for outage_number, position in enumerate(eof_positions, start=1):
        eof = ordered[position]
        next_eof_position = (
            eof_positions[outage_number]
            if outage_number < len(eof_positions)
            else len(ordered)
        )
        window = ordered[position + 1 : next_eof_position]
        attempt = next((item for item in window if item.kind == "RECONNECT_ATTEMPT"), None)
        connected = next((item for item in window if item.kind == "CONNECTED"), None)

        def duration_ms(other: Optional[ConnectionEvent]) -> int:
            if other is None:
                return -1
            if eof.epoch_ms > 0 and other.epoch_ms > 0:
                return max(0, other.epoch_ms - eof.epoch_ms)
            try:
                start = datetime.fromisoformat(eof.local_time)
                end = datetime.fromisoformat(other.local_time)
                return max(0, int((end - start).total_seconds() * 1000))
            except ValueError:
                return -1

        outages.append(
            Outage(
                source=source,
                outage_index=outage_number,
                eof_local_time=eof.local_time,
                eof_utc_time=eof.utc_time,
                reconnect_attempt_local_time=attempt.local_time if attempt else "",
                reconnect_attempt_utc_time=attempt.utc_time if attempt else "",
                connected_local_time=connected.local_time if connected else "",
                connected_utc_time=connected.utc_time if connected else "",
                reconnect_attempt_delay_ms=duration_ms(attempt),
                connected_delay_ms=duration_ms(connected),
            )
        )
    return outages


def correlate(
    deliveries: list[PushDelivery],
    outages: list[Outage],
    sends: dict[int, dict[str, Any]],
) -> None:
    outages_by_source: dict[str, list[Outage]] = {}
    for outage in outages:
        outages_by_source.setdefault(outage.source, []).append(outage)

    for delivery in deliveries:
        send = sends.get(delivery.sequence)
        if send:
            delivery.send_csv_utc = str(send["utc_time"])
            delivery.send_csv_epoch_ms = int(send["epoch_ms"])
            delivery.sender_csv_difference_ms = (
                delivery.sender_epoch_ms - delivery.send_csv_epoch_ms
            )
        for outage in outages_by_source.get(delivery.source, []):
            eof_epoch = epoch_from_iso(outage.eof_utc_time)
            connected_epoch = epoch_from_iso(outage.connected_utc_time)
            if eof_epoch <= 0 or connected_epoch <= 0:
                continue
            if eof_epoch <= delivery.sender_epoch_ms <= connected_epoch:
                delivery.during_outage = True
                delivery.outage_index = outage.outage_index
                break

    for outage in outages:
        eof_epoch = epoch_from_iso(outage.eof_utc_time)
        connected_epoch = epoch_from_iso(outage.connected_utc_time)
        if eof_epoch <= 0 or connected_epoch <= 0:
            continue
        outage.messages_sent_during_outage = sum(
            1 for send in sends.values()
            if eof_epoch <= int(send["epoch_ms"]) <= connected_epoch
        )
        outage.messages_arrived_after_outage = sum(
            1 for delivery in deliveries
            if delivery.source == outage.source
            and delivery.outage_index == outage.outage_index
        )


def epoch_from_iso(value: str) -> int:
    if not value:
        return 0
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return int(parsed.timestamp() * 1000)
    except ValueError:
        return 0


def percentile(values: list[int], fraction: float) -> int:
    if not values:
        return -1
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(fraction * len(ordered)) - 1))
    return ordered[index]


def format_duration(ms: int) -> str:
    if ms < 0:
        return "未恢复"
    seconds = ms / 1000
    if seconds < 60:
        return f"{seconds:.3f} 秒"
    minutes = seconds / 60
    if minutes < 60:
        return f"{minutes:.2f} 分钟"
    return f"{minutes / 60:.2f} 小时"


def backlog_groups(
    deliveries: list[PushDelivery],
    threshold_ms: int,
    release_gap_ms: int,
) -> list[dict[str, Any]]:
    delayed = sorted(
        [item for item in deliveries if item.delay_ms >= threshold_ms],
        key=lambda item: (item.source, item.seen_wall_ms),
    )
    groups: list[list[PushDelivery]] = []
    for item in delayed:
        if (
            not groups
            or groups[-1][-1].source != item.source
            or item.seen_wall_ms - groups[-1][-1].seen_wall_ms > release_gap_ms
        ):
            groups.append([item])
        else:
            groups[-1].append(item)
    output: list[dict[str, Any]] = []
    for index, group in enumerate(groups, start=1):
        output.append(
            {
                "source": group[0].source,
                "backlog_index": index,
                "release_utc": iso_utc_epoch(group[0].seen_wall_ms),
                "release_span_ms": group[-1].seen_wall_ms - group[0].seen_wall_ms,
                "message_count": len(group),
                "first_sequence": min(item.sequence for item in group),
                "last_sequence": max(item.sequence for item in group),
                "max_delay_ms": max(item.delay_ms for item in group),
                "min_delay_ms": min(item.delay_ms for item in group),
                "outage_indices": ",".join(
                    str(value)
                    for value in sorted(
                        {item.outage_index for item in group if item.outage_index}
                    )
                ),
            }
        )
    return output


def cross_device_rows(deliveries: list[PushDelivery]) -> list[dict[str, Any]]:
    by_sequence: dict[int, list[PushDelivery]] = {}
    all_sources = sorted({item.source for item in deliveries})
    for item in deliveries:
        by_sequence.setdefault(item.sequence, []).append(item)
    rows: list[dict[str, Any]] = []
    for sequence, items in sorted(by_sequence.items()):
        source_map = {item.source: item for item in items}
        row: dict[str, Any] = {
            "sequence": sequence,
            "source_count": len(source_map),
            "all_source_count": len(all_sources),
            "arrived_on_all_sources": len(source_map) == len(all_sources),
            "max_delay_ms": max(item.delay_ms for item in items),
            "min_delay_ms": min(item.delay_ms for item in items),
            "delay_spread_ms": max(item.delay_ms for item in items)
            - min(item.delay_ms for item in items),
            "sources": ",".join(sorted(source_map)),
        }
        for source in all_sources:
            item = source_map.get(source)
            row[f"{source}_delay_ms"] = item.delay_ms if item else ""
            row[f"{source}_seen_utc"] = (
                iso_utc_epoch(item.seen_wall_ms) if item else ""
            )
        rows.append(row)
    return rows


def missing_rows(
    deliveries: list[PushDelivery],
    sends: dict[int, dict[str, Any]],
) -> list[dict[str, Any]]:
    if not deliveries or not sends:
        return []
    min_epoch = min(item.sender_epoch_ms for item in deliveries) - 5 * 60_000
    max_epoch = max(item.seen_wall_ms for item in deliveries) + 5 * 60_000
    arrived_by_source: dict[str, set[int]] = {}
    for item in deliveries:
        arrived_by_source.setdefault(item.source, set()).add(item.sequence)
    rows: list[dict[str, Any]] = []
    for source, arrived in arrived_by_source.items():
        for sequence, send in sorted(sends.items()):
            epoch = int(send["epoch_ms"])
            if min_epoch <= epoch <= max_epoch and sequence not in arrived:
                rows.append(
                    {
                        "source": source,
                        "sequence": sequence,
                        "send_utc": send["utc_time"],
                        "message": send["message"],
                    }
                )
    return rows


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    if not rows:
        path.write_text("", encoding="utf-8-sig")
        return
    fieldnames: list[str] = []
    for row in rows:
        for key in row:
            if key not in fieldnames:
                fieldnames.append(key)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def build_summary(
    sources: list[str],
    deliveries: list[PushDelivery],
    outages: list[Outage],
    backlogs: list[dict[str, Any]],
    missing: list[dict[str, Any]],
    metadata: dict[str, Any],
) -> str:
    lines = [
        "# Luonnotar push session analysis",
        "",
        f"Generated: {datetime.now(timezone.utc).isoformat(timespec='seconds')}",
        "",
    ]
    for source in sources:
        source_deliveries = [item for item in deliveries if item.source == source]
        source_outages = [item for item in outages if item.source == source]
        source_backlogs = [item for item in backlogs if item["source"] == source]
        source_missing = [item for item in missing if item["source"] == source]
        delays = [item.delay_ms for item in source_deliveries]
        longest = max(
            (item.connected_delay_ms for item in source_outages),
            default=-1,
        )
        lines.extend(
            [
                f"## {source}",
                "",
                f"- Arrivals: {len(source_deliveries)}",
                f"- Median delay: {format_duration(percentile(delays, 0.50))}",
                f"- P95 delay: {format_duration(percentile(delays, 0.95))}",
                f"- Maximum delay: {format_duration(max(delays, default=-1))}",
                f"- Delays over 10 seconds: {sum(value >= 10_000 for value in delays)}",
                f"- Delays over 1 minute: {sum(value >= 60_000 for value in delays)}",
                f"- Delays over 10 minutes: {sum(value >= 600_000 for value in delays)}",
                f"- WhatsApp EOF outages: {len(source_outages)}",
                f"- Longest EOF-to-connected interval: {format_duration(longest)}",
                f"- Backlog release groups: {len(source_backlogs)}",
                f"- Sent sequences without arrival evidence in the observed window: {len(source_missing)}",
                "",
            ]
        )
        if source_outages:
            lines.extend(
                [
                    "| # | EOF | Reconnect attempt | Connected | EOF→attempt | EOF→connected |",
                    "|---:|---|---|---|---:|---:|",
                ]
            )
            for item in sorted(
                source_outages,
                key=lambda value: value.outage_index,
            ):
                lines.append(
                    f"| {item.outage_index} | {item.eof_local_time or item.eof_utc_time} | "
                    f"{item.reconnect_attempt_local_time or item.reconnect_attempt_utc_time or '—'} | "
                    f"{item.connected_local_time or item.connected_utc_time or '—'} | "
                    f"{format_duration(item.reconnect_attempt_delay_ms)} | "
                    f"{format_duration(item.connected_delay_ms)} |"
                )
            lines.append("")

    if len(sources) > 1:
        lines.extend(
            [
                "## Cross-device note",
                "",
                "`cross-device-sequences.csv` compares the same PUSH_TEST sequence "
                "across all supplied captures. A missing source is absence of evidence "
                "inside that capture, not automatic proof that WhatsApp never delivered it.",
                "",
            ]
        )

    lines.extend(
        [
            "## Interpretation boundaries",
            "",
            "- EOF pairing uses the first subsequent WhatsApp reconnect attempt and connected state before the next EOF.",
            "- Push delay is taken from Luonnotar's persisted sender epoch and seen-wall evidence, not from device-local clock subtraction.",
            "- Missing-arrival rows are limited to the observed send-time window and may include evidence lost because Logcat rotated.",
            "- Multi-device comparison only classifies the evidence supplied to this run.",
            "",
            "## Inputs",
            "",
            "```json",
            json.dumps(metadata, ensure_ascii=False, indent=2),
            "```",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> int:
    args = parse_args()
    input_paths = [Path(value).expanduser().resolve() for value in args.input]
    for path in input_paths:
        if not path.exists():
            raise FileNotFoundError(path)
    if args.source_name and len(args.source_name) != len(input_paths):
        raise ValueError("--source-name count must match --input count")

    if args.output:
        output_dir = Path(args.output).expanduser().resolve()
    else:
        output_dir = input_paths[0].parent / (
            "luonnotar-analysis-" + datetime.now().strftime("%Y%m%d-%H%M%S")
        )
    output_dir.mkdir(parents=True, exist_ok=True)

    all_deliveries: list[PushDelivery] = []
    all_connections: list[ConnectionEvent] = []
    all_experiment_events: list[ExperimentEvent] = []
    source_metadata: dict[str, Any] = {}
    embedded_send_paths: list[Path] = []

    with tempfile.TemporaryDirectory(prefix="luonnotar-analysis-") as temp_raw:
        temp_root = Path(temp_raw)
        for index, input_path in enumerate(input_paths):
            source = (
                args.source_name[index]
                if args.source_name
                else safe_source_name(input_path)
            )
            work_root = input_path
            if input_path.is_file() and zipfile.is_zipfile(input_path):
                work_root = temp_root / f"input-{index}"
                work_root.mkdir(parents=True, exist_ok=True)
                extract_zip_normalized(input_path, work_root)
            elif not input_path.is_dir():
                raise ValueError(f"Unsupported input: {input_path}")

            logcat = choose_logcat_file(work_root)
            metadata: dict[str, Any] = {
                "input": str(input_path),
                "working_root": str(work_root),
            }
            if logcat:
                deliveries, connections, experiment_events, log_metadata = (
                    parse_logcat(source, logcat)
                )
                all_deliveries.extend(deliveries)
                all_connections.extend(connections)
                all_experiment_events.extend(experiment_events)
                metadata.update(log_metadata)
            else:
                metadata["logcat_file"] = None

            jsonl_paths = list(work_root.rglob("*.jsonl"))
            json_deliveries, json_events = parse_jsonl(source, jsonl_paths)
            all_deliveries.extend(json_deliveries)
            all_experiment_events.extend(json_events)
            metadata["jsonl_files"] = [str(path) for path in jsonl_paths]

            embedded = choose_send_csv(work_root)
            if embedded:
                embedded_send_paths.append(embedded)
            source_metadata[source] = metadata

        explicit_send = (
            Path(args.send_events).expanduser().resolve()
            if args.send_events
            else None
        )
        send_path = explicit_send or (
            max(embedded_send_paths, key=lambda path: path.stat().st_size)
            if embedded_send_paths
            else None
        )
        sends = parse_send_events(send_path)

        deliveries = deduplicate_deliveries(all_deliveries)
        sources = sorted(source_metadata)
        outages: list[Outage] = []
        for source in sources:
            source_events = [
                item for item in all_connections if item.source == source
            ]
            outages.extend(pair_outages(source, source_events))
        correlate(deliveries, outages, sends)

        backlogs = backlog_groups(
            deliveries,
            args.backlog_threshold_ms,
            args.backlog_release_gap_ms,
        )
        missing = missing_rows(deliveries, sends)
        cross = cross_device_rows(deliveries)

        write_csv(
            output_dir / "push-deliveries.csv",
            [asdict(item) for item in deliveries],
        )
        write_csv(
            output_dir / "whatsapp-outages.csv",
            [asdict(item) for item in outages],
        )
        write_csv(output_dir / "backlog-releases.csv", backlogs)
        write_csv(output_dir / "missing-arrival-evidence.csv", missing)
        write_csv(output_dir / "cross-device-sequences.csv", cross)
        write_csv(
            output_dir / "experiment-events.csv",
            [asdict(item) for item in all_experiment_events],
        )

        metadata = {
            "format_version": 1,
            "inputs": source_metadata,
            "send_events_csv": str(send_path) if send_path else None,
            "send_event_count": len(sends),
            "delivery_count": len(deliveries),
            "outage_count": len(outages),
            "backlog_group_count": len(backlogs),
            "missing_evidence_count": len(missing),
            "backlog_threshold_ms": args.backlog_threshold_ms,
            "backlog_release_gap_ms": args.backlog_release_gap_ms,
        }
        (output_dir / "analysis-metadata.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        (output_dir / "summary.md").write_text(
            build_summary(
                sources,
                deliveries,
                outages,
                backlogs,
                missing,
                metadata,
            ),
            encoding="utf-8",
        )

    print(f"Analysis written to: {output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
