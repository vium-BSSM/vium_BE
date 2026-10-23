import unittest

from build_seed import i1250_estimate, parse_pog_daycnt, trimmed_p25


class DurationTests(unittest.TestCase):
    def test_unconditional_durations(self):
        cases = {"18일": 18, "14일": 14, "제조일로부터 14일간": 14,
                 "제조일로부터 2년": 730, "제조일로부터 24개월": 720,
                 "제조일로부터 24개월까지": 720, "24개월": 720,
                 "제조일로부터 6개월": 180, "90일 이내": 90, "1년까지": 365,
                 "제조일자로부터 2주": 14, "제조일부터 1달": 30,
                 "제조후 24시간": 1, "제조일로 48시간": 2, "12시간": 1}
        for raw, expected in cases.items():
            with self.subTest(raw=raw):
                self.assertEqual(expected, parse_pog_daycnt(raw))

    def test_reject_conditions_and_invalid_values(self):
        for raw in ("4-10월:24시간,11-3월:48시간,냉장시:3일", "0일", "0시간",
                    "냉장 7일", "14일(미개봉)", "10~20일", "11년", "", None):
            with self.subTest(raw=raw):
                self.assertIsNone(parse_pog_daycnt(raw))

    def test_trim_and_nearest_rank(self):
        self.assertEqual((11, 36), trimmed_p25(range(1, 41)))
        self.assertEqual((None, 19), trimmed_p25(range(1, 20)))
        self.assertEqual((None, 18), trimmed_p25(range(1, 21)))
        self.assertEqual((None, 0), trimmed_p25([]))

    def test_filters_old_and_conditional_rows(self):
        rows = [{"prms_dt": "20150101", "pog_daycnt": "14일"} for _ in range(23)]
        rows += [{"prms_dt": "20141231", "pog_daycnt": "1일"}] * 50
        rows += [{"prms_dt": "20260101", "pog_daycnt": "냉장 7일"}] * 50
        self.assertEqual((14, 21), i1250_estimate(rows))


from build_seed import (foodkeeper_products, foodkeeper_rules, histogram_stats, merge_rules,
                        storage_issues, match_name, generate_sql, validate_inputs, main)
import csv
import io
import json
from pathlib import Path
import tempfile
from contextlib import redirect_stdout, redirect_stderr


def ingredient(name='감자', **changes):
    row = dict(name=name, category='채소', default_unit='kg', preferred_storage='room',
               foodkeeper_id='297', mfds_type='', mfds_storage='')
    row.update(changes)
    return row


