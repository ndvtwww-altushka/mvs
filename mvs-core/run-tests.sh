#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
find src test -name '*.java' | sort > sources.txt
echo "== compile =="
javac -encoding UTF-8 -d out @sources.txt
echo "== RegressionTest =="
java -cp out app.mvs.core.RegressionTest
