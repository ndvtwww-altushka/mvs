# Local validation run

Command: `sh run-tests.sh`

Result on the packaged source snapshot: `REGRESSION RESULT pass=24 fail=0` and `ALL LOCAL CHECKS PASSED`.

This verifies the pure-Java core regression suite and compilation of the optional Android adapter against compile-only contract stubs. It does not verify the Android application build or runtime behavior on a device.
