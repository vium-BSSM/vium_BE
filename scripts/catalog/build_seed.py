"""Catalog source normalization. SQL generation is added in plan step 3."""

import re


def parse_pog_daycnt(value):
    """Accept only unconditional durations; never guess conditional label text."""
    if not isinstance(value, str):
        return None
    value = re.sub(r"\s+", "", value)
    value = re.sub(r"^(제조일로부터|제조일자로부터|제조일부터|제조후|제조일로)", "", value)
    match = re.fullmatch(r"(\d+)(일|주|개월|달|년|시간)(간|까지|이내)?", value)
    if not match:
        return None
    amount = int(match[1])
    if amount <= 0:
        return None
    unit = match[2]
    days = max(1, amount // 24) if unit == "시간" else amount * {
        "일": 1, "주": 7, "개월": 30, "달": 30, "년": 365,
    }[unit]
    return days if 0 < days <= 3650 else None


def trimmed_p25(values):
    """Return (nearest-rank lower quartile, retained sample count)."""
    ordered = sorted(values)
    trim = len(ordered) // 20
    retained = ordered[trim:len(ordered) - trim] if trim else ordered
    count = len(retained)
    return (retained[(count + 3) // 4 - 1] if count >= 20 else None, count)


def i1250_estimate(rows):
    values = []
    for row in rows:
        date = row.get("prms_dt", "")
        if not isinstance(date, str) or not re.fullmatch(r"\d{8}", date) or date < "20150101":
            continue
        value = parse_pog_daycnt(row.get("pog_daycnt"))
        if value is not None:
            values.append(value)
    return trimmed_p25(values)
