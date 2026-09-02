# Gated launcher for one supervised provider process.
#
# Invoked as: /bin/bash -c <this script> bytequay-launch <gate> <identity> <parent-pid> <payload argv...>
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
bq_parent=$3      # PID of the owning JVM
shift 3           # "$@" is now exactly the payload argv, with no shell interpretation

# Job control is enabled for one side effect only: under `set -m` a background job becomes a
# process-group leader, so the subshell below gets pgid == pid. It is not used to manage jobs.
# This needs bash: dash, which is /bin/sh on Debian and Ubuntu, refuses job control when it has
# no controlling terminal, which is exactly how a backend or a CI runner starts this script.
set -m

{
  # Wait for the supervisor to decide. Three ways out: the gate opens, the gate cancels, or the
  # owning backend disappears. The bound caps a hung supervisor at roughly 60 seconds.
  bq_waits=0
  while [ ! -f "$bq_gate" ]; do
    kill -0 "$bq_parent" 2>/dev/null || exit 70
    bq_waits=$((bq_waits+1))
    if [ "$bq_waits" -gt 600 ]; then exit 70; fi
    if [ "$bq_waits" -lt 20 ]; then sleep 0.01; else sleep 0.1; fi
  done
  read -r bq_release < "$bq_gate"
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
