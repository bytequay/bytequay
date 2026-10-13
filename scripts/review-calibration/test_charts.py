from datetime import date, timedelta, timezone
import json
from pathlib import Path
import unittest
from unittest import mock

import pandas

import activity
import charts

TABLE = json.loads((Path(__file__).with_name("fixture") / "saved-turns.json").read_text())
MODEL, TOOL, YOU = charts.STATES


def made(zone=timezone.utc):
    return charts.frames(TABLE, zone)


class TestFrames(unittest.TestCase):
    def test_every_frame_follows_the_turn_table_row_by_row(self):
        frames = made()
        self.assertEqual(frames["turns"][["task", "day", "seconds", "logged", "outcome"]].values.tolist(),
                         [["aaaa-1111", date(2026, 1, 1), 600, True, "completed"], ["aaaa-1111", date(2026, 1, 2), 300, True, "cancelled"],
                          ["bbbb-2222", date(2026, 1, 2), 120, True, "no end mark"], ["cccc-3333", date(2026, 1, 2), 240, False, "completed"]])
        self.assertEqual(frames["turns"]["setting"].tolist()[:2] + frames["turns"]["setting"].tolist()[3:], ["gpt-6-astra · max", "gpt-6-astra · medium", "opus · high"])
        self.assertTrue(pandas.isna(frames["turns"]["setting"][2]) and pandas.isna(frames["turns"]["inputTokens"][2]))  # not in the database: nothing is made up
        self.assertEqual(frames["turns"][["provider", "repository", "inputTokens", "cachedInputTokens", "outputTokens", "cost", "toolCalls", "compactions"]].values.tolist()[3],
                         ["claude", "bytequay/bytequay", 50000, 40000, 2000, 1.5, 12, 1])
        # Each logged turn's parts add up to the turn, so no second is lost or counted twice on the way into the charts.
        self.assertEqual(frames["parts"].groupby("turn")["seconds"].sum().tolist(), frames["turns"]["seconds"].tolist()[:3])
        self.assertEqual(frames["parts"][frames["parts"]["turn"] == 0][["state", "part", "seconds"]].values.tolist(),
                         [[MODEL, "model silent", 358], [TOOL, "tests and builds", 120], [TOOL, "ByteQuay tools", 60], [YOU, "waiting for you", 60], [MODEL, "model writing", 2]])
        self.assertEqual(frames["programs"].values.tolist(), [[0, "maven", 120, 1], [1, "npm", 120, 1], [2, "git", 40, 1]])
        self.assertEqual(frames["after"][frames["after"]["turn"] == 1].values.tolist(), [[1, "at the start of a turn", 20, 1], [1, "after tests and builds", 160, 1]])

    def test_days_are_the_readers_own_days(self):
        self.assertEqual(made(timezone(timedelta(hours=8)))["turns"]["day"].tolist(), [date(2026, 1, 1), date(2026, 1, 2), date(2026, 1, 2), date(2026, 1, 2)])
        self.assertEqual(made(timezone(timedelta(hours=-5)))["turns"]["day"].tolist(), [date(2025, 12, 31), date(2026, 1, 1), date(2026, 1, 1), date(2026, 1, 1)])

    def test_table_saved_by_another_version_is_refused_and_not_drawn_short(self):
        newer = json.loads(json.dumps(TABLE))
        newer["turns"][0]["groups"]["a part from a later version"] = 5
        with self.assertRaises(KeyError):
            charts.frames(newer, timezone.utc)
        for missing in ("groups", "programs", "after", "start", "seconds", "logged", "outcome", "model", "effort", "cost"):
            with self.subTest(missing=missing), self.assertRaises(KeyError):
                charts.frames({"turns": [{name: value for name, value in TABLE["turns"][0].items() if name != missing}]}, timezone.utc)

    def test_within_keeps_only_the_turns_of_the_chosen_days_in_every_frame(self):
        second_day = charts.within(made(), date(2026, 1, 2), date(2026, 1, 2))
        self.assertEqual((second_day["turns"].index.tolist(), second_day["turns"]["seconds"].sum()), ([1, 2, 3], 660))
        for name in ("parts", "programs", "after"):
            self.assertEqual(sorted(set(second_day[name]["turn"])), [1, 2], name)  # the turn without a log has no time split
        self.assertEqual(len(charts.within(made(), date(2026, 1, 1), date(2026, 1, 2))["turns"]), 4)  # both ends are included
        logged = charts.only(made(), made()["turns"]["logged"])
        self.assertEqual((logged["turns"].index.tolist(), len(logged["parts"]), len(made()["parts"])), ([0, 1, 2], 10, 10))
        one = charts.only(made(), made()["turns"]["setting"] == "gpt-6-astra · medium")
        self.assertEqual((one["turns"].index.tolist(), sorted(set(one["parts"]["turn"])), one["programs"]["program"].tolist()), ([1], [1], ["npm"]))
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

    def test_ending_ring_counts_turns_in_a_fixed_order_and_keeps_the_name_of_an_ending_it_does_not_know(self):
        turns = made()["turns"].copy()
        figure = charts.endings_donut(turns)
        self.assertEqual((list(figure.data[0].labels), list(figure.data[0].values), figure.layout.annotations[0].text),
                         (["completed  2", "cancelled  1", "no end mark  1"], [2, 1, 1], "4 turns"))
        turns["outcome"] = ["failed", "queued", "interrupted", "failed"]
        ring = charts.endings_donut(turns, "dark").data[0]
        self.assertEqual((list(ring.labels), list(ring.values)), (["interrupted  1", "failed  2", "queued  1"], [1, 2, 1]))
        self.assertEqual(list(ring.marker.colors), [charts.COLORS["dark"][kind] for kind in ("serious", "critical", "other")])

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
        wanted = {*charts.KINDS.values(), *charts.ENDINGS.values(), *charts.TOKEN_KINDS.values(), *charts.ANSWERS.values(), *charts.NOTICES.values(), "other", "surface",
                  "grid", "scale", "ink", "lighter"}
        for mode, colors in charts.COLORS.items():
            self.assertLessEqual(wanted, set(colors), mode)
            for figure in (charts.states_donut, charts.sunburst):
                self.assertIn(colors["model"], list(figure(made()["parts"], mode).data[0].marker.colors))


