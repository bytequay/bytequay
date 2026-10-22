import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

import batch
import cleanup
import monitor

SCRIPT = Path(cleanup.__file__).resolve()
OPEN_TASK, GONE_TASK = (f"{digit * 8}-{digit * 4}-{digit * 4}-{digit * 4}-{digit * 12}" for digit in "15")
OLD = time.time() - 2 * 86400


def git(*arguments, cwd):
    return subprocess.run(["git", "-c", "user.name=lab", "-c", "user.email=lab@example.invalid", *arguments], cwd=cwd, check=True, capture_output=True, text=True).stdout


class Data(unittest.TestCase):
    """The app's data with an open task, a task the database no longer has, the retired code index and the old development
    flow's worktrees, and three worktrees of one repository: one an open task's, two that no row names, one of them with a
    change not committed. Everything is two days old."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.data, self.folder = Path(directory.name).resolve() / "data", Path(directory.name).resolve() / "lab"
        for name in (f"agent-runtime/{OPEN_TASK}/events.jsonl", f"agent-runtime/{OPEN_TASK}/provider/state", f"agent-runtime/{OPEN_TASK}/codegraph/codegraph.db",
                     f"agent-runtime/{GONE_TASK}/events.jsonl", f"agent-runtime/{GONE_TASK}/blobs/one", f"agent-runtime/{GONE_TASK}/ci-memory.md",
                     "agent-runtime/codegraph-baselines/r/codegraph.db", f"agent-runtime/ide/{GONE_TASK}/index", f"agent-runtime/ide/{OPEN_TASK}/index",
                     "agent-runtime/_processes/p", "Cache/entry", "database-backup-1/db", "new-flow-worktrees/o/r/notes.txt"):
            (self.data / name).parent.mkdir(parents=True, exist_ok=True)
            (self.data / name).write_text("x")
        self.repository = self.data / "workspaces/w/repository"
        self.repository.mkdir(parents=True)
        git("init", "-q", cwd=self.repository)
        (self.repository / "tracked.txt").write_text("one")
        git("add", "tracked.txt", cwd=self.repository)
        git("commit", "-q", "-m", "first", cwd=self.repository)
        self.trees = {name: self.data / "workspaces/w/worktrees" / name for name in ("open-tree", "gone-clean", "gone-dirty")} | {"flow": self.data / "new-flow-worktrees/o/r/abc"}
        for name, tree in self.trees.items():
            git("worktree", "add", "-q", "-b", name, str(tree), cwd=self.repository)
        (self.trees["gone-dirty"] / "tracked.txt").write_text("changed")
        for path in [self.data, *self.data.rglob("*")]:
            os.utime(path, (OLD, OLD), follow_symlinks=False)
        self.known = ({OPEN_TASK: monitor.OPEN}, {"open-tree": monitor.OPEN})

    def planned(self, known):
        going, recent = cleanup.plan(self.data, known, time.time())
        return {(item.owner, item.path.relative_to(self.data).as_posix()) for item in going}, [item.path for item in recent]

    def worktrees(self):
        return {Path(line[len("worktree "):]).name for line in git("worktree", "list", "--porcelain", cwd=self.repository).splitlines() if line.startswith("worktree ")}


class TestPlan(Data):
    def test_what_nothing_uses_is_planned_and_the_rest_stays(self):
        going, recent = self.planned(self.known)
        self.assertEqual(going, {(monitor.RETIRED, f"agent-runtime/{OPEN_TASK}/codegraph"), (monitor.RETIRED, "agent-runtime/codegraph-baselines"),
                                 (monitor.RETIRED, "new-flow-worktrees"), (monitor.REMOVED, f"agent-runtime/{GONE_TASK}/blobs"),
                                 (monitor.REMOVED, f"agent-runtime/{GONE_TASK}/ci-memory.md"), (monitor.REMOVED, f"agent-runtime/ide/{GONE_TASK}"),
                                 (monitor.REMOVED, "workspaces/w/worktrees/gone-clean"), (monitor.REMOVED, "workspaces/w/worktrees/gone-dirty")})
        self.assertEqual(recent, [])  # the removed task's event log is not planned at all: the lab reads it

    def test_what_changed_in_the_last_day_stays(self):
        os.utime(self.trees["gone-clean"])
        os.utime(self.data / f"agent-runtime/{GONE_TASK}/blobs/one")  # a file in the folder changed, not the folder itself
        going, recent = self.planned(self.known)
        self.assertNotIn((monitor.REMOVED, "workspaces/w/worktrees/gone-clean"), going)
        self.assertEqual(recent, [self.data / f"agent-runtime/{GONE_TASK}/blobs", self.trees["gone-clean"]])

    def test_without_the_database_only_what_retired_features_left_is_planned(self):
        self.assertEqual({owner for owner, _ in self.planned(None)[0]}, {monitor.RETIRED})

    def test_old_database_copies_are_planned_only_when_asked_for(self):
        with_copies, _ = cleanup.plan(self.data, self.known, time.time(), copies=True)
        self.assertEqual({(item.owner, item.path.relative_to(self.data).as_posix()) for item in with_copies} - self.planned(self.known)[0],
                         {(monitor.COPIES, "database-backup-1")})

    def test_the_workspaces_outside_the_app_data_are_planned_by_the_same_rules(self):
        expected, _ = self.planned(self.known)
        moved = self.data.parent / "home/.bytequay/workspaces"
        moved.parent.mkdir(parents=True)
        (self.data / "workspaces").rename(moved)
        with mock.patch.object(monitor, "APP_DATA", self.data), mock.patch.object(monitor, "WORKSPACES", moved):
            going, _ = cleanup.plan(self.data, self.known, time.time())
            self.assertEqual({(item.owner, "/".join(monitor.names(item.path, self.data))) for item in going}, expected)
            self.assertEqual(cleanup.tally(self.data, going)[(monitor.REMOVED, "workspaces")], 2)

    def test_a_count_says_how_many_go_from_each_place(self):
        going, _ = cleanup.plan(self.data, self.known, time.time())
        self.assertEqual(cleanup.tally(self.data, going), {(monitor.RETIRED, "agent-runtime"): 2, (monitor.RETIRED, "new-flow-worktrees"): 1,
                                                          (monitor.REMOVED, "agent-runtime"): 3, (monitor.REMOVED, "workspaces"): 2})


class TestRemove(Data):
    def test_a_worktree_goes_through_git_and_one_with_changes_stays(self):
        self.assertIsNone(cleanup.let_go(self.trees["gone-clean"]))
        self.assertFalse(self.trees["gone-clean"].exists())
        self.assertNotIn("gone-clean", self.worktrees())
        self.assertIn("gone-clean", git("branch", "--list", "gone-clean", cwd=self.repository))  # its commits stay in the repository
        self.assertEqual(cleanup.let_go(self.trees["gone-dirty"]), "it holds changes not committed")
        self.assertEqual((self.trees["gone-dirty"] / "tracked.txt").read_text(), "changed")
        self.assertEqual(cleanup.let_go(self.repository), "it has commits on no remote")  # a repository with no remote holds them alone
        broken = self.data / "workspaces/w/worktrees/broken"
        broken.mkdir()
        (broken / ".git").write_text("gitdir: /nowhere/.git/worktrees/broken\n")  # its repository is gone, so git cannot say what it holds
        self.assertEqual((cleanup.let_go(broken), broken.exists()), ("git cannot read it", True))

    def test_detached_worktree_keeps_commits_until_a_durable_ref_holds_them(self):
        tree = self.trees["gone-clean"]
        git("checkout", "-q", "--detach", cwd=tree)
        git("commit", "-q", "--allow-empty", "-m", "detached work", cwd=tree)
        head = git("rev-parse", "HEAD", cwd=tree).strip()
        self.assertEqual(cleanup.let_go(tree), "it has commits on no branch, tag or remote")
        self.assertEqual(git("rev-parse", "HEAD", cwd=tree).strip(), head)
        git("tag", "saved-detached-work", head, cwd=self.repository)
        self.assertIsNone(cleanup.let_go(tree))
        self.assertFalse(tree.exists())
        self.assertEqual(git("rev-parse", "saved-detached-work", cwd=self.repository).strip(), head)

    def test_a_folder_with_a_kept_worktree_loses_everything_else(self):
        (self.trees["flow"] / "tracked.txt").write_text("changed")
        kept = cleanup.remove(self.data / "new-flow-worktrees")
        self.assertEqual(kept, {self.trees["flow"]: "it holds changes not committed"})
        self.assertFalse((self.data / "new-flow-worktrees/o/r/notes.txt").exists())
        self.assertEqual((self.trees["flow"] / "tracked.txt").read_text(), "changed")

    def test_a_folder_that_may_not_be_written_to_is_still_deleted(self):
        locked = self.data / f"agent-runtime/{GONE_TASK}/blobs"
        locked.chmod(0o500)
        self.addCleanup(lambda: locked.exists() and locked.chmod(0o700))
        self.assertEqual(cleanup.remove(locked), {})
        self.assertFalse(locked.exists())


class TestRepositories(unittest.TestCase):
    """A clone of the layout before workspaces with its remote, and a worktree made from it in the folder beside it."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name).resolve()
        self.origin, self.repos = root / "origin.git", root / "data" / "repos"
        git("init", "-q", "--bare", str(self.origin), cwd=root)
        self.clone = self.repos / "o" / "r"
        git("clone", "-q", str(self.origin), str(self.clone), cwd=root)
        (self.clone / "tracked.txt").write_text("one")
        git("add", "tracked.txt", cwd=self.clone)
        git("commit", "-q", "-m", "first", cwd=self.clone)
        git("push", "-q", "origin", "HEAD", cwd=self.clone)
        self.tree = self.repos / "o" / "r.bytequay-worktrees" / "pick" / "abc"
        git("worktree", "add", "-q", "-b", "pick", str(self.tree), "HEAD", cwd=self.clone)
        git("push", "-q", "origin", "pick", cwd=self.clone)

    def test_a_repository_goes_once_all_its_work_is_on_its_remote_and_its_worktrees_are_gone(self):
        self.assertEqual(cleanup.remove(self.repos), {})
        self.assertFalse(self.repos.exists())
        self.assertIn("first", git("log", "--oneline", cwd=self.origin))  # nothing was only there

    def test_a_repository_with_work_found_nowhere_else_stays(self):
        for change, why in (
                (lambda: (git("commit", "-q", "--allow-empty", "-m", "only here", cwd=self.clone)), "it has commits on no remote"),
                (lambda: ((self.clone / "tracked.txt").write_text("two"), git("stash", "-q", cwd=self.clone)), "it has a stash"),
                (lambda: (self.clone / "tracked.txt").write_text("three"), "it holds changes not committed")):
            with self.subTest(why=why):
                self.setUp()
                change()
                self.assertEqual(cleanup.remove(self.repos), {self.clone: why})
                self.assertTrue((self.clone / ".git").is_dir())
                self.assertFalse(self.tree.exists())  # its worktree had nothing of its own, so it went

    def test_a_worktree_deleted_by_hand_does_not_keep_its_repository(self):
        stale = self.repos.parent / "gone-tree"
        git("worktree", "add", "-q", "-b", "gone", str(stale), "HEAD", cwd=self.clone)
        git("push", "-q", "origin", "gone", cwd=self.clone)
        shutil.rmtree(stale)  # git still lists it until it is pruned
        self.assertEqual(cleanup.remove(self.repos), {})
        self.assertFalse(self.repos.exists())

    def test_a_repository_with_a_worktree_elsewhere_stays(self):
        elsewhere = self.repos.parent / "workspaces" / "w" / "worktrees" / "open-tree"
        git("worktree", "add", "-q", "-b", "open", str(elsewhere), "HEAD", cwd=self.clone)
        git("push", "-q", "origin", "open", cwd=self.clone)
        self.assertEqual(cleanup.remove(self.repos / "o"), {self.clone: "a worktree made from it is still there"})
        self.assertTrue((elsewhere / "tracked.txt").exists())

    def test_repository_keeps_detached_head_and_unpublished_tag_commits(self):
        git("checkout", "-q", "--detach", cwd=self.clone)
        git("commit", "-q", "--allow-empty", "-m", "detached work", cwd=self.clone)
        head = git("rev-parse", "HEAD", cwd=self.clone).strip()
        self.assertEqual(cleanup.remove(self.repos), {self.clone: "it has commits on no remote"})
        self.assertEqual(git("rev-parse", "HEAD", cwd=self.clone).strip(), head)
        git("tag", "saved-detached-work", head, cwd=self.clone)
        git("checkout", "-q", "-", cwd=self.clone)
        self.assertEqual(cleanup.remove(self.repos), {self.clone: "it has commits on no remote"})
        self.assertEqual(git("rev-parse", "saved-detached-work", cwd=self.clone).strip(), head)


