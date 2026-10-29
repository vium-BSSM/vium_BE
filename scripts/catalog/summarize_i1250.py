"""Build deterministic, offline I1250 duration histograms from local snapshots."""

from collections import Counter
import csv
import json
from pathlib import Path
import re
import sys

from build_seed import parse_pog_daycnt
from fetch_i1250 import safe_filename, save_snapshot

ROOT = Path(__file__).resolve().parents[2]


def summarize(snapshot, food_type):
    if snapshot.get("food_type") != food_type:
        raise ValueError(f"{food_type} 원본 식품유형 불일치")
    rows = snapshot["rows"]
    recent = 0
    days = Counter()
    for row in rows:
        date = row.get("prms_dt", "")
        if not isinstance(date, str) or not re.fullmatch(r"\d{8}", date) or date < "20150101":
            continue
        recent += 1
        value = parse_pog_daycnt(row.get("pog_daycnt"))
        if value is not None:
            days[value] += 1
    return {
        "fetched_at": snapshot["fetched_at"],
        "total_count": snapshot["total_count"],
        "saved_rows": len(rows),
        "recent_rows": recent,
        "parsed_rows": sum(days.values()),
        "days": {str(day): days[day] for day in sorted(days)},
    }


def main(root=ROOT):
    try:
        with (root / "data/catalog/ingredients.csv").open(encoding="utf-8-sig", newline="") as stream:
            types = sorted({row["mfds_type"].strip() for row in csv.DictReader(stream)
                            if row["mfds_type"].strip()})
        stats = {}
        missing = []
        for food_type in types:
            path = root / "data/raw/i1250" / (safe_filename(food_type) + ".json")
            if not path.is_file():
                missing.append(f"{food_type} 원본 없음 — fetch_i1250.py {food_type} 실행")
                continue
            with path.open(encoding="utf-8") as stream:
                stats[food_type] = summarize(json.load(stream), food_type)
        if missing:
            for message in missing:
                print(message, file=sys.stderr)
            return 1
        # Top-level types are sorted; days deliberately uses numeric, not lexical order.
        save_snapshot(root / "data/catalog/i1250_stats.json", stats)
        for food_type, values in stats.items():
            print(f"{food_type}: {values['parsed_rows']}건 저장")
        return 0
    except (OSError, ValueError, KeyError, TypeError):
        print("통계 생성 실패: 입력 CSV·원본 JSON 형식과 저장 경로를 확인하세요", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
