from __future__ import annotations

import importlib.util
import tempfile
import sys
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "analyze-luonnotar-push-session.py"
SPEC = importlib.util.spec_from_file_location("luonnotar_analyzer", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class AnalyzerTest(unittest.TestCase):
    def test_pairs_eof_and_reconnect_and_reads_push_delay(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            log = Path(raw) / "logcat-final.txt"
            log.write_text(
                "\n".join(
                    [
                        "07-31 13:00:00.000  100  101 I Luonnotar: "
                        "push_test_arrival_observed {packageName=com.whatsapp, "
                        "observationSource=LIVE_CALLBACK, sequence=10, "
                        "senderEpochMs=1785473998000, notificationPostTime=1785474000000, "
                        "seenWall=1785474001000, endToEndDelayMs=3000}",
                        "07-31 13:05:00.000  200  201 E msys: "
                        "MNSTCPSocketReceive(): Received EOF",
                        "07-31 13:07:00.000  200  201 E msys: "
                        "WAJMNSStream/impl/tcpStreamCreate",
                        "07-31 13:07:00.250  200  201 E msys: "
                        "WAJMNSStream/impl/state-changed (connecting=>connected)",
                    ]
                ),
                encoding="utf-8",
            )
            deliveries, connections, _, metadata = MODULE.parse_logcat(
                "iQOO", log
            )
            outages = MODULE.pair_outages("iQOO", connections)
            self.assertEqual(1, len(deliveries))
            self.assertEqual(3000, deliveries[0].delay_ms)
            self.assertEqual(1, len(outages))
            self.assertEqual(120000, outages[0].reconnect_attempt_delay_ms)
            self.assertEqual(120250, outages[0].connected_delay_ms)
            self.assertIsNotNone(
                metadata["inferred_device_utc_offset_minutes"]
            )

    def test_backlog_groups_same_release_burst(self) -> None:
        deliveries = [
            MODULE.PushDelivery(
                source="iQOO",
                sequence=index,
                sender_epoch_ms=1_000 + index,
                seen_wall_ms=10_000 + index * 100,
                delay_ms=20_000,
                post_time_ms=0,
                observation_source="LIVE_CALLBACK",
                package_name="com.whatsapp",
                log_local_time="",
                log_utc_time="",
                raw_file="",
            )
            for index in range(1, 4)
        ]
        groups = MODULE.backlog_groups(deliveries, 10_000, 5_000)
        self.assertEqual(1, len(groups))
        self.assertEqual(3, groups[0]["message_count"])


if __name__ == "__main__":
    unittest.main()
