#!/bin/bash
# Locate persistent cluster build dir file and execute destroy
PERSISTENT_DIR="/var/lib/jenkins/persistent"
export ANSIBLE_FORCE_COLOR=true

if [ $# -lt 1 ]; then
   echo "You must supply an existing persistent cluster file name: i.e $(basename $0) my-demo-cluster"
   exit 1
fi
[ ! -f ${PERSISTENT_DIR}/$1 ] && echo "${PERSISTENT_DIR}/$1 does not exist, exiting.." && exit 1

cd $(cat ${PERSISTENT_DIR}/$1) && sed -i "s/&$//g" destroy_env.sh && ./destroy_env.sh