class TestModels(unittest.TestCase):
    """The example has one turn for each model and effort, so these tests draw from one turn on."""

    def setUp(self):
        few = mock.patch.object(charts, "MIN_TURNS", 1)
        few.start()
        self.addCleanup(few.stop)

    def test_table_has_one_row_for_every_model_and_effort_the_database_knows(self):
        rows = charts.settings_table(made()["turns"])
        self.assertEqual(rows[0], {"model and effort": "gpt-6-astra · max", "turns": 1, "tasks": 1, "hours": 0.2, "typical minutes": 10,
                                   "typical tokens in": 900000, "cached %": 94, "typical tokens out": 4000, "typical tool calls": 30, "completed %": 100})
        self.assertEqual([(row["model and effort"], row["completed %"], row["cached %"]) for row in rows[1:]], [("gpt-6-astra · medium", 0, 75), ("opus · high", 100, 80)])
        twice = pandas.concat([made()["turns"], made()["turns"][made()["turns"]["setting"] == "opus · high"]], ignore_index=True)
        self.assertEqual([(row["model and effort"], row["turns"]) for row in charts.settings_table(twice)][0], ("opus · high", 2))  # the most turns come first
        opus = made()["turns"][made()["turns"]["setting"] == "opus · high"]
        three = pandas.concat([opus.assign(seconds=seconds, inputTokens=tokens) for seconds, tokens in ((60, 10), (120, 20), (6000, 3000))], ignore_index=True)
        (row,) = charts.settings_table(three)
        self.assertEqual((row["turns"], row["typical minutes"], row["typical tokens in"]), (3, 2, 20))  # the middle turn, which one very long turn does not move

    def test_model_and_effort_with_too_few_turns_is_left_out_of_the_charts_and_kept_in_the_table(self):
        turns = made()["turns"]
        self.assertEqual(charts.settings(turns), [("gpt-6-astra · max", 1), ("gpt-6-astra · medium", 1), ("opus · high", 1)])
        self.assertEqual(charts.settings(turns, logged=True), [("gpt-6-astra · max", 1), ("gpt-6-astra · medium", 1)])  # the opus turn has no log
        with mock.patch.object(charts, "MIN_TURNS", 2):
            self.assertEqual((charts.settings(turns), len(charts.settings_table(turns))), ([], 3))
            self.assertEqual([[amount for amount in row] for row in charts.heat_figure(turns).data[0].z], [[None, None, None], [None, None, None]])
            self.assertEqual(charts.heat_figure(turns).data[0].text[0][0], "1 turn")  # the cell says how few and has no color

    def test_heat_map_has_models_down_efforts_across_in_their_order_and_the_typical_turn_in_each_cell(self):
        heat = charts.heat_figure(made()["turns"]).data[0]
        self.assertEqual((list(heat.y), list(heat.x)), (["gpt-6-astra", "opus"], ["medium", "high", "max"]))
        self.assertEqual(([list(row) for row in heat.z], [list(row) for row in heat.text]),
                         ([[5, None, 10], [None, 4, None]], [["5.0 min<br>1 turn", "", "10.0 min<br>1 turn"], ["", "4.0 min<br>1 turn", ""]]))

    def test_time_split_compares_shares_and_names_each_bar_with_its_turns(self):
        figure = charts.split_figure(made()["turns"], made()["parts"])
        self.assertEqual(list(figure.data[0].y), ["gpt-6-astra · max  (1 turn)", "gpt-6-astra · medium  (1 turn)"])
        self.assertEqual([[round(share, 1) for share in bar.x] for bar in figure.data], [[60, 60], [30, 40], [10, 0]])
        for row in range(2):
            self.assertAlmostEqual(sum(bar.x[row] for bar in figure.data), 100)

    def test_turn_length_boxes_hold_every_turn_of_a_model_and_effort_in_minutes(self):
        twice = pandas.concat([made()["turns"], made()["turns"].assign(seconds=1200)], ignore_index=True)
        figure = charts.lengths_figure(twice)
        self.assertEqual([(box.name, list(box.x)) for box in figure.data],
                         [("gpt-6-astra · max  (2 turns)", [10, 20]), ("gpt-6-astra · medium  (2 turns)", [5, 20]), ("opus · high  (2 turns)", [4, 20])])
        self.assertEqual(figure.layout.xaxis.type, "log")

    def test_ending_bars_compare_shares_and_write_the_number_of_turns(self):
        twice = pandas.concat([made()["turns"], made()["turns"].assign(outcome="failed")], ignore_index=True)
        figure = charts.outcomes_figure(twice)
        self.assertEqual([bar.name for bar in figure.data], ["completed", "cancelled", "failed"])
        self.assertEqual(list(figure.data[0].y), ["gpt-6-astra · max  (2 turns)", "gpt-6-astra · medium  (2 turns)", "opus · high  (2 turns)"])
        self.assertEqual([list(bar.x) for bar in figure.data], [[50, 0, 50], [0, 50, 0], [50, 50, 50]])
        self.assertEqual([list(bar.text) for bar in figure.data], [["1", "", "1"], ["", "1", ""], ["1", "1", "1"]])


