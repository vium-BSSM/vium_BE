from contextlib import redirect_stderr
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import fetch_i1250 as fetcher


def row(report, updated="20260101"):
    return {"PRDLST_REPORT_NO": report, "PRDLST_DCNM": "두부", "PRDLST_NM": "제품",
            "POG_DAYCNT": "14일", "PRMS_DT": "20200101", "LAST_UPDT_DTM": updated}


def page(rows, total):
    return {"RESULT": {"CODE": "INFO-000"}, "total_count": str(total), "row": rows}


class FetchTests(unittest.TestCase):
    def test_pagination_dedup_and_projection(self):
        calls = []
        def request(key, food_type, start, end):
            calls.append((start, end))
            return page([row("b"), row("a")], 1001) if start == 1 else page([row("a", "20260201")], 1001)
        result = fetcher.collect("secret", "두부", request)
        self.assertEqual([(1, 1000), (1001, 2000)], calls)
        self.assertEqual(["a", "b"], [r["report_no"] for r in result["rows"]])
        self.assertEqual("20260201", result["rows"][0]["last_updt"])
        self.assertEqual(set(fetcher.FIELDS), set(result["rows"][0]))

    def test_empty_result_and_incomplete_page(self):
        empty = lambda *args: {"RESULT": {"CODE": "INFO-200"}}
        self.assertEqual(0, fetcher.collect("secret", "두부", empty)["total_count"])
        with self.assertRaises(ValueError):
            fetcher.collect("secret", "두부", lambda *args: page([], 5))

    def test_other_types_excluded_across_pages(self):
        other = dict(row("other"), PRDLST_DCNM="가공두부")
        calls = []
        def request(key, food_type, start, end):
            calls.append(start)
            return page([other], 1001) if start == 1 else page([other, row("a")], 1001)
        result = fetcher.collect("secret", "두부", request)
        self.assertEqual([1, 1001], calls)
        self.assertEqual(["a"], [r["report_no"] for r in result["rows"]])
        self.assertEqual(1001, result["total_count"])
        self.assertEqual(2, result["excluded_other_types"])

    def test_all_other_types_produce_empty_snapshot(self):
        result = fetcher.collect("secret", "어묵", lambda *args: page([row("a")], 1))
        self.assertEqual([], result["rows"])
        self.assertEqual(1, result["excluded_other_types"])

    def test_missing_report_number_still_rejected(self):
        for food_type in ("두부", "어묵"):
            with self.subTest(food_type=food_type), self.assertRaises(ValueError):
                fetcher.collect("secret", food_type, lambda *args: page([row("")], 1))

    def test_result_errors_are_not_retried(self):
        for nested in (True, False):
            payload = {"RESULT": {"CODE": "INFO-100", "MSG": "https://host/secret"}}
            body = {"I1250": payload} if nested else payload
            with self.subTest(nested=nested), \
                    patch.object(fetcher, "urlopen") as request, \
                    patch.object(fetcher.time, "sleep"):
                request.return_value.__enter__.return_value = io.StringIO(json.dumps(body))
                with self.assertRaisesRegex(fetcher.ApiResultError, "INFO-100") as error:
                    fetcher.request_page("secret", "두부", 1, 1000)
                self.assertEqual(1, request.call_count)
                self.assertNotIn("secret", str(error.exception))

    def test_cli_reports_safe_api_code(self):
        with patch.object(fetcher, "read_key", return_value="secret"), \
                patch.object(fetcher, "collect", side_effect=fetcher.ApiResultError("INFO-100")), \
                redirect_stderr(io.StringIO()) as output:
            self.assertEqual(1, fetcher.main(["두부"]))
        self.assertIn("INFO-100", output.getvalue())
        self.assertNotIn("secret", output.getvalue())
        self.assertNotIn("secret", str(fetcher.ApiResultError("https://host/secret")))

    def test_retries_do_not_expose_secret(self):
        with patch.object(fetcher, "urlopen", side_effect=OSError("https://host/secret")) as request, \
                patch.object(fetcher.time, "sleep"):
            with self.assertRaises(RuntimeError) as error:
                fetcher.request_page("secret", "두부", 1, 1000)
        self.assertEqual(3, request.call_count)
        self.assertNotIn("secret", str(error.exception))

    def test_env_key_precedence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".env").write_text('OTHER=private\nFOODSAFETY_API_KEY="file-key"\n')
            with patch.dict(os.environ, {"FOODSAFETY_API_KEY": "environment-key"}):
                self.assertEqual("environment-key", fetcher.read_key(root))
            with patch.dict(os.environ, {}, clear=True):
                self.assertEqual("file-key", fetcher.read_key(root))

    def test_atomic_snapshot_and_filename(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "두부.json"
            fetcher.save_snapshot(path, {"food_type": "두부", "rows": []})
            self.assertEqual("두부", json.loads(path.read_text())["food_type"])
            self.assertEqual([path], list(Path(directory).iterdir()))
        self.assertEqual("두부_어묵", fetcher.safe_filename("두부/어묵"))

    def test_missing_key(self):
        with patch.object(fetcher, "read_key", return_value=""), redirect_stderr(io.StringIO()) as output:
            self.assertEqual(1, fetcher.main(["두부"]))
        self.assertIn("FOODSAFETY_API_KEY가 없습니다", output.getvalue())

    def test_failure_continues_other_types(self):
        with patch.object(fetcher, "read_key", return_value="secret"), \
                patch.object(fetcher, "collect", side_effect=RuntimeError("secret")) as collect, \
                redirect_stderr(io.StringIO()) as output:
            self.assertEqual(1, fetcher.main(["두부", "어묵"]))
        self.assertEqual(2, collect.call_count)
        self.assertNotIn("secret", output.getvalue())
