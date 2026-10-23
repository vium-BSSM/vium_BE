"""Generate deterministic catalog seed SQL and audit source rules offline."""

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


# Must match PurchaseScanService.STORAGE_ORDER.
STORAGE_ORDER = ('cold', 'room', 'frozen')
CATEGORIES = {'유제품', '채소', '과일', '육류', '수산물', '곡류', '양념', '음료', '냉동식품', '기타'}
UNITS = set('ea g kg ml l pack bag bottle can container'.split())


def foodkeeper_products(document):
    products = {}
    for sheet in document['sheets']:
        if sheet['name'] != 'Product':
            continue
        for cells in sheet['data']:
            row = {key: value for cell in cells for key, value in cell.items()}
            value = row['ID']
            ident = int(value)
            if ident != float(value) or ident <= 0 or ident in products:
                raise ValueError('FoodKeeper ID invalid or duplicated')
            products[ident] = row
    if not products:
        raise ValueError('FoodKeeper Product sheet empty')
    return products


def foodkeeper_rules(row):
    import math
    rules, warnings = {}, []
    factors = {'Days': 1, 'Weeks': 7, 'Months': 30, 'Year': 365, 'Years': 365, 'Hours': 1 / 24}
    for storage, group in [('cold', 'Refrigerate'), ('room', 'Pantry'), ('frozen', 'Freeze')]:
        prefix = 'DOP_' + group
        # Explicit non-numeric DOP instructions must not fall back to another condition.
        if not any(row.get(prefix + '_' + field) not in (None, '') for field in ('Min', 'Max', 'Metric')):
            prefix = group
            tips = str(row.get(prefix + '_tips') or row.get(prefix + '_Tips') or '')
            if re.search(r'cooked|cooking|opened|after opening|thaw|prepared', tips, re.I):
                continue
        metric = row.get(prefix + '_Metric')
        if metric == 'Not Recommended':
            warnings.append(storage)
        if metric not in factors:
            continue
        value = row.get(prefix + '_Min')
        if value is None:
            value = row.get(prefix + '_Max')
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
            continue
        days = max(1, int(value * factors[metric]))
        if days <= 3650:
            rules[storage] = (days, f"USDA FoodKeeper v128 #{int(row['ID'])}")
    return rules, warnings