class TestApply(Data):
    def database(self):
        path = self.data / "bytequay-1.0.db"
        with sqlite3.connect(path) as connection:
            connection.executescript("CREATE TABLE agent_task (id TEXT, state TEXT, updated_at TEXT, worktree TEXT); CREATE TABLE branch_rebase (worktree TEXT);"
                                     "CREATE TABLE cherry_pick_execution (worktree TEXT);")
            connection.execute("INSERT INTO agent_task VALUES (?, 'OPEN', '2026-10-15T07:00:00Z', ?)", (OPEN_TASK, str(self.trees["open-tree"])))
        connection.close()
        return path

    def test_apply_deletes_what_nothing_uses_keeps_the_rest_and_counts_again(self):
        made = cleanup.apply(self.folder, self.data, self.database())
        self.assertEqual((made["state"], made["items"], made["done"], made["removed"]), ("done", 8, 8, {monitor.REMOVED: 4, monitor.RETIRED: 3}))
        self.assertEqual(made["kept"], [{"what": "workspaces/w/worktrees/gone-dirty", "owner": monitor.REMOVED, "why": "it holds changes not committed"}])
        for gone in (f"agent-runtime/{OPEN_TASK}/codegraph", f"agent-runtime/{GONE_TASK}/blobs", "agent-runtime/codegraph-baselines", "new-flow-worktrees",
                     "workspaces/w/worktrees/gone-clean", f"agent-runtime/ide/{GONE_TASK}"):
            with self.subTest(gone=gone):
                self.assertFalse((self.data / gone).exists())
        for stays in (f"agent-runtime/{GONE_TASK}/events.jsonl", f"agent-runtime/{OPEN_TASK}/provider/state", f"agent-runtime/ide/{OPEN_TASK}/index",
                      "workspaces/w/worktrees/open-tree/tracked.txt", "workspaces/w/worktrees/gone-dirty/tracked.txt", "Cache/entry", "database-backup-1/db"):
            with self.subTest(stays=stays):
                self.assertTrue((self.data / stays).exists())
        self.assertEqual(self.worktrees(), {"repository", "open-tree", "gone-dirty"})
        self.assertEqual(json.loads((self.folder / "cleanup" / cleanup.STATE).read_text())["state"], "done")
        connection = monitor.store(self.folder)
        self.addCleanup(connection.close)
        self.assertTrue(connection.execute("SELECT COUNT(*) FROM space").fetchone()[0])  # the disk was counted again

    def test_old_database_copies_go_only_when_asked_for(self):
        self.assertTrue((self.data / "database-backup-1").exists())
        made = cleanup.apply(self.folder, self.data, self.database(), copies=True)
        self.assertEqual((made["items"], made["removed"]), (9, {monitor.REMOVED: 4, monitor.RETIRED: 3, monitor.COPIES: 1}))
        self.assertFalse((self.data / "database-backup-1").exists())

    def test_a_failure_or_a_stop_is_kept_as_how_it_ended(self):
        database = self.database()
        for raised, state, problem in ((RuntimeError("broken"), "failed", "RuntimeError: broken"), (KeyboardInterrupt(), "stopped", None)):
            with self.subTest(state=state):
                with mock.patch.object(cleanup, "remove", side_effect=raised), self.assertRaises(type(raised)):
                    cleanup.apply(self.folder, self.data, database)
                kept = json.loads((self.folder / "cleanup" / cleanup.STATE).read_text())
                self.assertEqual((kept["state"], kept["done"], kept["problem"], bool(kept["ended"])), (state, 0, problem, True))
        self.assertTrue((self.data / "agent-runtime/codegraph-baselines").exists())


