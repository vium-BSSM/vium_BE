from contextlib import redirect_stdout, redirect_stderr
import io
import json
from pathlib import Path
import tempfile
import unittest

from summarize_i1250 import main, summarize


def snapshot():
    return {"food_type": "두부", "fetched_at": "2026-10-22", "total_count": 100,
            "rows": [
                {"prms_dt": "20141231", "pog_daycnt": "1일"},
                {"prms_dt": "20150101", "pog_daycnt": "2일"},
                {"prms_dt": "20261022", "pog_daycnt": "10일"},
                {"prms_dt": "20261022", "pog_daycnt": "제조일로부터 10일간"},
                {"prms_dt": "20261022", "pog_daycnt": "냉장 7일"},
                {"prms_dt": None, "pog_daycnt": "1일"},
                {"prms_dt": "20261022", "pog_daycnt": "3650일"},
            ]}


class SummaryTests(unittest.TestCase):
    def test_histogram_counts_and_filter_boundary(self):
        result = summarize(snapshot(), "두부")
        self.assertEqual(100, result["total_count"])
        self.assertEqual(7, result["saved_rows"])
        self.assertEqual(5, result["recent_rows"])
        self.assertEqual(4, result["parsed_rows"])
        self.assertEqual({"2": 1, "10": 2, "3650": 1}, result["days"])
        self.assertEqual(["2", "10", "3650"], list(result["days"]))
        self.assertEqual("2026-10-22", result["fetched_at"])

    def test_empty_and_wrong_type(self):
        data = snapshot()
        data["rows"] = []
        self.assertEqual({}, summarize(data, "두부")["days"])
        with self.assertRaises(ValueError):
            summarize(data, "김치")

    def test_cli_uses_only_selected_types_and_is_deterministic(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            catalog = root / "data/catalog"
            raw = root / "data/raw/i1250"
            catalog.mkdir(parents=True)
            raw.mkdir(parents=True)
            (catalog / "ingredients.csv").write_text("mfds_type\n두부\n두부\n\n", encoding="utf-8")
            (raw / "두부.json").write_text(json.dumps(snapshot(), ensure_ascii=False), encoding="utf-8")
            (raw / "unused.json").write_text("invalid JSON", encoding="utf-8")
            with redirect_stdout(io.StringIO()):
                self.assertEqual(0, main(root))
                first = (catalog / "i1250_stats.json").read_bytes()
                self.assertEqual(0, main(root))
            self.assertEqual(first, (catalog / "i1250_stats.json").read_bytes())
            text = first.decode("utf-8")
            self.assertIn('  "두부": {', text)
            self.assertTrue(text.endswith("\n"))
            self.assertEqual(["두부"], list(json.loads(text)))

    def test_missing_input_does_not_replace_existing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            catalog = root / "data/catalog"
            catalog.mkdir(parents=True)
            (catalog / "ingredients.csv").write_text("mfds_type\n두부\n김치\n", encoding="utf-8")
            output = catalog / "i1250_stats.json"
            output.write_text("previous", encoding="utf-8")
            with redirect_stderr(io.StringIO()) as errors:
                self.assertEqual(1, main(root))
            self.assertIn("두부 원본 없음", errors.getvalue())
            self.assertIn("김치 원본 없음", errors.getvalue())
            self.assertEqual("previous", output.read_text())