def histogram_stats(histogram):
    values = []
    for day, count in histogram.items():
        if not re.fullmatch(r'[1-9][0-9]*', day) or not 1 <= int(day) <= 3650:
            raise ValueError('invalid histogram day')
        if type(count) is not int or count <= 0 or count > 1_000_000:
            raise ValueError('invalid histogram count')
        if len(values) + count > 2_000_000:
            raise ValueError('histogram too large')
        values.extend([int(day)] * count)
    values.sort()
    trim = len(values) // 20
    if trim:
        values = values[trim:-trim]
    n = len(values)
    if n < 20:
        return {'n': n, 'p25': None, 'p50': None, 'p75': None}
    return {'n': n, 'p25': values[(n + 3) // 4 - 1],
            'p50': values[(n + 1) // 2 - 1], 'p75': values[(3 * n + 3) // 4 - 1]}


def validate_inputs(ingredients, manual, products, stats, samples):
    errors, names, pairs = [], set(), set()
    if not ingredients:
        errors.append('재료 목록이 비어 있습니다')
    for row in ingredients:
        name = row['name']
        if not name.strip() or len(name) > 120 or name in names:
            errors.append(f'재료 이름 오류: {name}')
        names.add(name)
        for field, allowed in [('category', CATEGORIES), ('default_unit', UNITS), ('preferred_storage', STORAGE_ORDER)]:
            if row[field] not in allowed:
                errors.append(f'{name}: {field} 오류')
        fid = row['foodkeeper_id']
        if fid and (not fid.isascii() or not fid.isdigit() or int(fid) not in products):
            errors.append(f'{name}: FoodKeeper ID 없음')
        kind = row['mfds_type']
        if kind and (kind not in stats or row['mfds_storage'] not in STORAGE_ORDER):
            errors.append(f'{name}: I1250 통계 또는 보관방법 없음')
        if not kind and row['mfds_storage']:
            errors.append(f'{name}: mfds_type 없는 보관방법')
    for kind in sorted({r['mfds_type'] for r in ingredients if r['mfds_type']} & stats.keys()):
        try:
            entry = stats[kind]
            histogram_stats(entry['days'])
            if sum(entry['days'].values()) != entry['parsed_rows']:
                raise ValueError('parsed count mismatch')
        except (ValueError, TypeError, KeyError, AttributeError):
            errors.append(f'{kind}: I1250 통계 형식 오류')
    for row in manual:
        pair = row['name'], row['storage']
        days = row['days']
        if pair in pairs or pair[0] not in names or pair[1] not in STORAGE_ORDER:
            errors.append(f'{pair}: manual 참조·중복 오류')
        pairs.add(pair)
        if days != '-' and (not days.isascii() or not days.isdigit() or not 1 <= int(days) <= 3650):
            errors.append(f'{pair}: manual 일수 오류')
        if not row['reason'].strip():
            errors.append(f'{pair}: manual 근거 없음')
    for row in samples:
        if not row['raw_name'].strip() or (row['expected'] and row['expected'] not in names):
            errors.append(f"매칭 샘플 오류: {row['raw_name']}")
    return errors


def merge_rules(ingredients, manual, products, stats):
    rules, reports = {}, []
    summaries = {kind: histogram_stats(stats[kind]['days']) for kind in sorted({r['mfds_type'] for r in ingredients if r['mfds_type']})}
    for kind, value in summaries.items():
        if value['p25'] is None:
            reports.append(f"표본 부족: {kind} n={value['n']}")
        elif value['p75'] >= 4 * value['p25']:
            reports.append(f"분포 넓음: {kind} p25={value['p25']} p50={value['p50']} p75={value['p75']} n={value['n']}")
    for row in ingredients:
        current = {}
        if row['foodkeeper_id']:
            current, warnings = foodkeeper_rules(products[int(row['foodkeeper_id'])])
            reports.extend(f"Not Recommended: {row['name']} {storage}" for storage in warnings)
        kind = row['mfds_type']
        if kind and summaries[kind]['p25'] is not None:
            value = summaries[kind]
            suffix = f" p25 n={value['n']}"
            source = 'MFDS I1250 ' + kind[:80 - len('MFDS I1250 ') - len(suffix)] + suffix
            current[row['mfds_storage']] = (value['p25'], source)
        rules[row['name']] = current
    for row in manual:
        if row['days'] == '-':
            rules[row['name']].pop(row['storage'], None)
        else:
            rules[row['name']][row['storage']] = (int(row['days']), 'manual')
    return rules, reports


def storage_issues(ingredients, rules):
    issues = []
    for row in ingredients:
        current = rules[row['name']]
        preferred = row['preferred_storage']
        chosen = next((s for s in STORAGE_ORDER if s in current), None)
        if chosen != preferred:
            actions = []
            if preferred not in current:
                actions.append(f'{preferred} 규칙 추가 필요')
            actions.extend(f'{s} 규칙 제거 필요' for s in STORAGE_ORDER[:STORAGE_ORDER.index(preferred)] if s in current)
            issues.append(f"{row['name']}: 선호 {preferred} / 스캔 선택 {chosen or '없음'} ({', '.join(actions)})")
    return issues


def match_name(raw, names):
    # Keep aligned with PurchaseScanService.match; ties assume CSV insertion order IDs.
    normalize = lambda text: re.sub(r'\s+', '', text, flags=re.ASCII).lower()
    tokens = re.split(r'\s+', raw.strip().lower(), flags=re.ASCII)
    for name in sorted(names, key=lambda name: -len(normalize(name))):
        candidate = normalize(name)
        if candidate and (candidate in tokens if len(candidate) == 1 else candidate in normalize(raw)):
            return name
    return ''


def sql_string(value):
    return "'" + value.replace("'", "''") + "'"


def generate_sql(ingredients, rules):
    quote = sql_string
    catalog = ',\n'.join('  (' + ', '.join(quote(row[k]) for k in ('name', 'category', 'default_unit')) + ')' for row in ingredients)
    sql = '-- Generated by scripts/catalog/build_seed.py. Do not edit by hand.\n'
    sql += f'''INSERT INTO ingredient_catalog (category_id, default_unit_id, name, created_at)
SELECT c.id, u.id, v.name, CURRENT_TIMESTAMP
FROM (VALUES
{catalog}
) AS v(name, category, unit)
JOIN ingredient_categories c ON c.name = v.category
JOIN units u ON u.code = v.unit
ON CONFLICT (name) DO NOTHING;

'''
    entries = []
    for row in ingredients:
        for storage in STORAGE_ORDER:
            if storage in rules[row['name']]:
                days, source = rules[row['name']][storage]
                entries.append(f"  ({quote(row['name'])}, {quote(storage)}, {days}, {quote(source)})")
    if entries:
        sql += '''INSERT INTO expiry_estimation_rules
  (ingredient_catalog_id, storage_method_id, shelf_life_days, source, effective_from)
SELECT ic.id, sm.id, v.days, v.source, DATE '2000-01-01'
FROM (VALUES
''' + ',\n'.join(entries) + '''
) AS v(name, storage, days, source)
JOIN ingredient_catalog ic ON ic.name = v.name
JOIN storage_methods sm ON sm.code = v.storage
ON CONFLICT (ingredient_catalog_id, storage_method_id, effective_from) DO NOTHING;

'''
    # A quoted SQL string avoids dollar-quote delimiter collisions in ingredient names.
    body = "BEGIN\n  IF (SELECT COUNT(*) FROM ingredient_catalog WHERE name IN (" + ', '.join(quote(r['name']) for r in ingredients) + f")) <> {len(ingredients)} THEN\n    RAISE EXCEPTION 'ingredient_catalog seed incomplete';\n  END IF;\nEND;"
    sql += 'DO ' + quote(body) + ';\n'
    return sql


def main(argv=None, root=None):
    import argparse
    import csv
    import json
    from collections import Counter
    from pathlib import Path
    import sys
    root = Path(root) if root is not None else Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description='Build catalog seed from offline snapshots')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--output', type=Path, help='Optional draft SQL destination')
    args = parser.parse_args(argv)
    def read_csv(name, columns):
        with (root / 'data/catalog' / name).open(encoding='utf-8-sig', newline='') as stream:
            reader = csv.DictReader(stream)
            if not set(columns) <= set(reader.fieldnames or []):
                raise ValueError(f'{name}: 필수 컬럼 없음')
            rows = list(reader)
            if any(None in row or any(row.get(c) is None for c in columns) for row in rows):
                raise ValueError(f'{name}: CSV 열 수 오류')
            return rows
    try:
        ingredients = read_csv('ingredients.csv', ['name', 'category', 'default_unit', 'preferred_storage', 'foodkeeper_id', 'mfds_type', 'mfds_storage'])
        manual = read_csv('manual_rules.csv', ['name', 'storage', 'days', 'reason'])
        samples = read_csv('match_samples.csv', ['raw_name', 'expected'])
        products = foodkeeper_products(json.loads((root / 'data/raw/foodkeeper.json').read_text(encoding='utf-8')))
        stats = json.loads((root / 'data/catalog/i1250_stats.json').read_text(encoding='utf-8'))
        errors = validate_inputs(ingredients, manual, products, stats, samples)
        if errors:
            print('\n'.join(errors), file=sys.stderr)
            return 1
        rules, reports = merge_rules(ingredients, manual, products, stats)
        sql = generate_sql(ingredients, rules)
        output = args.output or root / 'src/main/resources/db/migration/V9__seed_ingredient_catalog.sql'
        changed = not output.exists() or output.read_bytes() != sql.encode('utf-8')
        if args.check:
            if changed:
                print(f'생성 결과 불일치: {output}')
        else:
            output.parent.mkdir(parents=True, exist_ok=True)
            with output.open('w', encoding='utf-8', newline='\n') as stream:
                stream.write(sql)
        counts = Counter(source.split()[0] for current in rules.values() for _, source in current.values())
        print(f'재료 {len(ingredients)}개 / 규칙 {sum(counts.values())}개 / 출처 {dict(counts)}')
        missing = [name for name, current in rules.items() if not current]
        print(f"규칙 없는 재료 {len(missing)}개: {', '.join(missing)}")
        issues = storage_issues(ingredients, rules)
        print(f'보관방법 불일치 {len(issues)}개')
        print('\n'.join(issues + reports))
        mismatches = []
        for row in samples:
            actual = match_name(row['raw_name'], [r['name'] for r in ingredients])
            if actual != row['expected']:
                mismatches.append(f"{row['raw_name']} / 기대 {row['expected'] or '없음'} / 실제 {actual or '없음'}")
        print(f'매칭 불일치 {len(mismatches)}건 (CSV 순서 기준 동률 처리)')
        print('\n'.join(mismatches))
        return int(bool(missing or issues or (args.check and changed)))
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        print(f'입력/출력 오류: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    import sys
    sys.exit(main())
