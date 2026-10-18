#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
checks_dir="$(mktemp -d)"
trap 'rm -rf "$checks_dir"' EXIT HUP INT TERM
java com.sun.tools.javac.Main -source 17 -target 17 -Xlint:-options -d "$checks_dir" \
  mirror-core/src/main/java/com/roadconnect/mirror/MirrorWire.java tools/MirrorChecks.java
java -cp "$checks_dir" MirrorChecks
