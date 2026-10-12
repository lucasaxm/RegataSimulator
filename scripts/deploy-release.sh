#!/usr/bin/env bash
set -euo pipefail
umask 077

# Linux/systemd user service. Never sources runtime secrets or reads application logs.
[[ $# -eq 7 ]] || { echo 'Expected root, release id, JAR checksum, schema, acknowledgment, unit and launcher checksums' >&2; exit 2; }
root=$1 id=$2 checksum=$3 schema=$4 acknowledgment=$5 unit_checksum=$6 launcher_checksum=$7
[[ "$root" =~ ^/[a-zA-Z0-9_./-]+$ && "$root" != *'/../'* && "$root" != *'/./'* && "$root" != / && "$id" =~ ^[a-f0-9]{40}$ && "$checksum" =~ ^[a-f0-9]{64}$ && "$unit_checksum" =~ ^[a-f0-9]{64}$ && "$launcher_checksum" =~ ^[a-f0-9]{64}$ && "$schema" =~ ^[1-9][0-9]{0,3}$ && "$acknowledgment" == artifact-and-unit-schema-compatible ]] || { echo 'Unsafe deployment arguments' >&2; exit 2; }
readiness_attempts=${READINESS_ATTEMPTS:-120}
[[ "$readiness_attempts" =~ ^[1-9][0-9]{0,2}$ ]] && (( readiness_attempts <= 300 )) || { echo 'Readiness attempts must be between 1 and 300' >&2; exit 2; }
for directory in "$root" "$root/releases" "$root/incoming"; do
    [[ -d "$directory" && ! -L "$directory" ]] || { echo 'Operator-created private deployment directories required' >&2; exit 2; }
    part=$directory
    while [[ "$part" != / ]]; do
        [[ ! -L "$part" ]] || { echo 'Symlinked deployment parent forbidden' >&2; exit 2; }
        part=$(dirname "$part")
    done
done
[[ "$(cd "$root" && pwd -P)" == "$root" ]] || { echo 'Canonical root required' >&2; exit 2; }
[[ ! -L "$root/.deploy.lock" ]] || { echo 'Symlinked deployment lock refused' >&2; exit 2; }
exec 9>"$root/.deploy.lock"
flock -n 9 || { echo 'Another deployment is active' >&2; exit 2; }
incoming="$root/incoming/$id"
release="$root/releases/$id"
[[ -d "$incoming" && ! -L "$incoming" && -f "$incoming/app.jar" && ! -L "$incoming/app.jar" && ! -e "$release" ]] || { echo 'New regular staged artifact required' >&2; exit 2; }
verify_file() {
    [[ -f "$1" && ! -L "$1" && "$(shasum -a 256 "$1" | cut -d ' ' -f 1)" == "$2" ]]
}
verify_file "$incoming/app.jar" "$checksum" && verify_file "$incoming/regatasimulator.service" "$unit_checksum" && verify_file "$incoming/run-service.sh" "$launcher_checksum" || { echo 'Artifact/unit/launcher checksum mismatch or absent regular file' >&2; exit 2; }
bash -n "$incoming/run-service.sh" || { echo 'Invalid service launcher' >&2; exit 2; }

unit=regatasimulator.service
unit_dir="$HOME/.config/systemd/user"
unit_file="$unit_dir/$unit"
dropins="$unit_dir/$unit.d"
for directory in "$unit_dir" "$HOME/.config/regata"; do
    [[ -d "$directory" && ! -L "$directory" && "$(cd "$directory" && pwd -P)" == "$directory" ]] || { echo 'Canonical operator-owned service/config directories required' >&2; exit 2; }
done
[[ -f "$unit_file" && ! -L "$unit_file" && -f "$HOME/.config/regata/runtime.env" && ! -L "$HOME/.config/regata/runtime.env" ]] || { echo 'Existing regular unit and private runtime environment required' >&2; exit 2; }
old_unit_checksum=$(shasum -a 256 "$unit_file" | cut -d ' ' -f 1)
runtime_checksum=$(shasum -a 256 "$HOME/.config/regata/runtime.env" | cut -d ' ' -f 1)
dropin_hashes() {
    if [[ -e "$dropins" || -L "$dropins" ]]; then
        [[ -d "$dropins" && ! -L "$dropins" ]] || return 1
        for file in "$dropins"/*.conf; do
            [[ -e "$file" || -L "$file" ]] || continue
            [[ -f "$file" && ! -L "$file" ]] || return 1
            # Host settings may change environment/resources, never replace shared startup.
            if grep -Eq '^[[:space:]]*(ExecStart|ExecStartPre|ExecStartPost|ExecCondition|EnvironmentFile|WorkingDirectory)[[:space:]]*=' "$file"; then return 1; fi
            shasum -a 256 "$file" || return 1
        done
    fi
}
old_dropin_hashes=$(dropin_hashes) || { echo 'Unsupported host drop-in; preserve and review before deployment' >&2; exit 2; }
effective_dropins=$(timeout 5 systemctl --user show "$unit" --property=DropInPaths --value)
for file in $effective_dropins; do
    [[ "$file" == "$dropins/"*.conf ]] || { echo 'Unmanaged system/runtime drop-in requires operator review' >&2; exit 2; }
done

java_bin=${JAVA_BIN:-/usr/bin/java}
configured_environment=$(timeout 5 systemctl --user show "$unit" --property=Environment --value)
configured_java=/usr/bin/java
for variable in $configured_environment; do
    if [[ "$variable" == REGATA_JAVA_BIN=* ]]; then configured_java=${variable#REGATA_JAVA_BIN=}; fi
done
[[ "$configured_java" == "$java_bin" ]] || { echo 'Deployment Java must match host REGATA_JAVA_BIN setting' >&2; exit 2; }
version=$("$java_bin" -version 2>&1 | head -n 1)
[[ "$version" =~ \"21\.0\.([0-9]+)(\.([0-9]+))? ]] || { echo 'Java 21 required' >&2; exit 2; }
patch=${BASH_REMATCH[1]} subpatch=${BASH_REMATCH[3]:-0}
(( patch > 12 || patch == 12 && subpatch >= 1 )) || { echo 'Patched Java 21.0.12.1 or newer required' >&2; exit 2; }

previous=''
previous_checksum=''
if [[ -e "$root/current" || -L "$root/current" ]]; then
    [[ -L "$root/current" ]] || { echo 'Current release must be a managed symlink' >&2; exit 2; }
    previous=$(readlink "$root/current")
    [[ "$previous" =~ ^releases/[a-f0-9]{40}$ && -f "$root/$previous/app.jar" && ! -L "$root/$previous/app.jar" && -f "$root/$previous/schema-version" && ! -L "$root/$previous/schema-version" && ! -L "$root/$previous" ]] || { echo 'Unmanaged previous release refused' >&2; exit 2; }
    [[ "$(cat "$root/$previous/schema-version")" == "$schema" ]] || { echo 'Schema change requires a separate frozen migration and recovery procedure' >&2; exit 2; }
    previous_checksum=$(shasum -a 256 "$root/$previous/app.jar" | cut -d ' ' -f 1)
fi

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
    for ((i=0;i<readiness_attempts;i++)); do
        pid=$(timeout 5 systemctl --user show "$unit" --property=MainPID --value) || return 1
        state=$(timeout 5 systemctl --user show "$unit" --property=ActiveState --value) || return 1
        if [[ "$pid" =~ ^[1-9][0-9]*$ && "$state" == active ]] && curl --fail --silent --show-error --connect-timeout 2 --max-time 3 --noproxy '*' "$url" 2>/dev/null | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then return 0; fi
        sleep 1
    done
    return 1
}
switch_to() {
    local temporary
    temporary=$(mktemp -d "$root/.switch-$id-XXXXXX") || return 1
    ln -s "$1" "$temporary/current" && mv -Tf "$temporary/current" "$root/current" || return 1
    rmdir "$temporary"
}
install_unit() {
    local expected
    [[ -f "$1" && ! -L "$1" ]] || return 1
    expected=$(shasum -a 256 "$1" | cut -d ' ' -f 1) || return 1
    [[ ! -L "$unit_dir/.regata-unit-$id" ]] || return 1
    cp "$1" "$unit_dir/.regata-unit-$id" || return 1
    chmod 600 "$unit_dir/.regata-unit-$id" || return 1
    verify_file "$unit_dir/.regata-unit-$id" "$expected" || return 1
    mv -Tf "$unit_dir/.regata-unit-$id" "$unit_file" || return 1
    verify_file "$unit_file" "$expected"
}
host_settings_unchanged() {
    local current_hashes
    current_hashes=$(dropin_hashes) || return 1
    [[ "$current_hashes" == "$old_dropin_hashes" ]] && verify_file "$HOME/.config/regata/runtime.env" "$runtime_checksum"
}

mkdir -m 700 "$release"
cp "$incoming/app.jar" "$release/app.jar"
cp "$incoming/run-service.sh" "$release/run-service.sh"
chmod 600 "$release/app.jar"
chmod 700 "$release/run-service.sh"
[[ -x "$release/run-service.sh" ]] || { echo 'Launcher must be executable' >&2; exit 2; }
verify_file "$release/app.jar" "$checksum" && verify_file "$release/run-service.sh" "$launcher_checksum" || { echo 'Copied artifact checksum mismatch' >&2; exit 2; }
printf '%s\n' "$schema" > "$release/schema-version"
sed -e "s|^WorkingDirectory=.*|WorkingDirectory=$root|" -e "s|^ExecStart=.*|ExecStart=$root/current/run-service.sh|" -e "s|^ReadWritePaths=%h/.local/share/regata |ReadWritePaths=$root |" "$incoming/$unit" > "$release/$unit"
cp "$unit_file" "$release/previous-unit.service"
verify_file "$release/previous-unit.service" "$old_unit_checksum" || { echo 'Previous unit snapshot mismatch; deployment refused' >&2; exit 2; }
mkdir -m 700 "$release/verify"
sed "s|^ExecStart=.*|ExecStart=$release/run-service.sh|" "$release/$unit" > "$release/verify/$unit"
if [[ -d "$dropins" ]]; then cp -R "$dropins" "$release/verify/$unit.d"; fi
timeout 15 systemd-analyze --user verify "$release/verify/$unit" > "$release/unit-verify.log" 2>&1 || { echo 'Candidate unit verification failed; current release unchanged' >&2; exit 2; }
verify_file "$unit_file" "$old_unit_checksum" && host_settings_unchanged || { echo 'Operator settings changed during preflight; deployment refused' >&2; exit 2; }
stop_writers || { echo 'Writer stop could not be confirmed; current release/unit unchanged' >&2; exit 1; }
changed=false
rollback() {
    result=$?
    trap - EXIT INT TERM
    if [[ "$changed" == true && "$result" -ne 0 ]]; then
        if ! stop_writers; then echo 'Rollback blocked: writers not confirmed stopped; manual recovery required' >&2; exit 1; fi
        if ! host_settings_unchanged; then echo 'Rollback blocked: host settings changed; preserve artifacts for manual recovery' >&2; exit 1; fi
        if ! verify_file "$release/previous-unit.service" "$old_unit_checksum" || ! install_unit "$release/previous-unit.service"; then echo 'Previous unit restore failed; writers stopped; manual recovery required' >&2; exit 1; fi
        if [[ -n "$previous" ]]; then
            if ! verify_file "$root/$previous/app.jar" "$previous_checksum"; then echo 'Previous artifact changed; writers stopped; manual recovery required' >&2; exit 1; fi
            if ! switch_to "$previous" || ! timeout 15 systemctl --user daemon-reload; then echo 'Unit/artifact fallback selection failed; writers stopped; manual recovery required' >&2; exit 1; fi
            if ! timeout 15 systemctl --user start "$unit" || ! ready; then
                if stop_writers; then echo 'Previous unit/artifact readiness failed; writers stopped; manual recovery required' >&2
                else echo 'Previous unit/artifact readiness failed and writer stop unconfirmed; manual recovery required' >&2; fi
            else echo 'Failed deployment; previous unit and artifact restored (data was not reverted)' >&2; fi
        else
            rm -f -- "$root/current"
            timeout 15 systemctl --user daemon-reload || { echo 'Original unit reload failed; writers stopped; manual recovery required' >&2; exit 1; }
            echo 'First deployment failed; original unit restored, writers stopped and no release selected' >&2
        fi
    fi
    exit "$result"
}
trap rollback EXIT
trap 'exit 1' INT TERM
verify_file "$unit_file" "$old_unit_checksum" && host_settings_unchanged || { echo 'Operator settings changed during writer stop; deployment refused' >&2; exit 1; }
changed=true
install_unit "$release/$unit"
switch_to "releases/$id"
timeout 15 systemctl --user daemon-reload
host_settings_unchanged || { echo 'Host settings changed before start; manual recovery required' >&2; exit 1; }
timeout 15 systemctl --user start "$unit" || { echo 'Service start failed' >&2; exit 1; }
ready || { echo 'Readiness deadline exceeded' >&2; exit 1; }
if [[ -n "$previous" ]]; then printf '%s\n' "$previous" > "$root/previous"; fi
echo 'Unit and release readiness verified; rollback snapshots retained'