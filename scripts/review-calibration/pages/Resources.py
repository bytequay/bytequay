"""The lab's Resources page: what ByteQuay takes from this Mac, live and over time, from the samples monitor.py keeps."""

import argparse
from datetime import date, datetime
from pathlib import Path
import shutil
import time

import pandas
import streamlit as st

import activity
import advisor
import batch
import cleanup
import dashboard
import digest
import monitor
import record
import resources
import saved
import suggest

GUIDE = "Start resource monitoring on App health, or use Start the monitor on Processes."
RISK = {"high": ("High risk", ":material/error:", "red"), "medium": ("Medium risk", ":material/warning:", "orange"), "low": ("Low risk", ":material/info:", "blue")}


def plain(text):
    """Text the agent wrote, shown as it is: a dollar sign would otherwise start a formula."""
    return text.replace("$", "\\$")


@st.fragment(run_every=2)
def looking(folder):
    """How a look goes, looked at again every two seconds. Once it ends the whole page runs again, with what it found."""
    own = advisor.place(folder)
    running = batch.runner(own) is not None
    if st.session_state.get("advice_running") and not running:
        st.session_state["advice_running"] = False
        st.rerun(scope="app")
    st.session_state["advice_running"] = running
    if running:
        try:
            steps = (saved.read(own["folder"], advisor.STATE) or {}).get("steps", 0)
        except (OSError, ValueError):
            steps = 0
        st.info(f"The agent is looking: {activity.count(steps, 'model call')} so far, {advisor.STEPS} at most.", icon=":material/hourglass_top:")


def fix(one, state, started):
    """The brief of a ByteQuay task that fixes this risk, and, on the page ByteQuay started, Fix it to open it in that task's
    box. A task started from it notes the change by itself once it is completed, as a suggestion's does."""
    text = suggest.written(one["title"], one["seen"], one["do"], one["where"], f"Number to watch: {one['watch']}")
    with st.expander("Brief for a ByteQuay task"):
        st.code(text, language=None, wrap_lines=True)
    link = dashboard.task_link(text)
    if link and state != "done" and one["title"] not in started:
        st.link_button("Fix it", link, type="primary", help="Opens a ByteQuay task with this brief in its task box; you read it and send it")


def marks(folder, number, title, state):
    """The same marks as the lab's own suggestions, kept in the same record."""
    with st.container(horizontal=True):
        st.button("I changed it again" if state == "done" else "I made this change", key=f"risk_noted_{number}", on_click=digest.note, args=(folder, title, date.today()))
        if state != "done":
            st.button("Not working on it" if state == "doing" else "Working on it", key=f"risk_doing_{number}", on_click=record.mark,
                      args=(folder, title, None if state == "doing" else "doing", date.today()))
        with st.popover("Not wanted"):
            st.text_input("Why not", key=f"risk_why_{number}")
            st.button("Leave it out", key=f"risk_drop_{number}", on_click=lambda: record.mark(folder, title, "dropped", date.today(), st.session_state.get(f"risk_why_{number}", "")))


