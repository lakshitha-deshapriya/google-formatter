#!/bin/bash
# Google Java Formatter - Convenience script

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Run the formatter
java -jar "$SCRIPT_DIR/target/google-formatter-jar-with-dependencies.jar" "$@"

