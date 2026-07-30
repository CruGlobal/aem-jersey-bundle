#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"

JAVA_HOME="${JAVA_HOME:?set JAVA_HOME to the JDK under test}"
GOLDEN=tools/golden/manifest.fingerprint
WORK=core/target/smoke

echo "== building with $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
JAVA_HOME="$JAVA_HOME" mvn -s .m2/settings.xml -q clean package

# Resolve the built bundle rather than hardcoding the project version: a version
# bump would otherwise leave a stale path, and the resulting empty fingerprint
# reads as "every OSGi header disappeared" rather than "the jar moved".
JAR="$(find core/target -maxdepth 1 -name 'jersey-bundle.core-*.jar' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort | head -1)"
[ -n "$JAR" ] && [ -f "$JAR" ] || {
  echo "   ERROR: the build produced no bundle jar in core/target -- nothing to verify" >&2
  exit 1
}
echo "   built $JAR"

echo "== manifest contract"
# Materialise the fingerprint in a real file. Using <(...) here would hide a
# fingerprinting failure from set -e and make it indistinguishable from drift.
FP="$(mktemp)"
trap 'rm -f "$FP"' EXIT
./tools/manifest-fingerprint.sh "$JAR" > "$FP" || {
  echo "   ERROR: could not fingerprint $JAR -- golden left unchanged" >&2
  exit 1
}

if [ "${UPDATE_GOLDEN:-0}" = "1" ]; then
  echo "   golden will be updated after the runtime checks pass"
elif diff -u "$GOLDEN" "$FP"; then
  echo "   manifest matches golden"
else
  echo "   MANIFEST DRIFT -- the bundle's OSGi contract changed."
  echo "   If intended, re-run with UPDATE_GOLDEN=1 and commit the new golden."
  exit 1
fi

echo "== runtime smoke"
rm -rf "$WORK" && mkdir -p "$WORK/lib" "$WORK/classes"
unzip -o -q "$JAR" '*.jar' -d "$WORK/lib"

# Build the classpath in the manifest's declared Bundle-ClassPath order. This
# bundle embeds two jars carrying javax.inject.* and two carrying
# javax.annotation.*; OSGi resolves first-entry-wins, so an unordered `find`
# would test a class-resolution order the container will never use.
CP="$(unzip -p "$JAR" META-INF/MANIFEST.MF | tr -d '\r' | perl -0pe 's/\n //g' \
      | perl -ne 'if (/^Bundle-ClassPath:\s*(.*)$/) { for (split /,/, $1) {
            next if $_ eq "."; print "'"$WORK"'/lib/$_\n" } }' \
      | while IFS= read -r j; do [ -f "$j" ] && printf '%s:' "$j"; done)"
CP="${CP%:}"
[ -n "$CP" ] || { echo "   ERROR: no embedded jars extracted from $JAR" >&2; exit 1; }

"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$WORK/classes" tools/smoke/JerseyBundleSmoke.java
"$JAVA_HOME/bin/java" -cp "$WORK/classes:$CP" JerseyBundleSmoke

echo "== content package"
# The bundle is delivered to AEM inside ui.apps' content package, and
# filevault's <embeddeds> is what puts it there. failOnMissingEmbed defaults to
# false, so a silently empty install folder would otherwise ship unnoticed.
PKG="$(find ui.apps/target -maxdepth 1 -name 'jersey-bundle.ui.apps-*.zip' | sort | head -1)"
[ -n "$PKG" ] && [ -f "$PKG" ] || {
  echo "   ERROR: ui.apps produced no content package" >&2; exit 1; }
EMBEDDED="$(unzip -l "$PKG" | grep -cE 'jcr_root/apps/jersey-bundle/install/.*\.jar' || true)"
[ "$EMBEDDED" -ge 1 ] || {
  echo "   ERROR: $PKG has no bundle under jcr_root/apps/jersey-bundle/install/" >&2
  unzip -l "$PKG" | sed -n '1,25p' >&2
  exit 1
}
echo "   $PKG embeds $EMBEDDED bundle jar(s) under /apps/jersey-bundle/install"

# Only now, with the contract and both runtime checks green, is it safe to
# re-baseline. Writing via mv means a failed run can never truncate the golden.
if [ "${UPDATE_GOLDEN:-0}" = "1" ]; then
  mv "$FP" "$GOLDEN"
  trap - EXIT
  echo "== golden updated -- review the diff before committing"
fi

echo "== OK"