def found(folder, runtime, last, earlier):
    """What the last look found, the gravest first, each with how often the looks before found it and the marks of the
    lab's own suggestions. A risk marked not wanted is left out."""
    if last["state"] != "done":
        st.warning({"failed": f"The last look failed: {last['problem']}", "stopped": "The last look was stopped before it finished."}.get(
            last["state"], "The last look ended before it finished."))
        if last["state"] == "failed" and advisor.UNWATCHED in (last["problem"] or ""):
            st.button("Start the monitor", key="risk_monitor", on_click=monitor.start_session, args=(folder,))
        return
    usage = last["usage"] or {}
    st.caption(f"Looked {dashboard.when(last['ended'])} with {last['model']}: {activity.count(last['steps'], 'model call')}, "
               f"{usage.get('inputTokens', 0):,} tokens in and {usage.get('outputTokens', 0):,} out.")
    st.markdown(plain(last["summary"]))
    try:
        (noted, started), marked = dashboard.settle(folder, runtime), record.marks(folder)
    except (OSError, KeyError, TypeError, ValueError):
        noted, started, marked = [], {}, {}
        st.caption("The marks in `record.json` or the changes in `changes.json` could not be read, so every risk is shown as open.")
    before = [look for look in earlier if look["started"] != last["started"]]
    left_out = 0
    for number, one in enumerate(last["suggestions"]):
        state, day, _ = record.standing(one["title"], marked, noted)
        if state == "dropped":
            left_out += 1
            continue
        with st.container(border=True):
            label, icon, color = RISK[one["risk"]]
            st.badge(label, icon=icon, color=color)
            st.markdown(plain(one["title"]))
            for name, field in (("Seen", "seen"), ("Do", "do"), ("Where", "where"), ("Watch", "watch")):
                st.markdown(f"**{name}.** {plain(one[field])}")
            times = sum(any(other["title"] == one["title"] for other in look["suggestions"]) for look in before)
            st.caption(f"Earlier looks found it too: {times} of {len(before)}." if times else "New in this look.")
            if state == "done":
                st.caption(f"You noted this change on {suggest.said(day)}. " + (
                    "No look since then yet." if record.gone(one["title"], day, [last]) == "no look since yet" else "This look, after it, still finds it."))
            elif one["title"] in started:
                st.caption(f"A ByteQuay task is on it since {suggest.said(started[one['title']])}. Completing that task notes the change here.")
            elif state == "doing":
                st.caption(f"Being worked on since {suggest.said(day)}.")
            if one["action"] == "clean_up" and st.button("Clean up…", key=f"advice_clean_{number}"):
                st.session_state["cleanup_confirm"] = True
                st.caption("Confirm it under Clean up, at the end of the page.")
            elif one["action"] == "fix":
                fix(one, state, started)
            marks(folder, number, one["title"], state)
    if left_out:
        st.caption(f"{activity.count(left_out, 'risk')} you marked not wanted {'is' if left_out == 1 else 'are'} left out. "
                   "Bring one back under What became of them, on the Suggestions tab.")
    went = [one["title"] for one in before[-1]["suggestions"] if one["title"] not in {now["title"] for now in last["suggestions"]}] if before else []
    if went:
        st.caption(plain("Gone since the look before: " + "; ".join(went) + "."))
    with st.expander("What the agent saw first"):
        st.text(last["overview"] or "")


def risks(folder, runtime):
    st.subheader("Risks")
    looking(folder)
    own = advisor.place(folder)
    running = batch.runner(own) is not None
    try:
        last = saved.read(own["folder"], advisor.STATE)
    except (OSError, ValueError):
        last = None
        st.warning("What the agent found last could not be read.")
    try:
        earlier = advisor.looks(folder)
    except (OSError, ValueError):
        earlier = []
        st.caption("The earlier looks could not be read, so every risk is shown as new.")
    if last is not None and not running:
        found(folder, runtime, last, earlier)
    elif not running:
        st.caption("The lab's agent reads the monitor's numbers through read-only tools, finds risks and suggests what to do about each.")
    key = batch.has_key()
    if st.button("Look for risks", key="advice_look", disabled=running or not key):
        advisor.start(folder)
        st.rerun()
    st.caption("A look sends the monitor's counters and the titles of the last look's suggestions, each with how it stands, to DeepSeek's API, never "
               "a path, a command, a task's title or a reason you typed, and spends tokens on your key." if key
               else f"The agent needs a DeepSeek key. {dashboard.KEY}")
NUMBERS = {"memory MB": "%,.0f", "typical MB": "%,.0f", "peak MB": "%,.0f", "CPU cores": "%.2f", "typical CPU": "%.2f", "peak CPU": "%.2f", "threads": "%,.0f", "open files": "%,.0f"}


