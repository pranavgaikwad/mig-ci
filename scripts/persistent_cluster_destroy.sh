#!/bin/bash
# Locate persistent cluster build dir file and execute destroy
PERSISTENT_DIR="/var/lib/jenkins/persistent"
SKIP=false
export ANSIBLE_FORCE_COLOR=true

function usage () {
echo
echo "Valid options are : "
echo -e "\t-n : Name of file containing persistent cluster build directory in Jenkins: i.e $0 my-demo-cluster"
echo -e "\t-s : Skip cluster destroy, often used during first time builds"
echo -e "\t-d : Directory containing all persistent cluster build files, default is: ${PERSISTENT_DIR}"
echo
exit 1
}

if [ $# -lt 1 ]; then
  usage
fi

while getopts n:d:sh opt
do
    case $opt in

        n)
            FILE_NAME=${OPTARG}
            ;;
        d)
            PERSISTENT_DIR=${OPTARG}
            ;;
        s)
            SKIP=true
            ;;
        h)
            usage
            ;;
        *)
            usage
            ;;
    esac
done

[ ${SKIP} == "true" ] && echo "Skipping cluster destroy.." && exit 0

[ -z "${FILE_NAME}" ] && echo "Option -n must always be set.." && usage

PERSISTENT_FILE=${PERSISTENT_DIR}/${FILE_NAME}

[ ! -f ${PERSISTENT_FILE} ] && echo "${PERSISTENT_FILE} does not exist, exiting.." && exit 1

cd $(cat ${PERSISTENT_FILE}) && sed -i "s/&$//g" destroy_env.sh && ./destroy_env.sh
