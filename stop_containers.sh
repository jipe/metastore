#!/bin/bash
cd docker
exec sudo docker-compose down --remove-orphans 2>&1