def tiles(made):
    with st.container(horizontal=True):
        st.metric("App memory", f"{made['memory'] / resources.GB:,.2f} GB", border=True, width=dashboard.TILE,
                  help="What the app's processes hold in memory. Its helpers share a little of it, so the sum runs slightly high.")
        st.metric("CPU cores", f"{made['cpu']:,.2f}", border=True, width=dashboard.TILE, help="1 means one core kept busy.")
        st.metric("Threads", f"{made['threads']:,}", border=True, width=dashboard.TILE)
        st.metric("Processes", f"{made['processes']:,}", border=True, width=dashboard.TILE)
        st.metric("Turns running", "not known" if made["turns"] is None else f"{made['turns']:,}", border=True, width=dashboard.TILE)
        st.metric("Open tasks", "not known" if made["tasks"] is None else f"{made['tasks']:,}", border=True, width=dashboard.TILE,
                  help=None if made["queued"] is None else f"{made['queued']:,} inputs wait in their queues.")
    st.caption(f"This Mac: {made['used'] / resources.GB:,.1f} of {made['total'] / resources.GB:,.0f} GB of memory in use.")


@st.fragment(run_every=2)
def live(folder, mode):
    """The newest samples, looked at again every two seconds."""
    end = time.time()
    span = (end - resources.LIVE, end)
    made = resources.read(folder, span[0])
    if made is None:
        st.info(f"Nothing is sampled yet. {GUIDE}")
        return
    parts, machine = made["parts"], made["machine"]
    if parts.empty or end - parts["at"].max() > resources.STALE:
        st.warning(f"The monitor is not running, so this is not live. {GUIDE}")
    if parts.empty:
        return
    tiles(resources.now(parts, machine))
    groups = resources.grouped(parts)
    left, right = st.columns(2)
    with left:
        st.caption("Memory over the last 15 minutes")
        dashboard.draw("live_memory", resources.lines_figure(groups, "memory", resources.GB, mode, " GB", span, stacked=True))
    with right:
        st.caption("CPU over the last 15 minutes")
        dashboard.draw("live_cpu", resources.lines_figure(groups, "cpu", 1, mode, " cores", span))


def over_time(folder, mode):
    st.subheader("Over time")
    chosen = st.segmented_control("Show", list(resources.SPANS), default="Last day", key="span") or "Last day"
    reach, bucket = resources.SPANS[chosen]
    end = time.time()
    span = (end - reach, end)
    made = resources.read(folder, span[0])
    if made is None or made["parts"].empty:
        st.info("Nothing was sampled in this stretch.")
        return made
    parts, machine = made["parts"], made["machine"]
    groups, turns = resources.bucketed(resources.grouped(parts), bucket, ["group"]), resources.bucketed(machine, bucket)
    st.caption(f"Memory, averaged over every {bucket // 60:,} minutes" if bucket < 3600 else f"Memory, averaged over every {bucket // 3600:,} hours")
    dashboard.draw("memory_over_time", resources.lines_figure(groups, "memory", resources.GB, mode, " GB", span, stacked=True, height=280))
    st.caption("Turns running on the same clock, so a spike above can be set against the work")
    dashboard.draw("turns_over_time", resources.turns_figure(turns, mode, span))
    left, right = st.columns(2)
    with left:
        st.caption("CPU")
        dashboard.draw("cpu_over_time", resources.lines_figure(groups, "cpu", 1, mode, " cores", span))
    with right:
        st.caption("Threads")
        dashboard.draw("threads_over_time", resources.lines_figure(groups, "threads", 1, mode, "", span))
    heap = made["heap"]
    if len(heap):
        last = heap.iloc[-1]
        st.caption(f"The backend's heap, asked of its JVM once a minute: now {last['used'] / resources.MB:,.0f} MB used"
                   + ("" if pandas.isna(last["most"]) else f" of {last['most'] / resources.MB:,.0f} MB") + f"; at most {heap['used'].max() / resources.MB:,.0f} MB used"
                   + ("" if heap["old"].isna().all() else f", and {heap['old'].max() / resources.MB:,.0f} MB lived through collections") + " in this stretch. "
                   "The backend's memory above is more than its heap.")
        dashboard.draw("heap_over_time", resources.heap_figure(resources.bucketed(heap, bucket), mode, span))
    st.subheader("Every part")
    st.caption("Now, typical and peak over this stretch. Typical is the middle of the samples a part ran in.")
    st.dataframe(resources.parts_table(parts), hide_index=True, column_config={name: st.column_config.NumberColumn(format=shape) for name, shape in NUMBERS.items()})
    st.subheader("Memory while no turn ran")
    idle = resources.idle_days(parts, machine)
    if idle is None:
        st.info("No sample of this stretch was taken while no turn ran.")
    else:
        st.caption("The typical memory of each group in MB on each day when no agent turn ran. Browser pages, refreshes and other background work may still run; "
                   "caches and app restarts also affect these values. Growth alone does not prove a leak. App health compares growth within one process session.")
        st.dataframe(idle, hide_index=True, column_config={group: st.column_config.NumberColumn(format="%,.0f") for group in resources.GROUPS})
    mine = parts[parts["part"] == "monitor"]
    if len(mine):
        st.caption(f"The monitor itself typically holds {mine['memory'].median() / resources.MB:,.0f} MB and keeps {100 * mine['cpu'].mean():.1f}% of one core busy.")
    return made


