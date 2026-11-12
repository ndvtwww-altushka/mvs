#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
OUT="${TMPDIR:-/tmp}/mvs-core-test-out-$$"
ADAPTER_OUT="${TMPDIR:-/tmp}/mvs-core-adapter-out-$$"
trap 'rm -rf "$OUT" "$ADAPTER_OUT"' EXIT HUP INT TERM
mkdir -p "$OUT" "$ADAPTER_OUT"
find src test -name '*.java' -print | sort > "$OUT/sources.txt"
echo "== Compile core + regression tests (Java 17 API) =="
javac --release 17 -Xlint:all -encoding UTF-8 -d "$OUT" @"$OUT/sources.txt"
echo "== Run RegressionTest =="
java -cp "$OUT" app.mvs.core.RegressionTest
echo "== Compile Android adapter against compile-only contract stubs =="
find src adapter-stubs -name '*.java' -print | sort > "$ADAPTER_OUT/sources.txt"
printf '%s\n' android/org/telegram/tgnet/MtprotoAdapter.java android/org/telegram/messenger/MvsCoreHost.java >> "$ADAPTER_OUT/sources.txt"
javac --release 17 -Xlint:all -encoding UTF-8 -d "$ADAPTER_OUT" @"$ADAPTER_OUT/sources.txt"
echo "ALL LOCAL CHECKS PASSED"
