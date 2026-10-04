#!/usr/bin/env bash
# Downloads the ROS 2 interface packages used as samples into samples/ros2_interfaces/<pkg>/{msg,srv,action}.
# Sources (shallow clones of the default branches):
#   https://github.com/ros2/common_interfaces        std_msgs, geometry_msgs, sensor_msgs, nav_msgs, ...
#   https://github.com/ros2/rcl_interfaces           builtin_interfaces, action_msgs, rcl_interfaces, ...
#   https://github.com/ros2/example_interfaces       example .msg / .srv / .action files
#   https://github.com/ros2/unique_identifier_msgs   UUID, referenced by action_msgs/GoalInfo
#   https://github.com/ros2/test_interface_files     edge cases (bounded sequences, defaults, wstring, nested
#                                                    actions); ROS builds the `test_msgs` package from it
set -eu
cd "$(dirname "$0")"
DST=samples/ros2_interfaces
rm -rf samples/_tmp "$DST"
mkdir -p samples/_tmp "$DST"
# test_interface_files before rcl_interfaces: test_msgs takes most of its files from it, so samples/sources.txt gives
# that repository as the page of the package folder, and each of the few files of rcl_interfaces/test_msgs its own line
REPOS="ros2/common_interfaces ros2/test_interface_files ros2/rcl_interfaces ros2/example_interfaces ros2/unique_identifier_msgs"
for repo in $REPOS; do
    git clone --depth 1 --quiet "https://github.com/$repo.git" "samples/_tmp/$(basename "$repo")"
done
LIST=samples/_tmp/sources # <path in samples/> <page of the original>: a package folder, or a file of a second folder of a package
: > "$LIST"
for repo in $REPOS; do
    clone="samples/_tmp/$(basename "$repo")"
    ref="$(git -C "$clone" rev-parse --abbrev-ref HEAD)" # the default branch the clone took
    # every folder named msg / srv / action belongs to the package one level up
    find "$clone" -type d \( -name msg -o -name srv -o -name action \) | LC_ALL=C sort | while read -r d; do
        pkg="$(basename "$(dirname "$d")")"
        [ "$pkg" = "test_interface_files" ] && pkg=test_msgs   # that is the package name ROS installs them under
        kind="$(basename "$d")"
        up="${d#"$clone"}"; up="${up%/*}/"                     # the package folder in the repository: / or /std_msgs/
        page="https://github.com/$repo/tree/$ref$up"
        known="$(awk -v p="ros2_interfaces/$pkg/" '$1 == p { print $2 }' "$LIST")"
        if [ -z "$known" ]; then
            echo "ros2_interfaces/$pkg/ $page" >> "$LIST"
        elif [ "$known" != "$page" ]; then
            for f in "$d"/*; do
                echo "ros2_interfaces/$pkg/$kind/${f##*/} https://github.com/$repo/blob/$ref$up$kind/${f##*/}" >> "$LIST"
            done
        fi
        mkdir -p "$DST/$pkg"
        cp -r "$d" "$DST/$pkg/"
    done
done
{
    echo "# Where every sample comes from: <path in samples/> <page of the original>; a path ending with / covers every"
    echo "# file under it. Written by fetch-samples.sh; the converter links these pages in the headers of the descriptions."
    LC_ALL=C sort "$LIST" | while read -r n url; do printf '%-56s %s\n' "$n" "$url"; done
} > samples/sources.txt
rm -rf samples/_tmp || true
echo "packages: $(ls "$DST" | wc -l)"