def disk(made, mode):
    st.subheader("Disk")
    owners = None if made is None else resources.owner_rows(made["space"])
    if owners is None:
        st.info("The app's data is counted a few times a day. The first count starts with the monitor and takes a few minutes.")
        return
    size = lambda chosen: f"{owners.loc[owners['owner'].isin(chosen), 'GB'].sum():,.1f} GB"
    disk_now = shutil.disk_usage(Path.home())
    with st.container(horizontal=True):
        st.metric("Could be freed", size(monitor.FREEABLE), border=True, width=dashboard.TILE,
                  help="Left by removed tasks and by features the app no longer has. Nothing in the app uses it.")
        st.metric("Idle open tasks", size([monitor.IDLE]), border=True, width=dashboard.TILE,
                  help="Held by open tasks nobody changed for a week or more. Closing the tasks you are done with frees it.")
        st.metric("Free on this disk", f"{disk_now.free / resources.GB:,.0f} GB", border=True, width=dashboard.TILE, help=f"Of {disk_now.total / resources.GB:,.0f} GB.")
    st.caption(f"Who holds the app's data, counted {datetime.fromtimestamp(made['space']['at'].max()):%Y-%m-%d %H:%M}. "
               "A block that copy-on-write copies share is counted once, so these add up to what the data really takes.")
    dashboard.draw("disk_owners", resources.disk_figure(owners, mode, by="owner"))
    st.dataframe(owners, hide_index=True, column_config={"GB": st.column_config.NumberColumn(format="%,.1f"), "files": st.column_config.NumberColumn(format="%,.0f")})
    rows = resources.disk_rows(made["space"])
    st.caption("By place: each top-level folder of the app's data. Grew is the change since the first count kept.")
    dashboard.draw("disk", resources.disk_figure(rows, mode))
    st.dataframe(rows, hide_index=True, column_config={"GB": st.column_config.NumberColumn(format="%,.1f"), "grew GB": st.column_config.NumberColumn(format="%+,.1f")})


