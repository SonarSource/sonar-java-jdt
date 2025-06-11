#!/bin/bash

set -euo pipefail

function overrideECJ() {
  local jira_ticket="${1:-}"
  local ecj_tag="${2:-}"

  if [[ -z "${jira_ticket}" ]]; then
    read -p "Enter Jira ticket used to prefix commit message (e.g., SONARJAVA-5615): " jira_ticket
  fi

  if [[ -z "${ecj_tag}" ]]; then
    read -p "Enter ECJ release tag (e.g., R4_33): " ecj_tag
  fi

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

  local JDT_CORE_VERSION
  JDT_CORE_VERSION="$(curl -sfL "https://raw.githubusercontent.com/eclipse-jdt/eclipse.jdt.core/refs/tags/${ecj_tag}/org.eclipse.jdt.core/pom.xml" -o - | sed -n -E 's/^  <version>(.*)-SNAPSHOT<\/version>.*/\1/p')"

  echo "The JDT core version matching the tag '${ecj_tag}' is: ${JDT_CORE_VERSION}"

  echo "--- Updating the dependency for org.eclipse.jdt:org.eclipse.jdt.core in the pom.xml file with the version ${JDT_CORE_VERSION} ---"
  local sed_expression="N;s/^(.*<artifactId>org\.eclipse\.jdt\.core<\/artifactId>[^<]*<version>)[^<]*(<\/version>.*)/\1${JDT_CORE_VERSION}\2/"
  if [[ "$(uname -s)" == "Darwin" ]]; then
    sed -i '' -E "${sed_expression}" pom.xml
  else
    sed -i -E "${sed_expression}" pom.xml
  fi

  grep -B 2 -A 2 -E "<artifactId>org\.eclipse\.jdt\.core<\/artifactId>" pom.xml

  local first_commit_message="${jira_ticket} Update JDT core to ${JDT_CORE_VERSION} from ${ecj_tag}"
  echo "--- Create commit: ${first_commit_message} ---"
  git add src/main/java
  git add pom.xml
  git commit -m "${first_commit_message}"

  # Show the last 15 commits from history
  echo "Find the last Update JDT core commit about 'Override':"
  echo "----------------------------------------"
  git log --oneline -15 | grep -A 15 -B 15 --color Override

  # Ask the user to select two commits to base the patch on
  read -p "Enter 'Override' JDT core commit hash: " override_commit

  # Try to apply the patch
  echo "Applying patch..."
  if ! git apply --reject <(git format-patch --stdout -1 "$override_commit" -- $(find src/main/java -type f)); then
    echo "Error: Failed to apply the patch."
    exit 1
  fi
  echo "Patch applied successfully."

  local second_commit_message="${jira_ticket} Override JDT core ${ecj_tag} with custom changes"
  echo "--- Create commit: ${second_commit_message} ---"
  git add src/main/java
  git commit -m "${second_commit_message}"
}

overrideECJ "$@"

exit 0
