"""The lab's Improve page: one improvement of the review rules at a time, started, carried on and stopped here, as
`uv run improve.py` would. It shows how far each improvement got, what its versions changed and why, and its verdict. Of
the unseen cases it shows only each setting's totals and the change between them."""

import streamlit as st

import batch
import dashboard
import improve
import saved

TAIL = 8  # lines of improve.log shown while an improvement runs


def plans(folder):
    """Every experiment whose plan can run, by name."""
    found = {}
    for path in sorted((folder / "experiments").glob("*/plan.json")):
        try:
            found[path.parent.name] = batch.load(folder, path.parent.name)
        except ValueError:
            continue  # the Reviews tab says what is wrong with it
    return found


def holder(folder, made):
    """The tuning plan of an improvement, when it can still be read."""
    try:
        return batch.load(folder, made["tuning"])
    except ValueError:
        return None


def version(folder, made, label):
    """What one version changed and why, from the writer's record in the tuning experiment, and the merge's whole diff."""
    try:
        written = saved.read(folder / "experiments" / made["tuning"] / "rules", f"{label}.json")
    except (OSError, ValueError):
        written = None
    if not written:
        st.caption(f"{label}: its record cannot be read.")
        return
    st.write(f"{label} · {written['kind']} · {written['state']}")
    for change in written.get("changes") or []:
        st.markdown(f"- {dashboard.plain(change['what'].rstrip('.'))}: {dashboard.plain(change['why'])}")
    if written.get("lesson"):
        st.caption("Lesson: " + dashboard.plain(written["lesson"]))
    if written.get("kind") == "merge" and written.get("diff"):
        st.code(written["diff"], language="diff")


@st.fragment(run_every=5)
def improving(folder):
    """The improvement that runs, with its stages and a stop; or the choices to start one or carry the last one on."""
    try:
        kept = improve.improvements(folder)
    except (OSError, ValueError) as problem:
        st.warning(f"The improvements could not be read: {problem}")
        return
    latest = kept[0] if kept else None
    plan = holder(folder, latest) if latest else None
    if latest and latest["state"] == "running" and plan is not None and batch.runner(plan) is not None:
        done = latest["done"]
        st.subheader(f"Improvement {latest['number']} is running")
        st.progress(len(done) / len(improve.STAGES), text=f"{len(done)} of {len(improve.STAGES)} stages done" + (f": {', '.join(done)}" if done else ""))
        named = ", ".join(label for label in latest["labels"].values() if label)
        if named:
            st.caption(f"Versions so far: {named}")
        st.button("Stop", key="improve_stop", on_click=batch.stop, args=(plan,))
        log = improve.place(folder) / "improve.log"
        if log.exists():
            st.caption("The end of improve.log, where an improvement started here writes what it says:")
            st.code("\n".join(log.read_text().splitlines()[-TAIL:]), language=None)
        return
    key = batch.has_key()
    if latest and latest["state"] != "done":
        st.info(f"Improvement {latest['number']} {latest['state'] if latest['state'] != 'running' else 'ended without saying how'}"
                + (f": {latest['problem']}" if latest["problem"] else "."))
        st.button(f"Carry on improvement {latest['number']}", key="improve_continue", disabled=not key, on_click=improve.start,
                  args=(folder, ["--continue"], latest["tuning"]))
    found = plans(folder)
    tuning = [name for name, chosen in found.items() if not chosen["unseen"]]
    unseen = [name for name, chosen in found.items() if chosen["unseen"]]
    st.subheader("Start an improvement")
    if not tuning or not unseen:
        st.info('An improvement needs an experiment of tuning cases and one of unseen cases, marked with "unseen": true in its plan.')
        return
    last = latest or {}
    first = lambda options, name: options.index(last[name]) if last.get(name) in options else 0  # the last improvement's choice, if it is still there
    left, middle, right = st.columns(3)
    learned = left.selectbox("Tuning cases", tuning, index=first(tuning, "tuning"), key="improve_tuning")
    checked = middle.selectbox("Unseen cases", unseen, index=first(unseen, "unseen"), key="improve_unseen")
    shared = sorted({one["name"] for one in found[learned]["settings"]} & {one["name"] for one in found[checked]["settings"]})
    setting = right.selectbox("Setting with today's rules", shared, index=first(shared, "setting") if shared else None, key="improve_setting")
    problem = None
    if setting is None:
        problem = f"{learned} and {checked} name no setting in common."
    else:
        try:
            improve.checked(folder, learned, checked, setting)
        except ValueError as wrong:
            problem = str(wrong)
    busy = batch.runner(found[learned]) is not None or batch.runner(found[checked]) is not None
    st.button("Start an improvement", key="improve_start", type="primary", disabled=bool(problem) or busy or not key, on_click=improve.start,
              args=(folder, ["--tuning", learned, "--unseen", checked, "--setting", setting or ""], learned))
    if problem:
        st.caption(problem)
    elif busy:
        st.caption(f"{learned if batch.runner(found[learned]) is not None else checked} is running; an improvement starts once it ends.")
    if not key:
        st.caption(f"The writer and the judge need a DeepSeek key. {dashboard.KEY}")
    st.caption("An improvement takes about 36 review runs, some three hours two at a time, and spends DeepSeek tokens on your key for three "
               "writings and the judge.")


def history(folder):
    """Every improvement, the latest first: how far it got, its verdict on the unseen cases, and what each version changed."""
    try:
        kept = improve.improvements(folder)
    except (OSError, ValueError):
        return
    if kept:
        st.subheader("Improvements")
    for made in kept:
        verdict = (made.get("compared") or {}).get("verdict")
        with st.expander(f"Improvement {made['number']}: {verdict or made['state']}", expanded=made is kept[0]):
            st.caption(f"Learned from {made['tuning']}, checked on {made['unseen']}, setting {made['setting']}. Started {dashboard.when(made['started'])}."
                       + (f" Stages done: {', '.join(made['done'])}." if made["done"] else ""))
            if made["problem"]:
                st.warning(made["problem"])
            if made.get("compared"):
                for line in improve.said(made["compared"]):
                    st.write(line)
            for label in (made["labels"].get(kind) for kind in ("work", "expert", "merge")):
                if label:
                    version(folder, made, label)


dashboard.start_page()
FOLDER = dashboard.arguments()[0]
st.title("Improve")
st.caption("One improvement: the lab's agent writes a version of how the reviewer works and one of what an expert of the repository knows "
           "from the runs on the tuning cases, tries both on them, merges what their runs support, and runs the merged version on the "
           "unseen cases beside the rules as they stand. Making a version the rules ByteQuay ships is your step.")
improving(FOLDER)
history(FOLDER)
