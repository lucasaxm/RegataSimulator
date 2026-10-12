#!/usr/bin/env bash
set -euo pipefail

# systemd supplies private runtime settings; never source or print them here.
java_bin=${REGATA_JAVA_BIN:-/usr/bin/java}
heap_min=${REGATA_JAVA_HEAP_MIN:-128m}
heap_max=${REGATA_JAVA_HEAP_MAX:-512m}
[[ "$java_bin" =~ ^/[a-zA-Z0-9_./+-]+$ && -x "$java_bin" ]] || { echo 'Valid Java executable required' >&2; exit 2; }
[[ "$heap_min" =~ ^[1-9][0-9]{0,4}[mMgG]$ && "$heap_max" =~ ^[1-9][0-9]{0,4}[mMgG]$ ]] || { echo 'Valid heap sizes required' >&2; exit 2; }
release=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
[[ -f "$release/app.jar" && ! -L "$release/app.jar" ]] || { echo 'Release artifact required' >&2; exit 2; }
exec "$java_bin" -Djava.net.preferIPv4Stack=true "-Xms$heap_min" "-Xmx$heap_max" -jar "$release/app.jar"