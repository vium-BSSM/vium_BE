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
