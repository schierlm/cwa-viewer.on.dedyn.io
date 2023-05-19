#!/bin/sh
set -e

mvn package
java -cp target/CompressTool-0.1-SNAPSHOT-jar-with-dependencies.jar compresstool.DecompressDayFiles
java -cp target/CompressTool-0.1-SNAPSHOT-jar-with-dependencies.jar compresstool.DecompressHourFiles
