#!/bin/bash


echo "Starting Maven clean process for all microservices..."
echo "----------------------------------------------------"

for dir in */; do
    if [ -f "${dir}pom.xml" ]; then
        echo ""
        echo ">>> Cleaning project in: ${dir}"


        (cd "$dir" && mvn clean)

        echo "<<< Finished cleaning: ${dir}"
    fi
done