class TestCommandLine(Data):
    def command(self, *arguments):
        return subprocess.run([sys.executable, str(SCRIPT), "--data", str(self.data), "--folder", str(self.folder), *arguments],
                              capture_output=True, text=True, stdin=subprocess.DEVNULL)

    def test_with_no_option_it_lists_and_with_apply_it_deletes(self):
        listed = self.command()  # no database here: only what retired features left
        self.assertEqual(listed.stdout.splitlines()[:3], ["Would delete 3 places nothing in ByteQuay uses:", "  retired features in agent-runtime: 2",
                                                          "  retired features in new-flow-worktrees: 1"])
        self.assertIn("1 old database copy stays: --with-copies deletes them too.", listed.stdout.splitlines())
        self.assertTrue((self.data / "agent-runtime/codegraph-baselines").exists())
        applied = self.command("--apply")
        self.assertEqual((applied.returncode, applied.stdout), (0, "Deleted 3 of 3 places; 0 worktrees or folders stay.\n"))
        self.assertFalse((self.data / "agent-runtime/codegraph-baselines").exists())
        self.assertTrue((self.data / "database-backup-1").exists())
        self.assertFalse((self.folder / "cleanup" / "running.pid").exists())
        copied = self.command("--apply", "--with-copies")
        self.assertEqual((copied.returncode, copied.stdout, (self.data / "database-backup-1").exists()), (0, "Deleted 1 of 1 places; 0 worktrees or folders stay.\n", False))

    def test_the_page_starts_the_clean_up_apart_and_asks_for_the_copies_only_when_chosen(self):
        with mock.patch.object(cleanup.subprocess, "Popen") as started:
            started.return_value.poll.return_value = 0  # it ended at once, so start does not wait
            cleanup.start(self.folder)
            cleanup.start(self.folder, copies=True)
        self.assertEqual([call.args[0][2:] for call in started.call_args_list],
                         [["--apply", "--folder", str(self.folder)], ["--apply", "--folder", str(self.folder), "--with-copies"]])
        self.assertTrue(all(call.kwargs["start_new_session"] for call in started.call_args_list))

    def test_two_clean_ups_never_run_at_once(self):
        (self.folder / "cleanup").mkdir(parents=True)
        held = batch.claim(cleanup.place(self.folder))
        self.addCleanup(held.close)
        refused = self.command("--apply")
        self.assertEqual((refused.returncode, refused.stderr), (1, f"cleanup is already running, as process {os.getpid()}\n"))
        self.assertTrue((self.data / "agent-runtime/codegraph-baselines").exists())


if __name__ == "__main__":
    unittest.main()
