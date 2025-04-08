#!/bin/bash

#the script is verified to work for Eclipe release R4_35

# Ask for ECJ release tag
read -p "Enter ECJ release tag (e.g., R4_33): " ecj_tag

# Define the base URL for fetching the problem files
base_url="https://raw.githubusercontent.com/eclipse-jdt/eclipse.jdt.core/${ecj_tag}/org.eclipse.jdt.core.compiler.batch/src/org/eclipse/jdt/internal/compiler/problem"

# Ensure the target directory exists
mkdir -p src/main/java/org/eclipse/jdt/internal/compiler/problem

# Fetch ProblemHandler.java with curl
echo "Fetching ProblemHandler.java..."
curl -sSLf -o src/main/java/org/eclipse/jdt/internal/compiler/problem/ProblemHandler.java "${base_url}/ProblemHandler.java"
if [ $? -ne 0 ]; then
  echo "Error: Failed to fetch ProblemHandler.java"
  exit 1
fi

# Fetch ProblemReporter.java with curl
echo "Fetching ProblemReporter.java..."
curl -sSLf -o src/main/java/org/eclipse/jdt/internal/compiler/problem/ProblemReporter.java "${base_url}/ProblemReporter.java"
if [ $? -ne 0 ]; then
  echo "Error: Failed to fetch ProblemReporter.java"
  exit 1
fi

echo "Files fetched successfully."

# Show the last 15 commits from history
echo "Find the last Update JDT core commit and Override ECJ files commit:"
echo "----------------------------------------"
git log --oneline -15 || { echo "Error: Not a git repository."; exit 1; }

# Ask the user to select two commits to base the patch on
read -p "Enter Update JDT core commit hash: " commit1
read -p "Enter Override ECJ files commit hash: " commit2

# Create a diff patch between the two selected commits
patch_file="commit_patch.diff"
git diff "$commit1" "$commit2" > "$patch_file"
if [ $? -ne 0 ]; then
  echo "Error: Failed to create patch file."
  exit 1
fi
echo "Patch file created: $patch_file"

# Try to apply the patch
echo "Applying patch..."
git apply "$patch_file"
if [ $? -ne 0 ]; then
  echo "Error: Failed to apply the patch."
  exit 1
fi

echo "Patch applied successfully."

# Cleanup
rm "$patch_file"