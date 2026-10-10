#!/usr/bin/env bash
set -euo pipefail
umask 077

# Linux/systemd user service. Never sources runtime secrets or reads application logs.
[[ $# -eq 5 ]] || { echo 'Expected root, release id, SHA256, schema version and rollback acknowledgment' >&2; exit 2; }
root=$1 id=$2 checksum=$3 schema=$4 acknowledgment=$5
[[ "$root" =~ ^/[a-zA-Z0-9_./-]+$ && "$root" != *'/../'* && "$root" != *'/./'* && "$root" != / && "$id" =~ ^[a-f0-9]{40}$ && "$checksum" =~ ^[a-f0-9]{64}$ && "$schema" =~ ^[1-9][0-9]{0,3}$ && "$acknowledgment" == artifact-only-schema-compatible ]] || { echo 'Unsafe deployment arguments' >&2; exit 2; }
for directory in "$root" "$root/releases" "$root/incoming"; do
    [[ -d "$directory" && ! -L "$directory" ]] || { echo 'Operator-created private deployment directories required' >&2; exit 2; }
    part=$directory
    while [[ "$part" != / ]]; do
        [[ ! -L "$part" ]] || { echo 'Symlinked deployment parent forbidden' >&2; exit 2; }
        part=$(dirname "$part")
    done
done
[[ "$(cd "$root" && pwd -P)" == "$root" ]] || { echo 'Canonical root required' >&2; exit 2; }
incoming="$root/incoming/$id"
release="$root/releases/$id"
[[ -d "$incoming" && ! -L "$incoming" && -f "$incoming/app.jar" && ! -L "$incoming/app.jar" && ! -e "$release" ]] || { echo 'New regular staged artifact required' >&2; exit 2; }
[[ "$(shasum -a 256 "$incoming/app.jar" | cut -d ' ' -f 1)" == "$checksum" ]] || { echo 'Artifact checksum mismatch' >&2; exit 2; }

java_bin=${JAVA_BIN:-/usr/bin/java}
version=$("$java_bin" -version 2>&1 | head -n 1)
[[ "$version" =~ \"21\.0\.([0-9]+)(\.([0-9]+))? ]] || { echo 'Java 21 required' >&2; exit 2; }
patch=${BASH_REMATCH[1]} subpatch=${BASH_REMATCH[3]:-0}
(( patch > 12 || patch == 12 && subpatch >= 1 )) || { echo 'Patched Java 21.0.12.1 or newer required' >&2; exit 2; }

previous=''
if [[ -e "$root/current" || -L "$root/current" ]]; then
    [[ -L "$root/current" ]] || { echo 'Current release must be a managed symlink' >&2; exit 2; }
    previous=$(readlink "$root/current")
    [[ "$previous" =~ ^releases/[a-f0-9]{40}$ && -f "$root/$previous/schema-version" && ! -L "$root/$previous/schema-version" && ! -L "$root/$previous" ]] || { echo 'Unmanaged previous release refused' >&2; exit 2; }
    [[ "$(cat "$root/$previous/schema-version")" == "$schema" ]] || { echo 'Schema change requires a separate frozen migration and recovery procedure' >&2; exit 2; }
fi

unit=regatasimulator.service
url=http://127.0.0.1:8080/actuator/health/readiness
stop_writers() {
    timeout 50 systemctl --user stop "$unit" || return 1
    for ((i=0;i<20;i++)); do
        pid=$(timeout 5 systemctl --user show "$unit" --property=MainPID --value) || return 1
        state=$(timeout 5 systemctl --user show "$unit" --property=ActiveState --value) || return 1
        if [[ "$pid" == 0 && ( "$state" == inactive || "$state" == failed ) ]]; then return 0; fi
        sleep 1
    done
    return 1
}
ready() {
    for ((i=0;i<30;i++)); do
        if curl --fail --silent --show-error --connect-timeout 2 --max-time 3 --noproxy '*' "$url" 2>/dev/null | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then return 0; fi
        sleep 1
    done
    return 1
}
switch_to() {
    ln -s "$1" "$root/.current-$id"
    mv -Tf "$root/.current-$id" "$root/current"
}

mkdir -m 700 "$release"
cp "$incoming/app.jar" "$release/app.jar"
chmod 600 "$release/app.jar"
printf '%s\n' "$schema" > "$release/schema-version"
stop_writers || { echo 'Writer stop could not be confirmed; current release unchanged' >&2; exit 1; }
switched=false
rollback() {
    result=$?
    trap - EXIT INT TERM
    if [[ "$switched" == true && "$result" -ne 0 ]]; then
        if ! stop_writers; then echo 'Rollback blocked: writers not confirmed stopped; manual recovery required' >&2; exit 1; fi
        if [[ -n "$previous" ]]; then
            switch_to "$previous"
            if ! timeout 15 systemctl --user start "$unit" || ! ready; then
                echo 'Previous artifact selected but readiness failed; manual recovery required' >&2
            else echo 'Failed deployment; previous artifact restored (data was not reverted)' >&2; fi
        else
            rm -- "$root/current"
            echo 'First deployment failed; writers stopped and no release selected' >&2
        fi
    fi
    exit "$result"
}
trap rollback EXIT
trap 'exit 1' INT TERM
switch_to "releases/$id"
switched=true
timeout 15 systemctl --user start "$unit"
ready || { echo 'Readiness deadline exceeded' >&2; exit 1; }
if [[ -n "$previous" ]]; then printf '%s\n' "$previous" > "$root/previous"; fi
echo 'Release readiness verified; artifact rollback marker retained'