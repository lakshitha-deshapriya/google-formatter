#!/bin/bash
# Property Renamer - Convenience script

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Run the property renamer
java -jar "$SCRIPT_DIR/target/property-renamer-jar-with-dependencies.jar" "$@"

