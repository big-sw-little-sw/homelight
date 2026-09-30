#!/bin/bash
# Runs inside the builder container.
# Mounts: /src = worktree (ro), /m2ro = host ~/.m2/repository (ro), /m2 = writable volume, /out = output dir.
# Env: NATIVE_IMAGE_OPTIONS = extra native-image flags (e.g. --static-nolibc); OUTNAME = output binary name.
set -u
rm -rf /work && mkdir /work
tar -C /src --exclude=./target --exclude=./.git --exclude=./.idea -cf - . | tar -C /work -xf -
cd /work
start=$(date +%s)
mvn -B -Dmaven.repo.local=/m2 -Dmaven.repo.local.tail=/m2ro -Pnative -DskipTests package > /out/$OUTNAME.build.log 2>&1
rc=$?
end=$(date +%s)
peak=$(cat /sys/fs/cgroup/memory.peak 2>/dev/null || cat /sys/fs/cgroup/memory/memory.max_usage_in_bytes 2>/dev/null)
echo "EXIT=$rc WALL=$((end-start))s CGROUP_PEAK_BYTES=$peak NATIVE_IMAGE_OPTIONS=${NATIVE_IMAGE_OPTIONS:-}" | tee /out/$OUTNAME.build.summary
[ $rc -eq 0 ] && cp target/homelight /out/$OUTNAME
chown -R ${HOST_UID:-501}:${HOST_GID:-20} /out
