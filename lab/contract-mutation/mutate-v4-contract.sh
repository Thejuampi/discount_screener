#!/usr/bin/env bash
#
# Mutation runner for shared/contracts/opportunity-v4.json and the constants it binds.
#
# It exists because the V4 contract has no second implementation behind it. Windows has no V4, so
# nothing outside the fixture can catch Kotlin from agreeing with itself, and the only question that
# can be asked of the fixture is: if a port got this constant wrong, would this file notice?
#
# Two rules, both learned the expensive way and both enforced below.
#
#   1. THE RUNNER IS CHECKED BEFORE THE RUN. An early version passed a malformed `executable=` to a
#      subprocess. The shell never started, the call returned non-zero, and six mutations were
#      recorded as killed by a test that had not run. `n0` is the negative control: it changes
#      nothing and must SURVIVE. A run where n0 reports KILLED is a broken runner and every other
#      line in it is void.
#
#   2. EVERY CONSTANT MOVES BOTH WAYS. A first round moved each constant in one direction only and
#      passed six fixture cases that were pinned exactly on a ramp anchor. Such a case scores the
#      same for every constant that puts the anchor at or beyond it, so it is blind on one side and
#      the one-directional round measured only the side it could see. Each constant below is
#      therefore probed above AND below its true value.
#
# Usage:  bash lab/contract-mutation/mutate-v4-contract.sh
# Expect: n0 SURVIVED, everything else KILLED. Anything else is a finding.
#
set -u
ROOT=$(git rev-parse --show-toplevel)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

ENG="$ROOT/apps/android/core/src/main/kotlin/com/discountscreener/core/engine/OpportunityEngine.kt"
TESTS=':core:test --rerun --tests *OpportunityV4ContractTest*'

probe() { # $1 label  $2 old ("" = no change)  $3 new  $4 expected: KILLED|SURVIVED
  cp "$ENG" "$WORK/orig.bak"
  if [ -n "$2" ]; then
    python - "$ENG" "$2" "$3" <<'PY'
import io, sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
with io.open(path, "r", encoding="utf-8", newline="") as fh:
    text = fh.read()
if text.count(old) != 1:
    sys.exit("anchor matched %d times, wanted exactly 1" % text.count(old))
with io.open(path, "w", encoding="utf-8", newline="") as fh:
    fh.write(text.replace(old, new))
PY
    if [ $? -ne 0 ]; then echo "$1 -> ANCHOR ERROR"; cp "$WORK/orig.bak" "$ENG"; return 1; fi
  fi
  cd "$ROOT/apps/android" || exit 1
  ./gradlew $TESTS > "$WORK/run.log" 2>&1
  local rc=$?
  cp "$WORK/orig.bak" "$ENG"
  if grep -q "No such file or directory" "$WORK/run.log"; then
    echo "$1 -> RUNNER BROKEN — the shell did not start; this run is void"
    return 1
  fi
  local got=SURVIVED
  [ "$rc" != "0" ] && got=KILLED
  if [ "$got" != "$4" ]; then
    echo "$1 -> $got   *** WRONG, wanted $4 ***"
    return 1
  fi
  echo "$1 -> $got   ok"
}

C='private const val V4_FUND_SECTOR_CHEAP_MULT = 0.7'
R='private const val V4_FUND_SECTOR_RICH_MULT = 1.5'
S='private const val V4_FUND_SHARE_COUNT_SHRINK_BPS = -300.0'
D='private const val V4_FUND_SHARE_COUNT_DILUTE_BPS = 300.0'
RL='private const val V4_FUND_SECTOR_ROE_LOWER_OFFSET_BPS = -500.0'
RU='private const val V4_FUND_SECTOR_ROE_UPPER_OFFSET_BPS = 1_500.0'

FAILED=0
probe "n0  negative control, nothing changed " ""   ""                     SURVIVED || FAILED=1
probe "n1  CHEAP_MULT  0.7 -> 0.5   (below)  " "$C" "${C%0.7}0.5"          KILLED   || FAILED=1
probe "n2  CHEAP_MULT  0.7 -> 1.0   (above)  " "$C" "${C%0.7}1.0"          KILLED   || FAILED=1
probe "n3  RICH_MULT   1.5 -> 1.0   (below)  " "$R" "${R%1.5}1.0"          KILLED   || FAILED=1
probe "n4  RICH_MULT   1.5 -> 2.0   (above)  " "$R" "${R%1.5}2.0"          KILLED   || FAILED=1
probe "n5  SHRINK_BPS -300 -> -500  (below)  " "$S" "${S%-300.0}-500.0"    KILLED   || FAILED=1
probe "n6  SHRINK_BPS -300 -> -100  (above)  " "$S" "${S%-300.0}-100.0"    KILLED   || FAILED=1
probe "n7  DILUTE_BPS  300 -> 100   (below)  " "$D" "${D%300.0}100.0"      KILLED   || FAILED=1
probe "n8  DILUTE_BPS  300 -> 600   (above)  " "$D" "${D%300.0}600.0"      KILLED   || FAILED=1
probe "n9  ROE_LOWER  -500 -> -1000 (below)  " "$RL" "${RL%-500.0}-1000.0" KILLED   || FAILED=1
probe "n10 ROE_LOWER  -500 -> -100  (above)  " "$RL" "${RL%-500.0}-100.0"  KILLED   || FAILED=1
probe "n11 ROE_UPPER  1500 -> 800   (below)  " "$RU" "${RU%1_500.0}800.0"  KILLED   || FAILED=1
probe "n12 ROE_UPPER  1500 -> 3000  (above)  " "$RU" "${RU%1_500.0}3_000.0" KILLED  || FAILED=1

echo
if [ "$FAILED" = "0" ]; then
  echo "All 13 probes behaved as required: the negative control survived and every constant is"
  echo "separated on both sides of its true value."
else
  echo "At least one probe misbehaved. Read the lines above; do not treat the contract as verified."
fi
exit "$FAILED"
