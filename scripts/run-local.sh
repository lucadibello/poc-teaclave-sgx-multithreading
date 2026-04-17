#!/bin/bash

sudo java -cp 'host/target/crashme-topology.jar:/opt/storm/lib/*' ch.usi.inf.crashme.WordCountTopology --local
