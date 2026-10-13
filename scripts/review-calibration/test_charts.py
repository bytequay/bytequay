from datetime import date, timedelta, timezone
import json
from pathlib import Path
import unittest

import activity
import charts

TABLE = json.loads((Path(__file__).with_name("fixture") / "saved-turns.json").read_text())
MODEL, TOOL, YOU = charts.STATES


def made(zone=timezone.utc):
    return charts.frames(TABLE, zone)


class TestFrames(unittest.TestCase):
    def test_every_frame_follows_the_turn_table_row_by_row(self):
        frames = made()
        self.assertEqual(frames["turns"][["task", "day", "seconds", "ended"]].values.tolist(),
                         [["aaaa-1111", date(2026, 1, 1), 600, "TURN_COMPLETED"], ["aaaa-1111", date(2026, 1, 2), 300, "INTERRUPTED"], ["bbbb-2222", date(2026, 1, 2), 120, ""]])
        # Each turn's parts add up to the turn, so no second is lost or counted twice on the way into the charts.
        self.assertEqual(frames["parts"].groupby("turn")["seconds"].sum().tolist(), frames["turns"]["seconds"].tolist())
        self.assertEqual(frames["parts"][frames["parts"]["turn"] == 0][["state", "part", "seconds"]].values.tolist(),
                         [[MODEL, "model silent", 358], [TOOL, "tests and builds", 120], [TOOL, "ByteQuay tools", 60], [YOU, "waiting for you", 60], [MODEL, "model writing", 2]])
        self.assertEqual(frames["programs"].values.tolist(), [[0, "maven", 120, 1], [1, "npm", 120, 1], [2, "git", 40, 1]])
        self.assertEqual(frames["after"][frames["after"]["turn"] == 1].values.tolist(), [[1, "at the start of a turn", 20, 1], [1, "after tests and builds", 160, 1]])

    def test_days_are_the_readers_own_days(self):
        self.assertEqual(made(timezone(timedelta(hours=8)))["turns"]["day"].tolist(), [date(2026, 1, 1), date(2026, 1, 2), date(2026, 1, 2)])
        self.assertEqual(made(timezone(timedelta(hours=-5)))["turns"]["day"].tolist(), [date(2025, 12, 31), date(2026, 1, 1), date(2026, 1, 1)])

    def test_table_saved_by_another_version_is_refused_and_not_drawn_short(self):
        newer = json.loads(json.dumps(TABLE))
        newer["turns"][0]["groups"]["a part from a later version"] = 5
        with self.assertRaises(KeyError):
            charts.frames(newer, timezone.utc)
        for missing in ("groups", "programs", "after", "start", "seconds"):
            with self.subTest(missing=missing), self.assertRaises(KeyError):
                charts.frames({"turns": [{name: value for name, value in TABLE["turns"][0].items() if name != missing}]}, timezone.utc)

    def test_within_keeps_only_the_turns_of_the_chosen_days_in_every_frame(self):
        second_day = charts.within(made(), date(2026, 1, 2), date(2026, 1, 2))
        self.assertEqual((second_day["turns"].index.tolist(), second_day["turns"]["seconds"].sum()), ([1, 2], 420))
        for name in ("parts", "programs", "after"):
            self.assertEqual(sorted(set(second_day[name]["turn"])), [1, 2], name)
        self.assertEqual(len(charts.within(made(), date(2026, 1, 1), date(2026, 1, 2))["turns"]), 3)  # both ends are included
        self.assertTrue(charts.within(made(), date(2026, 2, 1), date(2026, 2, 2))["turns"].empty)


class TestRows(unittest.TestCase):
    def test_the_three_states_are_always_all_there_in_one_order(self):
        self.assertEqual(charts.state_seconds(made()["parts"]).to_dict(), {MODEL: 600, TOOL: 360, YOU: 60})
        second_day = charts.within(made(), date(2026, 1, 2), date(2026, 1, 2))
        self.assertEqual(list(charts.state_seconds(second_day["parts"]).items()), [(MODEL, 240), (TOOL, 180), (YOU, 0)])

    def test_parts_of_a_state_come_longest_first_without_those_that_would_print_as_zero(self):
        self.assertEqual(charts.part_rows(made()["parts"], TOOL),
                         [("tests and builds", 240, None), ("ByteQuay tools", 60, None), ("other shell commands", 40, None), ("reading and searching files", 20, None)])
        self.assertEqual(charts.part_rows(made()["parts"], MODEL), [("model silent", 598, None)])  # two seconds of writing would print as 0.0 minutes
        self.assertLess(2, activity.SHOWN)

    def test_programs_past_the_top_few_are_added_up_and_a_tie_goes_by_name(self):
        programs = made()["programs"]
        self.assertEqual(charts.program_rows(programs), [("maven", 120, 1), ("npm", 120, 1), ("git", 40, 1)])
        self.assertEqual(charts.program_rows(programs, top=1), [("maven", 120, 1), ("every other program", 160, 2)])
        self.assertEqual(charts.program_rows(programs, top=3)[-1], ("git", 40, 1))  # nothing is left to add up

    def test_model_time_is_listed_by_what_just_finished_with_how_often(self):
        self.assertEqual(charts.model_rows(made()["after"]),
                         [("after you answered", 330, 1), ("after tests and builds", 170, 2), ("after other shell commands", 60, 1), ("at the start of a turn", 30, 2),
                          ("after ByteQuay tools", 10, 1)])

    def test_share_of_nothing_is_zero(self):
        self.assertEqual((charts.share(1, 4), charts.share(1, 0)), (25, 0))


