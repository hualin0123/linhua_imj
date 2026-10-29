#!/usr/bin/env bash
set -e

mvn -q -DskipTests compile exec:java -Dexec.mainClass=com.linhua.plot.BeamPointing_
