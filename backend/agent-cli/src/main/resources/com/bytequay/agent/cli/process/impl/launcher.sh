# Gated launcher for one supervised provider process.
#
# Invoked as: /bin/bash -c <this script> bytequay-launch <gate> <identity> <payload argv...>
#
# It exists to solve two problems the JVM cannot solve on its own:
#
#   1. Crash window. ProcessBuilder returns an already-running process, so there is no way to
#      record "PID 1234 is mine" before 1234 can write to a Git worktree. This script reports its
#      PID and then blocks, so the supervisor can make the record durable before anything runs.
#   2. Containment. Java has no setpgid, and on macOS a ProcessBuilder child inherits the JVM's
#      process group. ProcessHandle.descendants() only walks a live tree, so after a backend crash
#      a re-parented grandchild is invisible. A process group outlives its leader, so the payload
#      is made a group leader and the group is the termination receipt.
#
# Exit 70 means the gate closed and the payload was never executed. DefaultProcessSupervisor
# checks for it when it rolls a launch back.

bq_gate=$1        # gate file the supervisor writes to release or cancel this launch
bq_identity=$2    # file this script writes its payload PID to
shift 2           # "$@" is now exactly the payload argv, with no shell interpretation

# Job control is enabled for one side effect only: under `set -m` a background job becomes a
# process-group leader, so the subshell below gets pgid == pid. It is not used to manage jobs.
# This needs bash: dash, which is /bin/sh on Debian and Ubuntu, refuses job control when it has
# no controlling terminal, which is exactly how a backend or a CI runner starts this script.
set -m

{
  # Wait without spawning polling helpers: a disappearing sleep process could be mistaken for an
  # unidentified provider descendant. The supervisor writes this line only after fsyncing GO to
  # the gate file. EOF on backend death or the timeout exits without running the payload.
  IFS= read -r -t 60 bq_release || exit 70
  if [ "$bq_release" != GO ]; then exit 70; fi
  read -r bq_release < "$bq_gate" || exit 70
  if [ "$bq_release" != GO ]; then exit 70; fi

  # exec keeps this PID and process group, so the identity reported below is the payload's own.
  exec "$@"
} &

# $! and not $$: POSIX $$ inside a subshell reports the parent shell, and bash 3.2 has no $BASHPID.
bq_payload=$!

# Silence only this outer shell. It must happen after the fork above, because the background job
# has already inherited the real stderr; with `set -m` bash would otherwise print job notices such
# as "[1]+ Done" into the payload's stderr stream, which is a provider diagnostic channel.
exec 2>/dev/null

# Publish the payload PID atomically so the supervisor never reads a partial line.
printf '%s\n' "$bq_payload" > "$bq_identity.part"
mv "$bq_identity.part" "$bq_identity"

# Exit with the payload's status so the supervisor observes the real result.
wait "$bq_payload"
