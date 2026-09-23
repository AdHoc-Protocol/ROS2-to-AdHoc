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
for repo in ros2/common_interfaces ros2/rcl_interfaces ros2/example_interfaces ros2/unique_identifier_msgs ros2/test_interface_files; do
    git clone --depth 1 --quiet "https://github.com/$repo.git" "samples/_tmp/$(basename "$repo")"
done
# every folder named msg / srv / action belongs to the package one level up
find samples/_tmp -type d \( -name msg -o -name srv -o -name action \) | while read -r d; do
    pkg="$(basename "$(dirname "$d")")"
    [ "$pkg" = "test_interface_files" ] && pkg=test_msgs   # that is the package name ROS installs them under
    mkdir -p "$DST/$pkg"
    cp -r "$d" "$DST/$pkg/"
done
rm -rf samples/_tmp || true
echo "packages: $(ls "$DST" | wc -l)"
