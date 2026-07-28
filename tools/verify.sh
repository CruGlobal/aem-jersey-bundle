#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:?set JAVA_HOME to the JDK under test}"
JAR=core/target/jersey-bundle.core-3.0.0-SNAPSHOT.jar
GOLDEN=tools/golden/manifest.fingerprint
WORK=core/target/smoke

echo "== building with $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
JAVA_HOME="$JAVA_HOME" mvn -s .m2/settings.xml -q clean install

echo "== manifest contract"
if [ "${UPDATE_GOLDEN:-0}" = "1" ]; then
  ./tools/manifest-fingerprint.sh "$JAR" > "$GOLDEN"
  echo "   golden updated -- review the diff before committing"
else
  if diff -u "$GOLDEN" <(./tools/manifest-fingerprint.sh "$JAR"); then
    echo "   manifest matches golden"
  else
    echo "   MANIFEST DRIFT -- the bundle's OSGi contract changed."
    echo "   If intended, re-run with UPDATE_GOLDEN=1 and commit the new golden."
    exit 1
  fi
fi

echo "== runtime smoke"
rm -rf "$WORK" && mkdir -p "$WORK/lib" "$WORK/classes"
( cd "$WORK/lib" && unzip -o -q "../../../../$JAR" '*.jar' )
CP="$(find "$WORK/lib" -name '*.jar' | tr '\n' ':')"
"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$WORK/classes" tools/smoke/JerseyBundleSmoke.java
"$JAVA_HOME/bin/java" -cp "$WORK/classes:$CP" JerseyBundleSmoke

echo "== OK"