class TestSlowSpots(unittest.TestCase):
    def test_long_commands_waits_and_notices_follow_the_turn_table_and_every_filter(self):
        frames = made()
        self.assertEqual(frames["runs"].values.tolist(), [[0, "maven", 120], [1, "npm", 120], [2, "git", 40]])
        self.assertEqual(frames["waits"].values.tolist(), [[0, "other", "allowed", 60]])
        self.assertEqual(frames["notices"].values.tolist(), [[1, "rate limit: allowed warning", 2], [1, "rate limit: rejected", 1], [1, "retry", 1]])
        second_day = charts.within(frames, date(2026, 1, 2), date(2026, 1, 2))
        self.assertEqual((second_day["runs"]["program"].tolist(), len(second_day["waits"]), len(second_day["notices"])), (["npm", "git"], 0, 3))

    def test_long_commands_are_added_up_by_program_and_listed_longest_first_with_task_and_day(self):
        frames = made()
        self.assertEqual(charts.long_rows(frames["runs"]), [("maven", 120, 1), ("npm", 120, 1), ("git", 40, 1)])
        twice = pandas.concat([frames["runs"], frames["runs"]], ignore_index=True)
        self.assertEqual(charts.long_rows(twice, top=1), [("maven", 240, 2), ("every other program", 320, 4)])
        self.assertEqual(charts.longest_runs(frames["runs"], frames["turns"]),
                         [{"program": "maven", "minutes": 2, "task": "aaaa-1111", "day": "2026-01-01"}, {"program": "npm", "minutes": 2, "task": "aaaa-1111", "day": "2026-01-02"},
                          {"program": "git", "minutes": 0.7, "task": "bbbb-2222", "day": "2026-01-02"}])
        self.assertEqual([run["program"] for run in charts.longest_runs(frames["runs"], frames["turns"], top=1)], ["maven"])

    def test_wait_ring_counts_how_waits_ended_in_a_fixed_order_and_keeps_the_name_of_an_ending_it_does_not_know(self):
        waits = pandas.DataFrame([(0, "command", outcome, 10.0) for outcome in ("expired", "allowed", "paused", "allowed", "cancelled", "denied")], columns=["turn", "kind", "outcome", "seconds"])
        figure = charts.waits_donut(waits, "dark")
        ring = figure.data[0]
        self.assertEqual((list(ring.labels), list(ring.values)), (["allowed  2", "denied  1", "cancelled  1", "expired  1", "paused  1"], [2, 1, 1, 1, 1]))
        self.assertEqual((list(ring.marker.colors), figure.layout.annotations[0].text),
                         ([charts.COLORS["dark"][kind] for kind in ("usual", "serious", "warning", "critical", "other")], "6 asked"))

    def test_wait_bars_put_each_wait_in_one_band_of_time(self):
        waits = pandas.DataFrame([(0, "command", "allowed", float(length)) for length in (0, 29.9, 30, 59, 60, 119, 120, 299, 300, 4000)], columns=["turn", "kind", "outcome", "seconds"])
        bars = charts.waits_figure(waits).data[0]
        self.assertEqual((list(bars.x), list(bars.y)), (["under 30 s", "30 s to 1 min", "1 to 2 min", "2 to 5 min", "5 min or more"], [2, 2, 2, 2, 2]))
        self.assertEqual(list(charts.waits_figure(made()["waits"]).data[0].y), [0, 0, 1, 0, 0])  # one wait of exactly a minute

    def test_notice_bars_count_each_kind_of_notice_for_each_day(self):
        frames = made()
        figure = charts.notices_figure(frames["notices"], frames["turns"])
        self.assertEqual([(bar.name, list(bar.x), list(bar.y)) for bar in figure.data],
                         [("rate limit: allowed warning", [date(2026, 1, 2)], [2]), ("rate limit: rejected", [date(2026, 1, 2)], [1]), ("retry", [date(2026, 1, 2)], [1])])
        more = pandas.concat([frames["notices"], pandas.DataFrame([(0, "rate limit: other", 4), (0, "retry", 2)], columns=["turn", "what", "times"])], ignore_index=True)
        figure = charts.notices_figure(more, frames["turns"])
        self.assertEqual([(bar.name, list(bar.y)) for bar in figure.data],
                         [("rate limit: allowed warning", [0, 2]), ("rate limit: rejected", [0, 1]), ("retry", [2, 1]), ("rate limit: other", [4, 0])])

    def test_repository_bars_cut_each_repositorys_hours_into_the_three_states(self):
        frames = made()
        figure = charts.repositories_figure(frames["turns"], frames["parts"])
        # The task the database does not know has no repository and is named as such, never dropped.
        self.assertEqual((list(figure.data[0].y), [bar.name for bar in figure.data]), (["trinodb/trino", "not recorded"], charts.STATES))
        self.assertEqual([[round(hours * 3600) for hours in bar.x] for bar in figure.data], [[540, 60], [300, 60], [60, 0]])