@st.fragment(run_every=2)
def progress(folder):
    """How a clean-up goes, looked at again every two seconds. Once it ends the whole page runs again, with what is left."""
    own = cleanup.place(folder)
    try:
        last = saved.read(own["folder"], cleanup.STATE)
    except (OSError, ValueError):
        st.warning("How the last clean-up went could not be read.")
        return
    running = batch.runner(own) is not None
    if st.session_state.get("cleanup_running") and not running:
        st.session_state["cleanup_running"] = False
        st.rerun(scope="app")
    st.session_state["cleanup_running"] = running
    if last is None:
        return
    if running:
        st.progress(last["done"] / max(last["items"], 1), text="Counting the disk again" if last["state"] == "counting" else f"Deleting {last['done']:,} of {last['items']:,}")
        return
    freed = (last["freeAfter"] or 0) - last["freeBefore"]
    ended = {"stopped": " It was stopped before it finished.", "failed": f" It failed: {last['problem']}."}.get(last["state"], "" if last["state"] == "done" else " It ended before it finished.")
    st.caption(f"Last clean-up, {dashboard.when(last['ended'] or last['started'])}: deleted {sum(last['removed'].values()):,} of {last['items']:,} folders or files"
               + (f" and freed {freed / resources.GB:,.1f} GB on this disk." if freed > 0 else ".") + ended)
    if last["kept"]:
        st.caption("Kept, and why:")
        st.dataframe(pandas.DataFrame(last["kept"]), hide_index=True)


def clean_up(made, folder):
    st.subheader("Clean up")
    progress(folder)
    if batch.runner(cleanup.place(folder)) is not None:
        return
    now = time.time()
    everything, recent = cleanup.plan(monitor.APP_DATA, monitor.holders(monitor.DATABASE, now), now, copies=True)
    copies = [item for item in everything if item.owner == monitor.COPIES]
    later = f" {activity.count(len(recent), 'folder or file', 'folders or files')} nothing uses changed in the last day, so they stay for now." if recent else ""
    if not everything:
        st.info("Nothing to clean up: no data is left by removed tasks or by features the app no longer has, and no old database copy." + later)
        return
    st.caption("What nothing in the app uses any more, found by the same rules as the disk count. A worktree goes through git, which keeps any with "
               "changes not committed; a repository stays while it has work found nowhere else; a removed task's event log stays, as the lab reads it." + later)
    st.dataframe(resources.cleanup_rows(monitor.APP_DATA, everything, made["space"] if made else pandas.DataFrame()), hide_index=True,
                 column_config={"GB at the last count": st.column_config.NumberColumn(format="%,.1f")})
    with_copies = bool(copies) and st.checkbox(f"Also delete the old database copies ({activity.count(len(copies), 'folder or file', 'folders or files')})", key="cleanup_copies",
                                               help="Copies of the database made before past changes. The app does not read them, but they are the only way back to an older database.")
    chosen = [item for item in everything if item.owner != monitor.COPIES or with_copies]
    if not chosen:
        st.caption("Only old database copies are left: tick the box to delete them.")
        return
    if st.button("Clean up…", key="cleanup_ask"):
        st.session_state["cleanup_confirm"] = True
    if not st.session_state.get("cleanup_confirm"):
        return
    st.warning(f"Delete {activity.count(len(chosen), 'folder or file', 'folders or files')}? This cannot be undone.")
    with st.container(horizontal=True):
        if st.button("Delete", key="cleanup_yes", type="primary"):
            st.session_state["cleanup_confirm"] = False
            cleanup.start(folder, copies=with_copies)
            st.rerun()
        if st.button("Cancel", key="cleanup_no"):
            st.session_state["cleanup_confirm"] = False
            st.rerun()


dashboard.start_page()
arguments = argparse.ArgumentParser(add_help=False)
arguments.add_argument("folder", type=Path, nargs="?", default=saved.FOLDER)
arguments.add_argument("--runtime", type=Path, default=activity.RUNTIME)
PARSED = arguments.parse_known_args()[0]
FOLDER, RUNTIME = PARSED.folder, PARSED.runtime
MODE = "dark" if st.context.theme.type == "dark" else "light"
st.title("Resources")
st.caption("What ByteQuay takes from this Mac: its Electron app, its Java backend, and the agents it runs with the commands they start.")
risks(FOLDER, RUNTIME)
live(FOLDER, MODE)
MADE = over_time(FOLDER, MODE)
disk(MADE, MODE)
clean_up(MADE, FOLDER)