class TestFigures(unittest.TestCase):
    def test_state_ring_shows_minutes_and_writes_each_share_in_its_key(self):
        ring = charts.states_donut(made()["parts"]).data[0]
        self.assertEqual(list(ring.values), [10, 6, 1])
        self.assertEqual(list(ring.labels), [f"{MODEL}  58.8%", f"{TOOL}  35.3%", f"{YOU}  5.9%"])
        self.assertEqual(list(ring.marker.colors), [charts.COLORS["light"][kind] for kind in ("model", "tool", "you")])
        self.assertEqual(charts.states_donut(made()["parts"]).layout.annotations[0].text, "0.3 h")

    def test_ending_ring_counts_turns_and_calls_an_unknown_mark_no_end_mark(self):
        turns = made()["turns"].copy()
        figure = charts.endings_donut(turns)
        self.assertEqual((list(figure.data[0].labels), list(figure.data[0].values), figure.layout.annotations[0].text),
                         (["completed  1", "interrupted  1", "no end mark  1"], [1, 1, 1], "3 turns"))
        turns["ended"] = ["TASK_FAILED", "A MARK FROM LATER", ""]
        self.assertEqual((list(charts.endings_donut(turns).data[0].labels), list(charts.endings_donut(turns).data[0].values)), (["failed  1", "no end mark  2"], [1, 2]))

    def test_sunburst_puts_each_part_under_its_state_and_never_above_it(self):
        rings = charts.sunburst(made()["parts"]).data[0]
        inner = dict(zip(rings.ids, rings.values))
        self.assertEqual(list(rings.ids[:3]), charts.STATES)
        self.assertEqual({part: parent for part, parent in zip(rings.ids, rings.parents) if parent},
                         {f"{MODEL}/model silent": MODEL, f"{TOOL}/tests and builds": TOOL, f"{TOOL}/ByteQuay tools": TOOL, f"{TOOL}/other shell commands": TOOL,
                          f"{TOOL}/reading and searching files": TOOL, f"{YOU}/waiting for you": YOU})
        for state in charts.STATES:
            self.assertLessEqual(sum(length for part, length in inner.items() if part.startswith(state + "/")), inner[state])

    def test_day_bars_stack_to_the_turn_time_of_each_day(self):
        figure = charts.days_figure(made()["turns"], made()["parts"])
        self.assertEqual([bar.name for bar in figure.data], charts.STATES)
        self.assertEqual(list(figure.data[0].x), [date(2026, 1, 1), date(2026, 1, 2)])
        hours = [sum(bar.y[day] for bar in figure.data) for day in range(2)]
        self.assertEqual([round(length * 3600) for length in hours], [600, 420])

    def test_bars_write_minutes_share_and_count_at_the_tip(self):
        bars = charts.bars_figure(charts.program_rows(made()["programs"]), 280, "#123456", "runs")
        self.assertEqual((list(bars.data[0].y), list(bars.data[0].x)), (["maven", "npm", "git"], [2, 2, 40 / 60]))
        self.assertEqual(list(bars.data[0].text), ["2.0 min · 42.9% · 1 runs", "2.0 min · 42.9% · 1 runs", "0.7 min · 14.3% · 1 runs"])
        self.assertEqual(charts.bars_figure([("x", 90000, None)], 180000, "#123456").data[0].text[0], "1,500.0 min · 50.0%")
        self.assertGreater(bars.layout.xaxis.range[1], 2)  # room at the tip for the numbers

    def test_task_bars_list_the_longest_tasks_first_by_the_start_of_their_id(self):
        figure = charts.tasks_figure(made()["turns"], made()["parts"])
        self.assertEqual((list(figure.data[0].y), list(figure.layout.yaxis.ticktext)), (["aaaa-1111", "bbbb-2222"], ["aaaa-1111", "bbbb-2222"]))
        self.assertEqual([round(sum(bar.x[row] for bar in figure.data) * 60) for row in range(2)], [900, 120])
        self.assertEqual(list(charts.tasks_figure(made()["turns"], made()["parts"], top=1).data[0].y), ["aaaa-1111"])

    def test_both_modes_have_every_color_a_chart_asks_for(self):
        wanted = {*charts.KINDS.values(), *(kind for _, kind in charts.ENDINGS.values()), "surface", "grid"}
        for mode, colors in charts.COLORS.items():
            self.assertLessEqual(wanted, set(colors), mode)
            for figure in (charts.states_donut, charts.sunburst):
                self.assertIn(colors["model"], list(figure(made()["parts"], mode).data[0].marker.colors))


if __name__ == "__main__":
    unittest.main()
