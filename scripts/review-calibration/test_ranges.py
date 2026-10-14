import unittest

import numpy
import pandas

import ranges


class TestRatio(unittest.TestCase):
    def test_value_is_the_sum_of_the_parts_over_the_sum_of_the_wholes(self):
        self.assertEqual(ranges.Ratio((1, 3), (4, 4), 100).value(), 50.0)
        self.assertIsNone(ranges.Ratio((1,), (0,)).value())

    def test_tasks_that_all_say_the_same_give_a_range_of_that_one_number(self):
        self.assertEqual(ranges.range_of(ranges.Ratio((1, 2, 3), (2, 4, 6), 100)), (50.0, 50.0))

    def test_too_few_tasks_give_no_range(self):
        self.assertIsNone(ranges.range_of(ranges.Ratio((1, 2), (2, 4))))
        self.assertIsNone(ranges.range_of(ranges.Ratio((1, 2, 5), (2, 4, 0))), "a task with no whole is not a task to draw from")

    def test_the_range_holds_the_number_and_is_the_same_every_time(self):
        made = ranges.Ratio((0, 1, 5, 2, 9, 4), (10, 10, 10, 10, 10, 10), 100)
        low, high = ranges.range_of(made)
        self.assertTrue(low < made.value() < high)
        self.assertEqual(ranges.range_of(made), (low, high))

    def test_a_ratio_is_made_from_two_series_by_task(self):
        made = ranges.ratio(pandas.Series({"b": 2.0, "a": 1.0}), pandas.Series({"a": 4.0, "c": 5.0}), 100)
        self.assertEqual(made, ranges.Ratio((1.0, 2.0, 0.0), (4.0, 0.0, 5.0), 100))


class TestMedian(unittest.TestCase):
    def test_value_is_the_middle_of_every_value(self):
        self.assertEqual(ranges.Median(((1.0, 2.0), (10.0,))).value(), 2.0)
        self.assertIsNone(ranges.Median(()).value())

    def test_whole_tasks_are_drawn_so_turns_that_are_alike_do_not_narrow_the_range(self):
        alike = ranges.Median(((1.0,) * 10, (2.0,) * 10, (30.0,) * 10))
        self.assertEqual(ranges.range_of(alike), (1.0, 30.0), "drawn turn by turn, thirty values would pin the middle near 2")

    def test_a_median_is_made_from_values_and_their_tasks_without_the_unknown_ones(self):
        self.assertEqual(ranges.median([3.0, None, float("nan"), 1.0, 2.0], ["b", "a", "a", "a", "b"]), ranges.Median(((1.0,), (3.0, 2.0))))

    def test_many_draws_of_many_turns_are_made_a_few_at_a_time_with_the_same_result(self):
        groups = tuple(tuple(numpy.arange(task, task + 50, dtype=float)) for task in range(40))
        made = ranges.Median(groups).draws(ranges.SEED)
        self.assertEqual((len(made), bool(numpy.isnan(made).any())), (ranges.DRAWS, False))
        self.assertTrue(numpy.array_equal(made, ranges.Median(groups).draws(ranges.SEED)))


class TestChange(unittest.TestCase):
    def test_a_change_far_beyond_chance_is_clear_and_says_which_way(self):
        before, since = ranges.Ratio((8, 9, 10, 9), (10,) * 4, 100), ranges.Ratio((1, 2, 1, 2), (10,) * 4, 100)
        moved = ranges.change(before, since)
        self.assertTrue(ranges.clear(moved))
        self.assertTrue(moved[1] < 0)
        self.assertEqual(ranges.change_in_words((-80.0, -60.0)), "It went down, clearly: by 60.0 to 80.0.")
        self.assertEqual(ranges.change_in_words((5.0, 9.0), 0), "It went up, clearly: by 5 to 9.")

    def test_a_change_within_chance_is_not_clear_yet(self):
        before, since = ranges.Ratio((1, 9, 2, 8), (10,) * 4, 100), ranges.Ratio((2, 8, 1, 9), (10,) * 4, 100)
        moved = ranges.change(before, since)
        self.assertFalse(ranges.clear(moved))
        self.assertEqual(ranges.change_in_words((-12.0, 3.5)), "Not clear yet: the change lies somewhere from -12.0 to +3.5.")

    def test_the_tasks_before_and_since_are_drawn_apart_and_never_paired(self):
        # Paired draws would see every task move by exactly one and call it clear; these are other tasks, so the spread of each side counts.
        moved = ranges.change(ranges.Ratio((1, 2, 3, 4), (1,) * 4), ranges.Ratio((2, 3, 4, 5), (1,) * 4))
        self.assertFalse(ranges.clear(moved), moved)

    def test_a_relative_change_is_a_ratio_of_the_two_sides_and_never_falls_below_minus_one_hundred(self):
        self.assertEqual(ranges.relative(ranges.Ratio((10, 10, 10), (1, 1, 1)), ranges.Ratio((5, 5, 5), (1, 1, 1))), (-50.0, -50.0))
        self.assertEqual(ranges.relative(ranges.Ratio((1, 2, 3), (1, 1, 1)), ranges.Ratio((0, 0, 0), (1, 1, 1))), (-100.0, -100.0))
        low, _ = ranges.relative(ranges.Ratio((0, 1, 9, 2), (1,) * 4), ranges.Ratio((0, 0, 1, 0), (1,) * 4))
        self.assertGreaterEqual(low, -100.0)
        self.assertIsNone(ranges.relative(ranges.Ratio((1,), (1,)), ranges.Ratio((1, 2, 3), (1, 1, 1))))

    def test_too_few_tasks_on_either_side_tell_nothing(self):
        many, few = ranges.Ratio((1, 2, 3), (4, 4, 4)), ranges.Ratio((1,), (4,))
        self.assertIsNone(ranges.change(many, few))
        self.assertIsNone(ranges.change(None, many))
        self.assertFalse(ranges.clear(None))
        self.assertEqual(ranges.change_in_words(None), "Too few tasks on one side to tell whether it changed.")

    def test_a_range_is_said_in_words(self):
        self.assertEqual((ranges.said((4.06, 6.94)), ranges.said((1200.4, 3000.6), 0), ranges.said(None)), ("4.1 to 6.9", "1,200 to 3,001", None))


if __name__ == "__main__":
    unittest.main()
