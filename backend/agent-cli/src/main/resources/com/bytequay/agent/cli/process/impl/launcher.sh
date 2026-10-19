# Gated launcher for one supervised provider process.
# ProcessSessionLauncher creates a session and execs this shell at the same PID. The shell
# publishes that identity before waiting for the durable gate, then execs the provider in-place.
# Thus its PID, session ID, and process-group ID remain the recorded identity across both execs.

bq_gate=$1
bq_identity=$2
shift 2

# Use the system publisher rather than resolving a helper from the provider's PATH.
printf '%s\n' "$$" > "$bq_identity.part"
/bin/mv "$bq_identity.part" "$bq_identity"

# EOF on backend death or the timeout exits without running the payload. Exit 70 means unlaunched.
IFS= read -r -t 60 bq_release || exit 70
if [ "$bq_release" != GO ]; then exit 70; fi
read -r bq_release < "$bq_gate" || exit 70
if [ "$bq_release" != GO ]; then exit 70; fi
exec "$@"
