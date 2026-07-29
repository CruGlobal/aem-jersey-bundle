#!/usr/bin/env bash
set -euo pipefail
JAR="${1:?usage: manifest-fingerprint.sh <bundle.jar>}"

unzip -p "$JAR" META-INF/MANIFEST.MF \
  | tr -d '\r' \
  | perl -0pe 's/\n //g' \
  | grep -vE '^(Bnd-LastModified|Build-Jdk|Build-Jdk-Spec|Built-By|Created-By|Tool):' \
  | grep -vE '^\s*$' \
  | while IFS= read -r line; do
      key="${line%%:*}"
      val="${line#*: }"
      case "$key" in
        Export-Package|Import-Package|Bundle-ClassPath|Embedded-Artifacts|Private-Package)
          printf '%s: %s\n' "$key" "$(printf '%s' "$val" | tr ',' '\n' | sort | paste -sd, -)"
          ;;
        *) printf '%s\n' "$line" ;;
      esac
    done \
  | sort
