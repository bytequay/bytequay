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
        wanted = {*charts.KINDS.values(), *charts.ENDINGS.values(), *charts.TOKEN_KINDS.values(), *charts.ANSWERS.values(), *charts.NOTICES.values(), *charts.FINDINGS.values(), *charts.RUN_ENDINGS.values(),
                  *charts.VERDICTS.values(), "other", "surface",
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


def calls(*rows):
    """Calls as (turn, kind, name, runs, failed, found nothing, seconds) and, for a command, the characters it printed."""
    return pandas.DataFrame([(*row, None)[:8] for row in rows], columns=["turn", "kind", "name", "runs", "failed", "noMatch", "seconds", "characters"])


# Two turns: Maven fails often and takes long, a search finds nothing a few times, git ran too seldom to draw, and two tools.
OFTEN = calls((0, "command", "maven", 6, 3, 0, 600), (1, "command", "maven", 4, 1, 0, 300), (0, "command", "rg", 20, 0, 5, 20), (0, "command", "git", 4, 4, 0, 40),
              (0, "tool", "Read", 50, 1, 0, 0), (1, "tool", "Task", 5, 1, 0, 3600))


class TestCalls(unittest.TestCase):
    def test_calls_follow_the_turn_table_row_by_row_and_every_filter(self):
        frames, found = made(), charts.call_frames(TABLE)
        self.assertEqual(list(found), ["calls", "reasons", "hops", "repeats"])
        self.assertEqual(found["calls"].drop(columns="characters").values.tolist(),
                         [[0, "command", "maven", 1, 0, 0, 120], [0, "tool", "Read", 1, 0, 0, 60], [0, "tool", "review_get_context", 1, 0, 0, 60],
                          [1, "command", "npm", 1, 1, 0, 120], [2, "command", "git", 1, 0, 0, 40], [2, "tool", "Read", 2, 1, 0, 40]])
        # A command says how much it printed. A tool call has no number, which is not a zero.
        self.assertEqual(found["calls"]["characters"].fillna(-1).tolist(), [0, -1, -1, 2000, 500, -1])
        self.assertEqual(found["reasons"].values.tolist(), [[1, "npm", "test failure", 1]])
        second_day = charts.calls_of(found, charts.within(frames, date(2026, 1, 2), date(2026, 1, 2))["turns"])
        self.assertEqual((second_day["calls"]["name"].tolist(), second_day["reasons"]["program"].tolist()), (["npm", "git", "Read"], ["npm"]))
        self.assertTrue(charts.calls_of(found, charts.within(frames, date(2026, 1, 1), date(2026, 1, 1))["turns"])["reasons"].empty)
        self.assertEqual(found["hops"].values.tolist(), [[0, "tests and builds", "ByteQuay tools", "long step", 1, 10], [0, "tests and builds", "reading and searching files", "long step", 1, 10],
                                                         [2, "other shell commands", "reading and searching files", "long step", 2, 70]])
        self.assertEqual((second_day["hops"]["turn"].tolist(), found["repeats"].empty), ([2], True))
        self.assertTrue(charts.calls_of(found, charts.only(frames, ~frames["turns"]["logged"])["turns"])["calls"].empty)  # a turn without a log has no calls

    def test_table_saved_before_calls_were_counted_is_refused_here_and_still_read_everywhere_else(self):
        earlier = {**TABLE, "turns": [{name: value for name, value in row.items() if name != "calls"} for row in TABLE["turns"]]}
        with self.assertRaises(KeyError):
            charts.call_frames(earlier)
        self.assertEqual(len(charts.frames(earlier, timezone.utc)["turns"]), 4)
        later = json.loads(json.dumps(TABLE))
        later["turns"][0]["calls"][0]["kind"] = "a kind from a later version"
        with self.assertRaises(ValueError):
            charts.call_frames(later)
        for missing in ("kind", "name", "runs", "failed", "noMatch", "seconds", "characters", "reasons"):
            short = json.loads(json.dumps(TABLE))
            del short["turns"][0]["calls"][0][missing]
            with self.subTest(missing=missing), self.assertRaises(KeyError):
                charts.call_frames(short)

    def test_every_program_and_tool_is_added_up_over_the_turns_the_most_calls_first_and_a_tie_by_name(self):
        self.assertEqual(charts.calls_table(OFTEN),
                         [{"name": "Read", "kind": "tool call", "calls": 50, "failed": 1, "failed %": 2, "found nothing": 0, "minutes": 0, "seconds a call": 0},
                          {"name": "rg", "kind": "command", "calls": 20, "failed": 0, "failed %": 0, "found nothing": 5, "minutes": 0.3, "seconds a call": 1},
                          {"name": "maven", "kind": "command", "calls": 10, "failed": 4, "failed %": 40, "found nothing": 0, "minutes": 15, "seconds a call": 90},
                          {"name": "Task", "kind": "tool call", "calls": 5, "failed": 1, "failed %": 20, "found nothing": 0, "minutes": 60, "seconds a call": 720},
                          {"name": "git", "kind": "command", "calls": 4, "failed": 4, "failed %": 100, "found nothing": 0, "minutes": 0.7, "seconds a call": 10}])
        self.assertEqual([row["name"] for row in charts.calls_table(charts.call_frames(TABLE)["calls"])], ["Read", "git", "maven", "npm", "review_get_context"])
        # As many calls each: by name, whichever is a command and whichever a tool.
        self.assertEqual([row["name"] for row in charts.calls_table(calls((0, "command", "git", 2, 0, 0, 1), (0, "tool", "Bash", 2, 0, 0, 1), (0, "command", "awk", 2, 0, 0, 1)))], ["Bash", "awk", "git"])

    def test_bubbles_are_the_names_called_often_enough_by_calls_failure_rate_and_time(self):
        self.assertEqual(charts.candidates(OFTEN)["name"].tolist(), ["Read", "rg", "maven", "Task"])  # git ran four times: one failure more or less would move its rate by 25 points
        for mode, colors in charts.COLORS.items():
            commands, tools = charts.candidates_figure(OFTEN, mode).data
            self.assertEqual(((commands.name, commands.marker.color), (tools.name, tools.marker.color)), (("command", colors["tool"]), ("tool call", colors["model"])), mode)
            self.assertEqual(commands.marker.line.color, colors["surface"], mode)  # a ring of the page around every bubble
            # A name wears the color of text, never the color of its bubble.
            self.assertEqual({(note.font.color, note.arrowcolor) for note in charts.candidates_figure(OFTEN, mode).layout.annotations}, {(colors["ink"], colors["other"])}, mode)
        self.assertEqual((list(commands.x), list(commands.y), list(tools.x), list(tools.y)), ([20, 10], [0, 40], [50, 5], [2, 20]))
        # The size is the time. A tool that took no time at all still has a bubble, and the longest sets the scale.
        self.assertEqual((list(commands.marker.size), list(tools.marker.size), commands.marker.sizemode), ([20, 900], [1, 3600], "area"))
        self.assertAlmostEqual(commands.marker.sizeref, 2 * 3600 / charts.BUBBLE ** 2)  # the longest is as wide as a bubble may be
        self.assertEqual([tuple(point) for point in commands.customdata], [("rg", 0, 20 / 60), ("maven", 4, 15)])
        figure = charts.candidates_figure(OFTEN)
        self.assertEqual((figure.layout.xaxis.type, figure.layout.hovermode), ("log", "closest"))
        self.assertTrue(figure.layout.yaxis.range[0] < 0 and figure.layout.yaxis.range[1] > 40)  # a bubble at either edge is drawn whole

    def test_only_the_names_that_failed_most_are_written_beside_their_bubbles(self):
        named = lambda top: [note.text for note in charts.candidates_figure(OFTEN, top=top).layout.annotations]
        self.assertEqual(named(8), ["Task", "maven", "Read"])  # from the fewest calls to the most; a search that only found nothing never failed, so it has no name
        self.assertEqual((named(1), named(2)), (["maven"], ["maven", "Read"]))  # Read and Task failed once each: the tie goes by name
        notes = charts.candidates_figure(OFTEN).layout.annotations
        # A name points at its own bubble: the calls on an axis of tens, and the failure rate.
        self.assertEqual([(round(10 ** note.x), note.y) for note in notes], [(5, 20), (10, 40), (50, 2)])
        # Task took the longest, so its bubble is the widest and the line stops at its edge. Read took no time and has the smallest.
        self.assertEqual([round(note.standoff, 1) for note in notes], [charts.BUBBLE / 2, charts.BUBBLE / 4, charts.SMALLEST])
        # Neighbours along the axis stand at three heights in turn, each measured from the edge of its bubble.
        self.assertEqual([round(-note.ay - note.standoff) for note in notes], list(charts.LIFTS))
        self.assertEqual([round(-note.ay - note.standoff) for note in charts.candidates_figure(pandas.concat([OFTEN.assign(name=OFTEN["name"] + " again"), OFTEN])).layout.annotations],
                         [*charts.LIFTS, *charts.LIFTS])
        self.assertEqual({trace.mode for trace in charts.candidates_figure(OFTEN).data}, {"markers"})

    def test_reasons_come_most_frequent_first_with_the_programs_each_hit_most(self):
        reasons = pandas.DataFrame([(0, "maven", "test failure", 3), (1, "maven", "test failure", 2), (0, "python", "test failure", 5), (0, "npm", "test failure", 1), (0, "gradle", "test failure", 1),
                                    (0, "rg", "missing file or path", 4), (1, "maven", "other", 4), (1, "git", "no output kept", 9)], columns=["turn", "program", "reason", "times"])
        rows = charts.reason_rows(reasons)
        # Maven and python failed five tests each: the tie goes by name. Of the two that failed once, only the first by name is among the three listed.
        self.assertEqual(rows, [("test failure", 12, "maven 5, python 5, gradle 1"), ("no output kept", 9, "git 9"), ("missing file or path", 4, "rg 4"), ("other", 4, "maven 4")])
        self.assertEqual(charts.reason_rows(reasons, top=1)[0], ("test failure", 12, "maven 5"))
        for mode, colors in charts.COLORS.items():
            bars = charts.reasons_figure(rows, mode).data[0]
            # A failure whose reason is not known is drawn quiet, however often it came.
            self.assertEqual(list(bars.marker.color), [colors["tool"], colors["usual"], colors["tool"], colors["usual"]], mode)
        self.assertEqual((list(bars.y), list(bars.x), list(bars.text), list(bars.customdata)),
                         (["test failure", "no output kept", "missing file or path", "other"], [12, 9, 4, 4], ["12 · 41%", "9 · 31%", "4 · 14%", "4 · 14%"],
                          ["maven 5, python 5, gradle 1", "git 9", "rg 4", "maven 4"]))

    def test_what_commands_printed_is_added_up_by_program_and_past_the_top_few_together(self):
        printed = calls((0, "command", "rg", 10, 0, 0, 1, 50000), (1, "command", "rg", 10, 0, 0, 1, 30000), (0, "command", "git", 4, 0, 0, 1, 2000), (0, "command", "ls", 1, 0, 0, 1, 400),
                        (0, "command", "awk", 2, 0, 0, 1, 400), (0, "tool", "Read", 99, 0, 0, 1))
        self.assertEqual(charts.printed_rows(printed), [("rg", 80000, 20), ("git", 2000, 4), ("awk", 400, 2), ("ls", 400, 1)])  # a tool call is not in it, and a tie goes by name
        self.assertEqual(charts.printed_rows(printed, top=2), [("rg", 80000, 20), ("git", 2000, 4), ("every other program", 800, 3)])
        bars = charts.printed_figure(charts.printed_rows(printed, top=2)).data[0]
        self.assertEqual((list(bars.y), list(bars.x), list(bars.text)),
                         (["rg", "git", "every other program"], [80000, 2000, 800], ["80.0 K · 97% · 4.0 K a run", "2.0 K · 2% · 500 a run", "800 · 1% · 267 a run"]))
        self.assertEqual(charts.printed_rows(charts.call_frames(TABLE)["calls"]), [("npm", 2000, 1), ("git", 500, 1), ("maven", 0, 1)])

    def test_table_saved_before_hops_and_repeats_were_counted_or_with_an_unknown_gap_is_refused(self):
        for missing in ("hops", "repeats"):
            with self.subTest(missing=missing), self.assertRaises(KeyError):
                charts.call_frames({"turns": [{name: value for name, value in row.items() if name != missing} for row in TABLE["turns"]]})
        later = json.loads(json.dumps(TABLE))
        later["turns"][0]["hops"][0]["gap"] = "a gap from a later version"
        with self.assertRaises(ValueError):
            charts.call_frames(later)

    def test_every_kind_of_gap_is_listed_in_its_order_and_drawn_with_the_short_hops_standing_out(self):
        hops = pandas.DataFrame([(0, "ByteQuay tools", "ByteQuay tools", "short hop", 3, 12.0), (1, "other shell commands", "ByteQuay tools", "short hop", 2, 42.0),
                                 (1, "ByteQuay tools", "ByteQuay tools", "short hop", 4, 18.0), (0, "tests and builds", "ByteQuay tools", "long step", 1, 120.0)],
                                columns=["turn", "from", "to", "gap", "times", "seconds"])
        self.assertEqual(charts.gap_rows(hops), [("in one answer", 0, 0), ("short hop", 9, 72), ("long step", 1, 120)])  # a kind with no call is still there, as zero
        for mode, colors in charts.COLORS.items():
            self.assertEqual(list(charts.gaps_figure(hops, 600, mode).data[0].marker.color), [colors["usual"], colors["tool"], colors["model"]], mode)
        bars = charts.gaps_figure(hops, 600).data[0]
        self.assertEqual((list(bars.y), list(bars.x), list(bars.text)), (["in one answer", "short hop", "long step"], [0, 1.2, 2],
                                                                        ["0 calls · 0.0 min · 0.0% of turn time", "9 calls · 1.2 min · 12.0% of turn time", "1 calls · 2.0 min · 20.0% of turn time"]))
        # The pairs with the most time in short hops: the two pairs of ByteQuay tools add up to more calls, and the shell to more time, which is what counts.
        self.assertEqual(charts.hop_pairs_table(hops), [{"after": "other shell commands", "the call": "ByteQuay tools", "times": 2, "minutes": 0.7},
                                                        {"after": "ByteQuay tools", "the call": "ByteQuay tools", "times": 7, "minutes": 0.5}])
        tie = pandas.DataFrame([(0, "subagents", "ByteQuay tools", "short hop", 1, 5.0), (0, "ByteQuay tools", "subagents", "short hop", 1, 5.0)], columns=hops.columns)
        self.assertEqual([row["after"] for row in charts.hop_pairs_table(tie)], ["ByteQuay tools", "subagents"])  # as much time each: by name
        self.assertEqual((len(charts.hop_pairs_table(hops, top=1)), charts.hop_pairs_table(hops, kind="long step")[0]["after"]), (1, "tests and builds"))

    def test_repeats_are_added_up_by_program_with_what_a_cache_could_have_answered_apart(self):
        repeats = pandas.DataFrame([(0, "maven", 2, 120.0, 1, 30.0), (1, "maven", 1, 60.0, 0, 0.0), (0, "git", 3, 6.0, 3, 6.0), (1, "gh", 1, 6.0, 0, 0.0)],
                                   columns=["turn", "program", "runs", "seconds", "unchangedRuns", "unchangedSeconds"])
        self.assertEqual(charts.repeat_rows(repeats), [("maven", 30, 150, 3, 1), ("gh", 0, 6, 1, 0), ("git", 6, 0, 3, 3)])  # the most seconds first, and a tie by name
        self.assertEqual([row[0] for row in charts.repeat_rows(repeats, top=1)], ["maven"])
        for mode, colors in charts.COLORS.items():
            cached, rest = charts.repeats_figure(charts.repeat_rows(repeats), mode).data
            self.assertEqual(((cached.name, cached.marker.color), (rest.name, rest.marker.color)),
                             (("nothing written in between", colors["tool"]), ("something written in between", colors["usual"])), mode)
        self.assertEqual((list(cached.x), list(rest.x), list(cached.customdata), list(rest.customdata)), ([0.5, 0, 0.1], [2.5, 0.1, 0], [1, 0, 3], [2, 1, 0]))
        self.assertEqual((list(cached.y), list(cached.text), list(rest.text)), (["maven  (3 runs)", "gh  (1 run)", "git  (3 runs)"], ["0.5 min", "", "0.1 min"], ["2.5 min", "0.1 min", ""]))

    def test_providers_are_listed_with_their_calls_and_a_kind_that_never_ran_has_no_rate(self):
        frames, found = made(), charts.call_frames(TABLE)["calls"]
        self.assertEqual(charts.providers_table(found, frames["turns"]),
                         [{"provider": "codex", "turns": 2, "commands": 2, "commands failed": 1, "commands failed %": 50, "tool calls": 2, "tool calls failed": 0, "tool calls failed %": 0},
                          {"provider": "not recorded", "turns": 1, "commands": 1, "commands failed": 0, "commands failed %": 0, "tool calls": 2, "tool calls failed": 1,
                           "tool calls failed %": 50}])
        only_commands = charts.providers_table(found[found["kind"] == "command"], frames["turns"])
        self.assertEqual([(row["provider"], row["tool calls"], row["tool calls failed %"]) for row in only_commands], [("codex", 0, None), ("not recorded", 0, None)])

    def test_grid_of_providers_has_the_most_used_programs_down_and_a_cell_with_too_few_runs_has_no_color(self):
        frames, found = made(), charts.call_frames(TABLE)["calls"]
        grid = charts.providers_figure(found, frames["turns"]).data[0]
        self.assertEqual((list(grid.x), list(grid.y)), (["codex", "not recorded"], ["git", "maven", "npm"]))
        self.assertEqual(([list(row) for row in grid.z], [list(row) for row in grid.text]), ([[None, None]] * 3, [["", "1 run"], ["1 run", ""], ["1 run", ""]]))
        with mock.patch.object(charts, "MIN_RUNS", 1):
            grid = charts.providers_figure(found, frames["turns"]).data[0]
        self.assertEqual(([list(row) for row in grid.z], [list(row) for row in grid.text]),
                         ([[None, 0], [0, None], [100, None]], [["", "0%<br>0 of 1"], ["0%<br>0 of 1", ""], ["100%<br>1 of 1", ""]]))
        self.assertEqual((grid.zmin, grid.zmax), (0, 100))
        # The program with the most runs comes first and the provider with the most commands, whatever their names. A tool call is not in the grid.
        turns = pandas.DataFrame({"provider": ["codex", "claude"]})
        busy = calls((0, "command", "maven", 10, 6, 0, 1), (1, "command", "maven", 30, 0, 0, 1), (0, "command", "git", 25, 1, 0, 1), (1, "tool", "Read", 99, 9, 0, 1))
        grid = charts.providers_figure(busy, turns).data[0]
        self.assertEqual((list(grid.x), list(grid.y), [list(row) for row in grid.z], grid.zmax), (["codex", "claude"], ["maven", "git"], [[60, 0], [4, None]], 60))
        self.assertEqual(list(charts.providers_figure(busy, turns, top=1).data[0].y), ["maven"])
        self.assertEqual([row["provider"] for row in charts.providers_table(busy, turns)], ["claude", "codex"])  # with its tool calls Claude made the most calls
        self.assertEqual([row["provider"] for row in charts.providers_table(busy[busy["kind"] == "command"], turns)], ["codex", "claude"])


REVIEWS = json.loads((Path(__file__).with_name("fixture") / "saved-reviews.json").read_text())
SCORES = json.loads((Path(__file__).with_name("fixture") / "saved-scores.json").read_text())


def reviewed():
    return charts.review_frames(REVIEWS, timezone.utc)


class TestReviews(unittest.TestCase):
    def test_runs_and_findings_follow_the_saved_reviews_and_a_finding_keeps_the_number_of_its_run(self):
        made = reviewed()
        self.assertEqual(made["runs"][["review", "repository", "day", "seconds", "state", "repaired", "turns", "setting"]].values.tolist()[:2],
                         [["review-1", "trinodb/trino", date(2026, 1, 1), 1830, "accepted", False, 2, "gpt-6-astra · max"],
                          ["review-1", "trinodb/trino", date(2026, 1, 1), 600, "failed", True, 1, "gpt-6-sol · high"]])
        self.assertTrue(pandas.isna(made["runs"]["seconds"][2]))  # still running: its length is not known, and not zero
        self.assertEqual(made["findings"].fillna("not given").values.tolist(), [[0, "resolved", "medium"], [0, "withdrawn", "not given"], [0, "open", "high"], [2, "open", "not given"]])
        self.assertEqual(charts.review_frames(REVIEWS, timezone(timedelta(hours=-5)))["runs"]["day"].tolist(), [date(2025, 12, 31)] * 3)

    def test_reviews_in_another_shape_are_refused(self):
        with self.assertRaisesRegex(ValueError, "a run that is not saved"):
            charts.review_frames({**REVIEWS, "findings": [{**REVIEWS["findings"][0], "run": 3}]}, timezone.utc)
        for missing in ("review", "start", "state", "model", "cost"):
            with self.subTest(missing=missing), self.assertRaises(KeyError):
                charts.review_frames({"runs": [{name: value for name, value in REVIEWS["runs"][0].items() if name != missing}], "findings": []}, timezone.utc)
        with self.assertRaises(KeyError):
            charts.review_frames({"runs": REVIEWS["runs"], "findings": [{"run": 0}]}, timezone.utc)

    def test_choice_keeps_the_runs_of_those_days_models_and_repositories_with_their_findings(self):
        made, day = reviewed(), date(2026, 1, 1)
        pick = lambda **choice: (lambda chosen: (chosen["runs"].index.tolist(), chosen["findings"]["run"].tolist()))(charts.reviews_chosen(made, day, day, **choice))
        self.assertEqual(pick(), ([0, 1, 2], [0, 0, 0, 2]))
        self.assertEqual(pick(settings=["opus · extra"]), ([2], [2]))
        self.assertEqual(pick(repositories=["trinodb/trino"]), ([0, 1], [0, 0, 0]))
        self.assertEqual(pick(settings=["gpt-6-sol · high"], repositories=["trinodb/trino"]), ([1], []))
        self.assertEqual(pick(settings=["opus · extra"], repositories=["trinodb/trino"]), ([], []))
        self.assertTrue(charts.reviews_chosen(made, date(2026, 1, 2), date(2026, 1, 3))["runs"].empty)

    def test_held_up_is_the_resolved_share_of_the_decided_findings_and_nothing_while_none_is_decided(self):
        findings = reviewed()["findings"]
        self.assertEqual(charts.held_up(findings), 50)  # one resolved, one withdrawn; the two open ones are not decided
        self.assertEqual(charts.held_up(findings[findings["state"] != "withdrawn"]), 100)
        self.assertIsNone(charts.held_up(findings[findings["state"] == "open"]))
        self.assertIsNone(charts.held_up(findings[:0]))

    def test_rings_count_findings_and_run_endings_in_a_fixed_order_and_keep_a_name_they_do_not_know(self):
        made = reviewed()
        findings, runs = charts.findings_donut(made["findings"]), charts.run_endings_donut(made["runs"], "dark")
        self.assertEqual((list(findings.data[0].labels), list(findings.data[0].values), findings.layout.annotations[0].text),
                         (["resolved  1", "withdrawn  1", "open  2"], [1, 1, 2], "4 findings"))
        self.assertEqual(list(findings.data[0].marker.colors), [charts.COLORS["light"][kind] for kind in ("model", "serious", "usual")])
        self.assertEqual((list(runs.data[0].labels), runs.layout.annotations[0].text), (["accepted  1", "failed  1", "running  1"], "3 runs"))
        self.assertEqual(list(runs.data[0].marker.colors), [charts.COLORS["dark"][kind] for kind in ("usual", "critical", "other")])

    def test_findings_per_run_counts_every_run_also_those_that_raised_nothing(self):
        made = reviewed()
        bars = charts.findings_per_run_figure(made["runs"], made["findings"]).data[0]
        self.assertEqual((list(bars.x), list(bars.y)), (["0", "1", "2", "3", "4", "5 or more"], [1, 1, 0, 1, 0, 0]))
        many = pandas.concat([made["findings"]] * 3, ignore_index=True)  # nine findings in the first run, three in the last
        self.assertEqual(list(charts.findings_per_run_figure(made["runs"], many).data[0].y), [1, 0, 0, 1, 0, 1])

    def test_severity_bars_count_what_became_of_the_findings_gravest_first(self):
        figure = charts.severity_figure(reviewed()["findings"])
        self.assertEqual((list(figure.data[0].y), [bar.name for bar in figure.data]), (["high", "medium", "not given"], ["resolved", "withdrawn", "open"]))
        self.assertEqual([list(bar.x) for bar in figure.data], [[0, 1, 0], [0, 0, 1], [1, 0, 1]])

    def test_table_by_model_and_effort_lists_runs_findings_and_what_held_up(self):
        made = reviewed()
        rows = charts.review_settings_table(made["runs"], made["findings"])
        self.assertEqual(rows[0], {"model and effort": "gpt-6-astra · max", "runs": 1, "asked again": 0, "typical minutes": 30.5, "findings": 3, "findings a run": 3, "resolved": 1,
                                   "withdrawn": 1, "open": 1, "held up %": 50, "typical tokens in": 4000})
        self.assertEqual([(row["model and effort"], row["asked again"], row["typical minutes"], row["findings a run"], row["held up %"]) for row in rows[1:]],
                         [("gpt-6-sol · high", 1, 10, 0, None), ("opus · extra", 0, None, 1, None)])
        lost = made["runs"].assign(setting=None)  # runs whose turns the database no longer holds
        self.assertEqual([(row["model and effort"], row["runs"], row["findings"]) for row in charts.review_settings_table(lost, made["findings"])], [("not recorded", 3, 4)])


class TestScores(unittest.TestCase):
    def test_bars_give_every_setting_the_same_length_and_write_the_counts(self):
        figure = charts.scores_figure(SCORES)
        self.assertEqual(([bar.name for bar in figure.data], list(figure.data[0].y)), (["found", "partly", "missed", "judge errors"], ["before  (2 comments)", "today  (5 comments)"]))
        self.assertEqual([[round(part) for part in bar.x] for bar in figure.data], [[50, 60], [0, 20], [50, 0], [0, 20]])
        self.assertEqual([list(bar.text) for bar in figure.data], [["1 found", "3 found"], ["", "1 partly"], ["1 missed", ""], ["", "1 judge errors"]])
        for row in range(2):
            self.assertAlmostEqual(sum(bar.x[row] for bar in figure.data), 100)

    def test_judge_errors_have_a_part_of_their_own_only_when_there_are_some(self):
        clean = {**SCORES, "settings": [SCORES["settings"][0]]}
        self.assertEqual((charts.verdicts(SCORES), charts.verdicts(clean)), (["found", "partly", "missed", "errors"], ["found", "partly", "missed"]))
        self.assertNotIn("judge errors", charts.score_settings_table(clean)[0])
        self.assertEqual([bar.name for bar in charts.scores_figure(clean).data], ["found", "partly", "missed"])

    def test_tables_add_up_each_setting_and_list_each_scored_run(self):
        settings = charts.score_settings_table(SCORES)
        self.assertEqual(settings[1], {"setting": "today", "rules": "aaaaaaaa reviewer-model high, bbbbbbbb reviewer-model high", "scored runs": "2 of 3", "comments": 5, "found": 3,
                                       "partly": 1, "missed": 0, "judge errors": 1, "leads": 6, "extra leads": 2, "tokens in": 2000, "cached": 800, "tokens out": 100, "minutes": 3,
                                       "recorded cost": 1.5, "judge tokens in": 1600, "judge tokens out": 120})
        self.assertIsNone(settings[0]["recorded cost"])  # no run of this setting records a cost: not known, and not zero
        self.assertEqual([(row["setting"], row["run"], row["case"], row["comments"], row["minutes"], row["recorded cost"]) for row in charts.score_runs_table(SCORES)],
                         [("before", "pr1_rep1", "o/n#1", 2, 1.5, None), ("today", "pr1_rep1", "o/n#1", 3, 1.5, 1.5), ("today", "pr1_rep2", "o/n#1", 2, 1.5, None)])

    def test_two_settings_are_compared_on_the_comments_both_were_judged_on(self):
        self.assertEqual(charts.comment_verdicts(SCORES["settings"][1]), {("o/n#1", "0"): ["found", "found"], ("o/n#1", "1"): ["partly", "found"]})
        rows, counts = charts.compared(SCORES, "before", "today")
        self.assertEqual(rows, [{"case": "o/n#1", "comment": "0", "first": "found", "second": "found, found", "better": "neither"},
                                {"case": "o/n#1", "comment": "1", "first": "missed", "second": "partly, found", "better": "today"}])
        self.assertEqual(counts, (0, 1, 1))
        self.assertEqual(charts.compared(SCORES, "today", "before")[1], (1, 0, 1))
        self.assertEqual(charts.compared(SCORES, "today", "today")[1], (0, 0, 2))  # a setting is never better than itself
        # A part-find is half a find: one found and one missed are level with two part-finds, and below a find and a part-find.
        run = lambda verdicts: {**SCORES["settings"][0]["runs"][0], "verdicts": verdicts}
        mixed = {**SCORES, "settings": [{"name": "a", "rules": [], "notScored": [], "runs": [run({"1": "found", "2": "found"}), run({"1": "missed", "2": "partly", "3": "found"})]},
                                        {"name": "b", "rules": [], "notScored": [], "runs": [run({"1": "partly", "2": "partly"}), run({"1": "partly", "2": "partly", "4": "found"})]}]}
        rows, counts = charts.compared(mixed, "a", "b")
        self.assertEqual(([(row["comment"], row["better"]) for row in rows], counts), ([("1", "neither"), ("2", "a")], (1, 0, 1)))  # comments 3 and 4 were judged for one of them only
        with self.assertRaises(KeyError):
            charts.compared(SCORES, "before", "a setting that is not there")

    def test_gap_between_two_settings_is_weighed_against_chance(self):
        self.assertEqual([round(charts.by_chance(wins, losses), 4) for wins, losses in [(0, 0), (1, 0), (0, 1), (5, 5), (6, 1), (1, 6), (10, 0), (9, 1)]],
                         [1.0, 1.0, 1.0, 1.0, 0.125, 0.125, 0.002, 0.0215])
        self.assertEqual(charts.compared_in_words("a", "b", (0, 0, 0)), "These two settings were judged on no comment in common.")
        self.assertEqual(charts.compared_in_words("a", "b", (6, 1, 3)), "On 10 comments: a did better on 6, b on 1, and on 3 they did the same. "
                         "Chance alone gives a split this uneven about 12% of the time, so this does not show that one is better.")
        self.assertEqual(charts.compared_in_words("a", "b", (9, 1, 0)), "On 10 comments: a did better on 9, b on 1, and on 0 they did the same. "
                         "Chance alone gives a split this uneven only about 2% of the time.")
        self.assertEqual(charts.compared_in_words("a", "b", (0, 12, 1)), "On 13 comments: a did better on 0, b on 12, and on 1 they did the same. "
                         "Chance alone gives a split this uneven only less than 1% of the time.")

    def test_notes_name_mixed_settings_and_runs_that_were_not_scored(self):
        self.assertEqual(charts.score_notes(SCORES), [
            "before: not scored, and not counted as zero: pr1_rep2 (timed_out)",
            "today: mixed rules files, models or efforts in this setting, so its runs are not one comparison: aaaaaaaa reviewer-model high, bbbbbbbb reviewer-model high",
            "today: not scored, and not counted as zero: pr2_rep1 (not judged)"])
        self.assertEqual(charts.score_notes({"settings": [{**SCORES["settings"][0], "notScored": []}]}), [])

    def test_setting_with_no_scored_run_has_no_bar_and_is_never_drawn_as_zero(self):
        unscored = {**SCORES, "settings": [SCORES["settings"][0], {"name": "broken", "rules": ["cccccccc reviewer-model"], "runs": [], "notScored": [{"run": "r", "status": "failed"}]}]}
        self.assertEqual(list(charts.scores_figure(unscored).data[0].y), ["before  (2 comments)"])
        self.assertEqual(charts.score_settings_table(unscored)[1]["scored runs"], "0 of 1")


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
