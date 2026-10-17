#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
checks_dir="$(mktemp -d)"
trap 'rm -rf "$checks_dir"' EXIT HUP INT TERM
java com.sun.tools.javac.Main -source 17 -target 17 -Xlint:-options -d "$checks_dir" \
  app/src/main/java/com/roadconnect/app/data/UrlPolicy.java \
  app/src/main/java/com/roadconnect/app/data/Station.java tools/CoreChecks.java
java -cp "$checks_dir" CoreChecks