PRICES = {"version": 1, "dollarsPerMillionTokens": {"gpt-6-astra": {"input": 2, "cachedInput": 0.2, "output": 10}, "opus": {"input": 15, "cachedInput": 1.5, "output": 75},
                                                    "not priced yet": {"input": 0, "cachedInput": 0, "output": 0}}}


class TestCost(unittest.TestCase):
    def test_tokens_are_split_into_fresh_input_cached_input_and_output_for_turns_that_have_a_count(self):
        kinds = charts.tokens(made()["turns"])
        self.assertEqual((kinds.index.tolist(), kinds.values.tolist()), ([0, 1, 3], [[50000, 850000, 4000], [50000, 150000, 1000], [10000, 40000, 2000]]))
        self.assertEqual(list(kinds.columns), list(charts.TOKEN_KINDS))

    def test_token_numbers_are_written_in_the_largest_unit_that_fits(self):
        self.assertEqual([charts.short(amount) for amount in (0, 950, 7000, 1_150_000, 2_500_000_000)], ["0", "950", "7.0 K", "1.1 M", "2.5 B"])

    def test_token_ring_shows_millions_by_kind_with_the_share_in_its_key(self):
        figure = charts.tokens_donut(made()["turns"])
        ring = figure.data[0]
        self.assertEqual(([round(value, 3) for value in ring.values], list(ring.labels)), ([0.11, 1.04, 0.007], ["fresh input  9.5%", "cached input  89.9%", "output  0.6%"]))
        self.assertEqual((figure.layout.annotations[0].text, list(ring.marker.colors)), ("1.2 M", [charts.COLORS["light"][kind] for kind in ("model", "usual", "tool")]))

    def test_token_bars_by_day_by_model_and_by_task_add_up_to_the_same_tokens(self):
        turns = made()["turns"]
        total = lambda figure, across: round(sum(sum(bar.x if across else bar.y) for bar in figure.data), 6)
        days, settings, tasks = charts.tokens_days_figure(turns), charts.tokens_settings_figure(turns), charts.tokens_tasks_figure(turns)
        self.assertEqual((total(days, False), total(settings, True), total(tasks, True)), (1.157, 1.157, 1.157))
        self.assertEqual(([bar.name for bar in days.data], list(days.data[0].x)), (list(charts.TOKEN_KINDS), [date(2026, 1, 1), date(2026, 1, 2)]))
        # The most tokens first, each bar named with its turns.
        self.assertEqual(list(settings.data[0].y), ["gpt-6-astra · max  (1 turn)", "gpt-6-astra · medium  (1 turn)", "opus · high  (1 turn)"])
        self.assertEqual((list(tasks.data[0].y), list(tasks.layout.yaxis.ticktext)), (["aaaa-1111", "cccc-3333"], ["aaaa-1111", "cccc-3333"]))
        self.assertEqual((list(charts.tokens_settings_figure(turns, top=1).data[0].y), list(charts.tokens_tasks_figure(turns, top=1).data[0].y)),
                         (["gpt-6-astra · max  (1 turn)"], ["aaaa-1111"]))

    def test_price_table_keeps_models_with_a_price_and_refuses_another_shape(self):
        self.assertEqual(charts.price_table(None), {})
        self.assertEqual(charts.price_table(PRICES), {"gpt-6-astra": (2, 0.2, 10), "opus": (15, 1.5, 75)})  # all zeros is a price not filled in yet
        for wrong, error in (({"version": 1}, KeyError), ({"dollarsPerMillionTokens": {"m": {"input": 1, "output": 1}}}, KeyError),
                             ({"dollarsPerMillionTokens": {"m": {"input": "cheap", "cachedInput": 0, "output": 0}}}, ValueError),
                             ({"dollarsPerMillionTokens": {"m": {"input": -1, "cachedInput": 0, "output": 0}}}, ValueError), ({"dollarsPerMillionTokens": [1]}, AttributeError)):
            with self.subTest(wrong=wrong), self.assertRaises(error):
                charts.price_table(wrong)

    def test_recorded_cost_wins_and_the_rest_is_estimated_only_from_a_price_and_a_token_count(self):
        turns = made()["turns"]
        costed = charts.dollars(turns, charts.price_table(PRICES))
        # 50,000 fresh at $2, 850,000 cached at $0.20 and 4,000 out at $10 a million is 31 cents. The opus turn has a recorded cost, so its price is not used.
        self.assertEqual(([round(amount, 6) for amount in costed["dollars"].tolist()[:2]], costed["dollars"].tolist()[3], costed["how"].tolist()),
                         ([0.31, 0.14], 1.5, ["estimated", "estimated", "unknown", "recorded"]))
        self.assertTrue(pandas.isna(costed["dollars"][2]))
        nothing = charts.dollars(turns, {})
        self.assertEqual((nothing["how"].tolist(), charts.unpriced(nothing), charts.unpriced(costed)), (["unknown", "unknown", "unknown", "recorded"], ["gpt-6-astra"], []))
        no_count = charts.dollars(turns.assign(inputTokens=None, cost=None), charts.price_table(PRICES))
        self.assertEqual((no_count["how"].tolist(), charts.unpriced(no_count)), (["unknown"] * 4, []))  # a price cannot stand in for tokens that are not known

    def test_cost_table_lists_tokens_for_every_model_and_dollars_only_where_they_are_known(self):
        turns = made()["turns"]
        rows = charts.cost_table(charts.dollars(turns, {}))
        self.assertEqual(rows[0], {"model and effort": "opus · high", "turns": 1, "tokens in, M": 0.1, "cached %": 80, "tokens out, M": 0, "dollars": 1.5,
                                   "turns with a cost": 1, "how": "recorded", "typical turn, $": 1.5, "completed turn, $": 1.5})
        self.assertEqual([(row["model and effort"], row["tokens in, M"], row["cached %"], row["dollars"], row["turns with a cost"], row["how"]) for row in rows[1:]],
                         [("gpt-6-astra · max", 0.9, 94, None, 0, "unknown"), ("gpt-6-astra · medium", 0.2, 75, None, 0, "unknown")])
        mixed = pandas.concat([turns, turns[turns["setting"] == "opus · high"].assign(cost=None)], ignore_index=True)
        (opus,) = [row for row in charts.cost_table(charts.dollars(mixed, charts.price_table(PRICES))) if row["model and effort"] == "opus · high"]
        # One opus turn has a recorded cost; the other is estimated: 10,000 at $15, 40,000 at $1.50 and 2,000 at $75 a million is 36 cents.
        self.assertEqual((opus["turns"], opus["dollars"], opus["turns with a cost"], opus["how"], opus["typical turn, $"]), (2, 1.86, 2, "estimated, recorded", 0.93))
        # A turn that did not complete was paid for too: its dollars count, and it does not count as a finished turn.
        failed = pandas.concat([turns, turns[turns["setting"] == "opus · high"].assign(outcome="failed", cost=0.5)], ignore_index=True)
        (opus,) = [row for row in charts.cost_table(charts.dollars(failed, {})) if row["model and effort"] == "opus · high"]
        self.assertEqual((opus["dollars"], opus["completed turn, $"]), (2, 2))
        none_done = charts.cost_table(charts.dollars(turns.assign(outcome="failed"), {}))
        self.assertEqual([row["completed turn, $"] for row in none_done], [None, None, None])
        # A completed turn whose cost is not known is no turn to divide the known dollars by.
        unknown = pandas.concat([turns[turns["setting"] == "opus · high"].assign(outcome="failed"), turns[turns["setting"] == "opus · high"].assign(cost=None)], ignore_index=True)
        (opus,) = charts.cost_table(charts.dollars(unknown, {}))
        self.assertEqual((opus["turns"], opus["turns with a cost"], opus["dollars"], opus["completed turn, $"]), (2, 1, 1.5, None))

    def test_dollar_bars_stack_what_was_estimated_on_what_was_recorded(self):
        turns = made()["turns"]
        only_recorded = charts.dollars_days_figure(charts.dollars(turns, {}))
        self.assertEqual([(bar.name, list(bar.x), list(bar.y)) for bar in only_recorded.data], [("recorded", [date(2026, 1, 2)], [1.5])])
        both = charts.dollars_days_figure(charts.dollars(turns, charts.price_table(PRICES)), "dark")
        self.assertEqual([(bar.name, list(bar.x), [round(amount, 6) for amount in bar.y]) for bar in both.data],
                         [("recorded", [date(2026, 1, 1), date(2026, 1, 2)], [0, 1.5]), ("estimated", [date(2026, 1, 1), date(2026, 1, 2)], [0.31, 0.14])])
        self.assertEqual([bar.marker.color for bar in both.data], [charts.COLORS["dark"]["model"], charts.COLORS["dark"]["lighter"]])


if __name__ == "__main__":
    unittest.main()
