"""The Lab's landing view: coverage, observed app health and actions supported by local evidence."""

from datetime import date
import sqlite3
import time

import streamlit as st

import app_logs
import dashboard
import digest
import health
import monitor
import record
import resources
import saved
import suggest


def render(folder, runtime):
    st.subheader("App health")
    st.caption("Local log analysis and resource measurements. Actions prepare a task for you to review and send; no model key is needed.")
    now = time.time()
    try:
        table = saved.read(folder, app_logs.FILE)
    except (OSError, ValueError):
        table = None
    try:
        samples = resources.read(folder, now - 3600)
    except (OSError, sqlite3.Error, ValueError):
        samples = None
    try:
        made = health.overall(table, samples, now)
    except (KeyError, TypeError, ValueError):
        table, samples = None, None
        made = health.overall(None, None, now)
        st.warning("The saved health measurements could not be read. Choose Analyze now to replace them.")
    if table:
        dashboard.invalidate_logs(folder)
    {"Attention needed": st.warning, "Unknown": st.info, "No issue observed": st.success}[made["status"]](made["status"])
    st.caption("This describes the evidence below, not a guarantee that every app feature works. UI responsiveness and external-service availability are not directly measured.")
    st.dataframe(made["components"], hide_index=True, width="stretch")
    if table:
        coverage = table["coverage"]
        with st.container(horizontal=True):
            st.metric("Errors in analyzed 24 hours", table["errors"], border=True)
            st.metric("Warnings in analyzed 24 hours", table["warnings"], border=True)
            st.metric("Log files read", coverage["files"], border=True)
        st.caption(f"Analyzed 24-hour window ends {dashboard.when(table['savedAt'])}. Read at most {app_logs.MAX_FILES} files and {app_logs.MAX_BYTES // 1024**2} MB per file. "
                   f"Unreadable: {coverage['unreadable']}; truncated: {coverage['truncated']}; omitted: {coverage['omitted']}. Counts cover only readable retained logs.")
        if table["groups"]:
            with st.expander("Repeated log signals"):
                st.dataframe([{"signal": app_logs.RULES[row["category"]][1] if row["category"] in app_logs.RULES else "Other logged failure",
                               "component": row["component"], "level": row["level"], "count": row["count"],
                               "last seen": dashboard.when(saved.stamp(row["last"]))} for row in table["groups"]], hide_index=True)
        with st.expander("Measured send latency"):
            if table["timings"]:
                st.dataframe([{"phase": row["name"], "samples": row["count"], "median seconds": round(row["median"], 3),
                               "p95 seconds": round(row["p95"], 3), "max seconds": round(row["max"], 3)} for row in table["timings"]], hide_index=True)
                st.caption("Only matched phase pairs count. Phases can overlap and must not be added together. Provider wait includes processing and network time.")
            else:
                st.info("No matched send-timing samples. Optional timing logs must already be enabled; ordinary logs do not measure send latency.")
    else:
        st.info("No readable health snapshot. Choose Analyze now to analyze the existing backend logs.")
    fresh = (samples is not None and not samples["parts"].empty and not samples["machine"].empty
             and 0 <= now - min(samples["parts"]["at"].max(), samples["machine"]["at"].max()) <= resources.STALE
             and max(samples["parts"]["at"].max(), samples["machine"]["at"].max()) <= now)
    if fresh:
        values = resources.now(samples["parts"], samples["machine"])
        with st.container(horizontal=True):
            st.metric("App memory", f"{values['memory'] / resources.GB:.2f} GB", border=True)
            st.metric("CPU cores", f"{values['cpu']:.2f}", border=True)
            st.metric("Processes", values["processes"], border=True)
            st.metric("Turns running", values["turns"] if values["turns"] is not None else "Unknown", border=True)
        st.caption("Resource sample: " + dashboard.when(saved.stamp(samples["machine"]["at"].max())) + ". Analyze now updates this view; Resources shows live charts.")
    else:
        st.caption("Resource history needs the optional monitor. It samples every fifteen seconds while Lab is open and stops when you close Lab or quit ByteQuay.")
        if st.button("Start resource monitoring", key="health_monitor"):
            try:
                monitor.start_session(folder)
                st.success("Monitoring started. Choose Analyze now after samples arrive, or open Resources for live charts.")
            except ValueError as error:
                st.error(str(error))
    st.subheader("Actions supported by the evidence")
    try:
        noted, started = dashboard.settle(folder, runtime)
    except (OSError, ValueError, KeyError, TypeError):
        noted, started = [], {}
        st.caption("Task follow-up status is unavailable.")
    if not made["findings"]:
        st.info("No action meets the evidence thresholds. Unknown or incomplete measurements are not a clean bill of health.")
    for one in made["findings"]:
        with st.container(border=True):
            st.markdown("##### " + dashboard.plain(one["title"]))
            st.caption("High priority" if one["severity"] == "high" else "Investigate")
            st.write("Seen: " + one["seen"])
            st.write("Action: " + one["do"])
            st.write("Verify: " + one["watch"])
            st.caption("Source: " + one["where"])
            state, day, _ = record.standing(one["title"], {}, noted)
            if state == "done":
                st.caption(f"A change was noted on {day}. Evidence still meets this rule; check measurements after running the updated app.")
            elif one["title"] in started:
                st.caption("A ByteQuay task is working on this action.")
            text = suggest.written(one["title"], one["seen"], one["do"], one["where"], "Verify with the same workload: " + one["watch"])
            with st.expander("Task brief"):
                st.code(text, language=None, wrap_lines=True)
            link = dashboard.task_link(text)
            if link and one["title"] not in started:
                st.link_button("Investigate in ByteQuay", link, key=f"health_fix_{one['id']}")
            st.button("I made this change", key=f"health_done_{one['id']}", on_click=digest.note, args=(folder, one["title"], date.today()))