class SeedTests(unittest.TestCase):
    def test_foodkeeper_conditions_and_float_id(self):
        potato = {'ID': 297.0, 'DOP_Pantry_Min': 1, 'DOP_Pantry_Metric': 'Months',
                  'DOP_Refrigerate_Min': 1, 'DOP_Refrigerate_Metric': 'Weeks',
                  'Freeze_Min': 10, 'Freeze_Metric': 'Months', 'Freeze_Tips': 'cooked and mashed'}
        document = {'sheets': [{'name': 'Product', 'data': [[{k: v} for k, v in potato.items()]]}]}
        self.assertEqual(potato, foodkeeper_products(document)[297])
        rules, _ = foodkeeper_rules(potato)
        self.assertEqual({'room': 30, 'cold': 7}, {s: v[0] for s, v in rules.items()})
        milk = {'ID': 27, 'DOP_Refrigerate_Metric': 'Package use-by date',
                'Refrigerate_Min': 9, 'Refrigerate_Metric': 'Days',
                'DOP_Freeze_Min': 3, 'DOP_Freeze_Metric': 'Months'}
        self.assertEqual({'frozen': (90, 'USDA FoodKeeper v128 #27')}, foodkeeper_rules(milk)[0])
        tofu = {'ID': 168, 'DOP_Refrigerate_Min': 1, 'DOP_Refrigerate_Metric': 'Weeks',
                'DOP_Freeze_Min': 5, 'DOP_Freeze_Metric': 'Months'}
        self.assertEqual([7, 150], [v[0] for v in foodkeeper_rules(tofu)[0].values()])

    def test_fallback_max_hours_and_not_recommended(self):
        r = {'ID': 1, 'Refrigerate_Max': 12, 'Refrigerate_Metric': 'Hours',
             'DOP_Freeze_Metric': 'Not Recommended'}
        rules, warnings = foodkeeper_rules(r)
        self.assertEqual(1, rules['cold'][0])
        self.assertEqual(['frozen'], warnings)
        r['Refrigerate_tips'] = 'after cooking'
        self.assertEqual({}, foodkeeper_rules(r)[0])

    def test_merge_distribution_and_manual_priority(self):
        row = ingredient(mfds_type='두부', mfds_storage='cold')
        products = {297: {'ID': 297, 'DOP_Refrigerate_Min': 7, 'DOP_Refrigerate_Metric': 'Days'}}
        stats = {'두부': {'days': {'10': 25, '40': 25}}}
        rules, reports = merge_rules([row], [], products, stats)
        self.assertEqual(10, rules['감자']['cold'][0])
        self.assertTrue(any('분포 넓음' in line for line in reports))
        manual = [dict(name='감자', storage='cold', days='-', reason='remove'),
                  dict(name='감자', storage='room', days='30', reason='preferred')]
        rules, _ = merge_rules([row], manual, products, stats)
        self.assertEqual({'room': (30, 'manual')}, rules['감자'])
        self.assertEqual([], storage_issues([row], rules))
        self.assertIsNone(histogram_stats({'10': 19})['p25'])
        stats['두부']['days'] = {'10': 25, '39': 25}
        self.assertFalse(any('분포 넓음' in line for line in merge_rules([row], [], products, stats)[1]))

    def test_storage_mismatch(self):
        for preferred, available in [('room', 'cold'), ('frozen', 'cold'), ('cold', 'frozen')]:
            self.assertEqual(1, len(storage_issues([ingredient(preferred_storage=preferred)], {'감자': {available: (1, 'manual')}})))

    def test_matching(self):
        names = ['파', '대파', '고추', '고추장', '무']
        for raw, expected in [('파스타 500g', ''), ('대파 1단', '대파'), ('고추장 500g', '고추장'), ('무 1개', '무'), ('무가당요거트', '')]:
            self.assertEqual(expected, match_name(raw, names))

    def test_validation_accumulates_and_sql_escapes(self):
        rows = [ingredient("O'Brien"), ingredient("O'Brien", category='없는 분류')]
        errors = validate_inputs(rows, [], {297: {}}, {}, [])
        self.assertEqual(2, len(errors))
        rows = rows[:1]
        rules = {"O'Brien": {'room': (30, 'manual')}}
        sql = generate_sql(rows, rules)
        self.assertIn("O''Brien", sql)
        self.assertEqual(sql, generate_sql(rows, rules))
        self.assertIn('ON CONFLICT (name) DO NOTHING', sql)
        self.assertNotIn('INSERT INTO ingredient_catalog (id', sql)

    def test_cli_check_is_read_only_and_missing_rules_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            catalog = root / 'data/catalog'; catalog.mkdir(parents=True)
            raw = root / 'data/raw'; raw.mkdir(exist_ok=True)
            row = ingredient()
            with (catalog / 'ingredients.csv').open('w', newline='') as stream:
                writer = csv.DictWriter(stream, fieldnames=list(row));writer.writeheader();writer.writerow(row)
            (catalog / 'manual_rules.csv').write_text('name,storage,days,reason\n감자,room,30,검토\n')
            (catalog / 'match_samples.csv').write_text('raw_name,expected\n감자,감자\n')
            (catalog / 'i1250_stats.json').write_text('{}')
            (raw / 'foodkeeper.json').write_text(json.dumps({'sheets': [{'name': 'Product', 'data': [[{'ID': 297.0}]]}]}))
            output = root / 'draft.sql'
            args = ['--output', str(output)]
            with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                self.assertEqual(0, main(args, root))
                first = output.read_bytes()
                self.assertEqual(0, main(args + ['--check'], root))
                output.write_text('stale')
                self.assertEqual(1, main(args + ['--check'], root))
                self.assertEqual('stale', output.read_text())
                self.assertEqual(0, main(args, root))
                self.assertEqual(first, output.read_bytes())
                (catalog / 'manual_rules.csv').write_text('name,storage,days,reason\n')
                self.assertEqual(1, main(args, root))
                self.assertEqual(1, main(args + ['--check'], root))
