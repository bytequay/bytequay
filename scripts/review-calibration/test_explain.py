import unittest

import explain

WHOLE = "{:,.0f}".format


def by(name, **amounts):
    """The numbers of each model and effort: ten turns each, and an amount of one total."""
    return {setting: {"Turns": 10, name: amount} for setting, amount in amounts.items()}


class TestWhy(unittest.TestCase):
    def test_only_a_share_of_the_turns_that_moved_ten_points_or_more_is_named(self):
        self.assertIsNone(explain.shifted({"a": 50, "b": 50}, {"a": 59, "b": 41}))
        self.assertEqual(explain.shifted({"a": 50, "b": 50}, {"a": 60, "b": 40, "c": 0}),
                         "The turns ran with other models and efforts than in the days before: a 60% of the turns (50% before), b 40% of the turns (50% before).")

    def test_a_total_gets_a_reason_when_it_moved_a_quarter_and_is_known_on_both_sides(self):
        name = "Questions to you"
        reason = explain.total(name, by(name, a=20, b=4), by(name, a=4, b=4, c=12), {name: 24}, {name: 20}, WHOLE)
        self.assertIsNone(reason)  # 24 to 20 is less than a quarter, however much each model and effort moved
        self.assertEqual(explain.total(name, by(name, a=20, b=4), by(name, a=4, b=3, c=1), {name: 24}, {name: 8}, WHOLE),
                         "Questions to you 24 → 8: a -16 (10 → 10 turns).")  # b and c moved it by less than a tenth
        self.assertEqual(explain.total(name, by(name, a=20), {}, {name: 20}, {name: 0}, WHOLE), "Questions to you 20 → 0: a -20 (10 → 0 turns).")
        for before, now in ((5, 1), (None, 30), (30, None), (0, 30)):  # a small count, a side not known, and growth from nothing have no reason
            self.assertIsNone(explain.total(name, by(name, a=before), by(name, a=now), {name: before}, {name: now}, WHOLE))
        self.assertEqual(explain.total("Turns", by("Turns", a=10), by("Turns", a=10, b=10), {"Turns": 10}, {"Turns": 20}, WHOLE), "Turns 10 → 20: b +10.")


if __name__ == "__main__":
    unittest.main()
