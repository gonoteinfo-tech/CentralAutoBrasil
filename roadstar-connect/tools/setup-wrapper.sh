#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
download_dir="$(mktemp -d)"
trap 'rm -rf "$download_dir"' EXIT HUP INT TERM
fetch() {
    curl --fail --location --proto '=https' --tlsv1.2 --connect-timeout 10 --max-time 30 "$1" --output "$2"
}
fetch https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar "$download_dir/gradle-wrapper.jar"
fetch https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256 "$download_dir/wrapper.sha256"
fetch https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256 "$download_dir/distribution.sha256"
python3 - "$download_dir" <<'PY'
import hashlib, pathlib, re, sys
folder = pathlib.Path(sys.argv[1])
expected = folder.joinpath('wrapper.sha256').read_text().strip().split()[0]
distribution = folder.joinpath('distribution.sha256').read_text().strip().split()[0]
if not re.fullmatch(r'[0-9a-fA-F]{64}', expected) or not re.fullmatch(r'[0-9a-fA-F]{64}', distribution):
    raise SystemExit('Checksum oficial inválido; nada foi instalado.')
actual = hashlib.sha256(folder.joinpath('gradle-wrapper.jar').read_bytes()).hexdigest()
if actual.lower() != expected.lower():
    raise SystemExit('Checksum do wrapper divergiu; nada foi instalado.')
destination = pathlib.Path('gradle/wrapper')
destination.mkdir(parents=True, exist_ok=True)
properties = destination / 'gradle-wrapper.properties'
text = properties.read_text()
text = re.sub(r'^distributionSha256Sum=.*\n?', '', text, flags=re.M)
properties.write_text(text.rstrip() + '\ndistributionSha256Sum=' + distribution.lower() + '\n')
folder.joinpath('gradle-wrapper.jar').replace(destination / 'gradle-wrapper.jar')
print('Wrapper oficial Gradle 8.13 instalado e checksum verificado.')
PY
