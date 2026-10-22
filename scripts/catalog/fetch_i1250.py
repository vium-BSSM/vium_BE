"""Fetch I1250 snapshots without logging credentials or request URLs."""

import argparse
import csv
from datetime import datetime
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
from urllib.parse import quote
from urllib.request import urlopen
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[2]
FIELDS = {
    "report_no": "PRDLST_REPORT_NO", "name": "PRDLST_NM",
    "pog_daycnt": "POG_DAYCNT", "prms_dt": "PRMS_DT", "last_updt": "LAST_UPDT_DTM",
}


class ApiResultError(RuntimeError):
    """Expose only a validated result code, never an upstream message or URL."""

    def __init__(self, code):
        safe_code = code if isinstance(code, str) and re.fullmatch(r"(?:INFO|ERROR)-\d{3}", code) else "UNKNOWN"
        super().__init__(f"API 결과 오류 ({safe_code})")


def read_key(root=ROOT):
    key = os.environ.get("FOODSAFETY_API_KEY", "").strip()
    if key:
        return key
    path = root / ".env"
    if path.exists():
        with path.open(encoding="utf-8") as stream:
            for line in stream:
                match = re.match(r"^\s*FOODSAFETY_API_KEY\s*=(.*)$", line)
                if match:
                    return match[1].strip().strip("\"'")
    return ""


def safe_filename(food_type):
    return re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", food_type).strip(" .") or "_"


def request_page(key, food_type, start, end):
    # HTTPS prevents exposing the API key to intermediaries over plain HTTP.
    url = ("https://openapi.foodsafetykorea.go.kr/api/" + quote(key, safe="")
           + f"/I1250/json/{start}/{end}/PRDLST_DCNM=" + quote(food_type, safe=""))
    for attempt in range(3):
        time.sleep(0.3)
        try:
            with urlopen(url, timeout=30) as response:
                body = json.load(response)
                payload = body.get("I1250", body)
            code = payload.get("RESULT", {}).get("CODE")
            if code not in ("INFO-000", "INFO-200"):
                raise ApiResultError(code)
            return payload
        except ApiResultError:
            raise
        except Exception:
            # Exception text and upstream MSG can contain the credential URL.
            if attempt == 2:
                raise RuntimeError("API 요청 실패 (응답 코드·네트워크·JSON 확인 필요)") from None


def collect(key, food_type, fetch=request_page):
    start = 1
    rows = {}
    excluded_other_types = 0
    while True:
        payload = fetch(key, food_type, start, start + 999)
        if payload.get("RESULT", {}).get("CODE") == "INFO-200":
            if start != 1:
                raise ValueError("페이지 수집 도중 결과 없음")
            return {"food_type": food_type, "fetched_at": today(), "total_count": 0,
                    "excluded_other_types": 0, "rows": []}
        total = int(payload["total_count"])
        page = payload.get("row", [])
        if total < 0 or not isinstance(page, list) or (not page and start <= total):
            raise ValueError("잘못된 페이지 응답")
        for row in page:
            if not row.get("PRDLST_REPORT_NO"):
                raise ValueError("품목제조번호 누락")
            # PRDLST_DCNM is a substring search; retain only exact type matches.
            if row.get("PRDLST_DCNM") != food_type:
                excluded_other_types += 1
                continue
            normalized = {target: row.get(source, "") for target, source in FIELDS.items()}
            report = normalized["report_no"]
            if report not in rows or normalized["last_updt"] > rows[report]["last_updt"]:
                rows[report] = normalized
        if start + 999 >= total:
            break
        start += 1000
    return {"food_type": food_type, "fetched_at": today(), "total_count": total,
            "excluded_other_types": excluded_other_types,
            "rows": [rows[key] for key in sorted(rows)]}


def today():
    return datetime.now(ZoneInfo("Asia/Seoul")).date().isoformat()


def save_snapshot(path, snapshot):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(snapshot, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("food_types", nargs="*")
    args = parser.parse_args(argv)
    key = read_key()
    if not key:
        print("FOODSAFETY_API_KEY가 없습니다", file=sys.stderr)
        return 1
    types = args.food_types
    if not types:
        try:
            with (ROOT / "data/catalog/ingredients.csv").open(encoding="utf-8-sig", newline="") as stream:
                types = [row["mfds_type"].strip() for row in csv.DictReader(stream) if row["mfds_type"].strip()]
        except (OSError, KeyError):
            print("ingredients.csv가 없거나 mfds_type 컬럼이 없습니다. 식품유형을 인자로 지정하세요.", file=sys.stderr)
            return 1
    types = list(dict.fromkeys(types))
    filenames = [safe_filename(value) for value in types]
    if len(set(filenames)) != len(filenames) or any(not value.strip() for value in types):
        print("식품유형이 비어 있거나 저장 파일명이 충돌합니다", file=sys.stderr)
        return 1
    failed = False
    for food_type, filename in zip(types, filenames):
        try:
            snapshot = collect(key, food_type)
            save_snapshot(ROOT / "data/raw/i1250" / (filename + ".json"), snapshot)
            print(f"{food_type}: {len(snapshot['rows'])}건 저장")
            if not snapshot["rows"]:
                print(f"식품유형 '{food_type}' 결과 없음 — ingredients.csv의 mfds_type을 확인하세요")
        except ApiResultError as error:
            print(f"{food_type}: 수집 실패 — {error}", file=sys.stderr)
            failed = True
        except Exception:
            print(f"{food_type}: 수집 실패 (인증·API 응답·네트워크·저장 경로 확인 필요)", file=sys.stderr)
            failed = True
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
