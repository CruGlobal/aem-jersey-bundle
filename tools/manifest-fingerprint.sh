#!/usr/bin/env bash
set -euo pipefail

# Byte-stable output regardless of the caller's locale. Without this, sort
# collates punctuation differently under en_US.UTF-8 than under C/POSIX, so a
# golden generated on a developer laptop spuriously "drifts" in a CI container.
export LC_ALL=C

JAR="${1:?usage: manifest-fingerprint.sh <bundle.jar>}"
[ -f "$JAR" ] || { echo "manifest-fingerprint.sh: no such jar: $JAR" >&2; exit 1; }
JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
  else echo "manifest-fingerprint.sh: need sha256sum or shasum" >&2; exit 1
  fi
}

MANIFEST="$(unzip -p "$JAR" META-INF/MANIFEST.MF | tr -d '\r' | perl -0pe 's/\n //g')"

# bnd derives the export version of unversioned packages from Bundle-Version.
# Normalising it keeps a routine release bump from reading as a contract change.
PV="$(printf '%s\n' "$MANIFEST" \
      | perl -ne 'print "$1\n" if /^Bundle-Version:\s*(\d+\.\d+\.\d+)/' | head -1)"

printf '%s\n' "$MANIFEST" \
  | grep -vE '^(Bnd-LastModified|Build-Jdk|Build-Jdk-Spec|Built-By|Created-By|Tool|Bundle-Version):' \
  | grep -vE '^[[:space:]]*$' \
  | PV="$PV" perl -e '
      my $pv = $ENV{PV};
      # Split on top-level commas only. OSGi clause values legally contain
      # commas inside quoted directives such as uses:="a,b,c"; splitting on
      # every comma shreds those clauses and interleaves fragments from
      # unrelated packages once sorted.
      sub clauses { return split /,(?=(?:[^"]*"[^"]*")*[^"]*$)/, $_[0] }
      while (my $line = <STDIN>) {
        chomp $line;
        next unless $line =~ /^([^:]+):[ ]?(.*)$/;
        my ($key, $val) = ($1, $2);
        $val =~ s/version="\Q$pv\E"/version="\@PROJECT_VERSION\@"/g if $pv ne "";
        if ($key =~ /^(Export-Package|Import-Package|Private-Package)$/) {
          # One clause per line so a real change is a one-line diff a human
          # can actually review, as the README asks them to.
          print "$key: $_\n" for sort(clauses($val));
        } elsif ($key =~ /^(Bundle-ClassPath|Embedded-Artifacts)$/) {
          # ORDER IS SIGNIFICANT: this bundle embeds two jars carrying
          # javax.inject.* and two carrying javax.annotation.*, and OSGi
          # resolves Bundle-ClassPath first-entry-wins. Sorting these would
          # hide a reordering that changes which classes win.
          my @c = clauses($val);
          printf "%s[%02d]: %s\n", $key, $_, $c[$_] for 0..$#c;
        } else {
          print "$key: $val\n";
        }
      }
    ' \
  | sort

# The manifest names the embedded jars but not their bytes, so an upstream
# artifact swapped at the same coordinates would be invisible. Hash them.
TMPD="$(mktemp -d)"
trap 'rm -rf "$TMPD"' EXIT
( cd "$TMPD" && unzip -o -q "$JAR" '*.jar' )
find "$TMPD" -name '*.jar' | sort | while IFS= read -r j; do
  printf 'Embedded-Sha256: %s %s\n' "$(basename "$j")" "$(sha256_of "$j")"
done | sort
