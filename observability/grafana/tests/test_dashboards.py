"""Regression checks for provisioned dashboard navigation and filter contracts."""
import json
from pathlib import Path
import re
import unittest


DASHBOARDS = Path(__file__).resolve().parents[1] / "dashboards"


def walk(panels):
    for panel in panels:
        yield panel
        yield from walk(panel.get("panels", []))


class DashboardTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dashboards = {
            path.stem: json.loads(path.read_text())
            for path in DASHBOARDS.glob("*.json")
        }

    def test_navigation_resolves_to_existing_dashboard_uids(self):
        uids = {dashboard["uid"] for dashboard in self.dashboards.values()}
        for name, dashboard in self.dashboards.items():
            for link in dashboard.get("links", []):
                with self.subTest(dashboard=name, link=link["title"]):
                    self.assertIn(link["url"].split("/")[2], uids)
                    self.assertTrue(link["keepTime"])
            for panel in walk(dashboard["panels"]):
                for uid in re.findall(r"\]\(/d/([^/)]+)", panel.get("options", {}).get("content", "")):
                    self.assertIn(uid, uids)

    def test_pod_queries_apply_both_filters_when_all_pods_is_selected(self):
        dashboard = self.dashboards["pods"]
        for panel in walk(dashboard["panels"]):
            for target in panel.get("targets", []):
                # Selecting All expands Pod to .*. Each metric selector must
                # still constrain the workload group independently.
                selectors = re.findall(r"\{[^}]*namespace=[^}]*\}",
                                       target["expr"].replace("${pod:regex}", ".*")
                                       .replace("${app_group:raw}", "GROUP"))
                selected = [selector for selector in selectors if 'pod=~".*"' in selector]
                with self.subTest(panel=panel["title"], query=target["refId"]):
                    self.assertTrue(selected)
                    for selector in selected:
                        self.assertIn('pod=~"GROUP"', selector)

    def test_panel_ids_and_visible_layout_are_valid(self):
        def check_layout(panels, name):
            for i, panel in enumerate(panels):
                a = panel["gridPos"]
                self.assertGreater(a["w"], 0)
                self.assertLessEqual(a["x"] + a["w"], 24)
                for other in panels[i + 1:]:
                    b = other["gridPos"]
                    overlap = (a["x"] < b["x"] + b["w"] and b["x"] < a["x"] + a["w"]
                               and a["y"] < b["y"] + b["h"] and b["y"] < a["y"] + a["h"])
                    self.assertFalse(overlap, f'{name}: {panel["title"]} overlaps {other["title"]}')
                check_layout(panel.get("panels", []), name)

        for name, dashboard in self.dashboards.items():
            ids = [panel["id"] for panel in walk(dashboard["panels"])]
            self.assertEqual(len(ids), len(set(ids)), name)
            check_layout(dashboard["panels"], name)

    def test_data_details_are_on_dedicated_dashboards(self):
        overview = self.dashboards["data-explorer"]
        self.assertEqual(8, sum(panel["type"] == "stat" for panel in overview["panels"]))
        self.assertFalse(any(panel.get("panels") for panel in overview["panels"]))
        self.assertIn("Tables — rows, size, scans", {p["title"] for p in walk(self.dashboards["postgres-debug"]["panels"])})
        self.assertIn("Keyspace", {p["title"] for p in walk(self.dashboards["redis-debug"]["panels"])})
        # Prefix queries use KEYS and should only execute when explicitly opened.
        for panel in self.dashboards["redis-debug"]["panels"]:
            self.assertNotIn("redis.call('keys'", json.dumps(panel.get("targets", [])))


if __name__ == "__main__":
    unittest.main()
