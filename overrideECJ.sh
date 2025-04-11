#!/bin/bash

set -euo pipefail

function overrideECJ() {

  # Ask for ECJ release tag
  read -p "Enter ECJ release tag (e.g., R4_33): " ecj_tag

  module_and_file_path_to_override=(
   "org.eclipse.jdt.core.compiler.batch/src:org/eclipse/jdt/internal/compiler/problem/ProblemReporter.java"
   "org.eclipse.jdt.core.compiler.batch/src:org/eclipse/jdt/internal/compiler/problem/ProblemHandler.java"
   "org.eclipse.jdt.core/dom:org/eclipse/jdt/core/dom/ASTParser.java"
  )

  for entry in "${module_and_file_path_to_override[@]}"; do
    module="${entry%%:*}"   # Extract the part before ':'
    java_file="${entry##*:}" # Extract the part after ':'
    echo "Overriding '${java_file}' from module '${module}'"

    # Define the base URL for fetching the problem files
    url="https://raw.githubusercontent.com/eclipse-jdt/eclipse.jdt.core/refs/tags/${ecj_tag}/${module}/${java_file}"

    echo "Fetching '${url}' into 'src/main/java/${java_file}'"

    mkdir -p "$(dirname "src/main/java/${java_file}")"
    if ! curl -sSLf -o "src/main/java/${java_file}" "${url}"; then
      echo "Error: Failed to fetch"
      return 1
    else
      echo "Files fetched successfully."
    fi
  done

  # Show the last 15 commits from history
  echo "Find the last Update JDT core commit about 'Override':"
  echo "----------------------------------------"
  git log --oneline -15 | grep -A 15 -B 15 --color Override

  # Ask the user to select two commits to base the patch on
  read -p "Enter 'Override' JDT core commit hash: " override_commit

  # Try to apply the patch
  echo "Applying patch..."
  if ! git apply --reject <(git format-patch --stdout -1 "$override_commit"); then
    echo "Error: Failed to apply the patch."
    exit 1
  fi
  echo "Patch applied successfully."
}

overrideECJ "$@"

exit 0
