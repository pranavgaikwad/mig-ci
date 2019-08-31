#!/bin/bash
# Mig operator logs disabled by default, they are very large

OC_BINARY=`which oc`
MIG_NS="openshift-migration"
WITH_CONTROLLER="false"
WITH_OPERATOR_LOGS=${WITH_OPERATOR_LOGS:-false}
E2E_NS=${E2E_NS:-"sock-shop robot-shop parks-app mysql-persistent"}
DATE=`date`

echo "############################## BEGIN MIG DEBUG ##################################"
echo -e "\t\t\t$DATE"
echo "#################################################################################"

echo -n "Check status of oc session "
oc_status=$( ${OC_BINARY} whoami )

if [ $? -ne 0 ]; then
	echo "FAILED"
	echo "oc session does not seem to be valid, exiting..."
        exit 1
else
	echo "OK"
fi

echo
echo "##### Print OC client env #####"
${OC_BINARY} version
echo 

oc_url=$(${OC_BINARY} whoami --show-server)

echo
echo "##### Print all resources on ${MIG_NS} namespace #####"
echo
oc -n ${MIG_NS} get all
echo

# Check if cluster hosting controller

mig_controller_check=$( ${OC_BINARY} -n ${MIG_NS} get pods | grep controller-manager )
if [ $? -ne 0 ]; then
	echo
	echo "Controller pod not found, skipping mig CRs..."
	echo
else
	WITH_CONTROLLER="true"
	echo "Controller pod running : ${mig_controller_pod}"
	echo
	echo "====== Extract migmigrations ======"
	echo
	${OC_BINARY} -n ${MIG_NS} describe migmigration
	echo
	echo "====== Extract migplans ======"
	echo
	${OC_BINARY} -n ${MIG_NS} describe migplan
	echo
	echo "====== Extract migstorage ======"
	echo
	${OC_BINARY} -n ${MIG_NS} describe migstorage
	echo
	echo "====== Extract migclusters ======"
	echo
        ${OC_BINARY} -n ${MIG_NS} describe migcluster
	echo
	echo "====== Extract clusters ======"
	echo
        ${OC_BINARY} -n ${MIG_NS} describe cluster
	echo
fi

# Collect logs
echo "##### Process LOGS #####"
echo
mig_controller_pod=$( ${OC_BINARY} -n ${MIG_NS} get pods | grep controller-manager | cut -d " " -f1 )
operator_pod=$( ${OC_BINARY} -n ${MIG_NS} get pods | grep migration-operator | cut -d " " -f1 )
velero_pod=$( ${OC_BINARY} -n ${MIG_NS} get pods | grep velero | cut -d " " -f1 )

if [ ${WITH_CONTROLLER} == "true" ]; then
	echo "=== Mig controller logs ==="
#	${OC_BINARY} -n ${MIG_NS} logs ${mig_controller_pod}
fi

echo
echo "====== Velero logs ======"
echo
${OC_BINARY} -n ${MIG_NS} logs ${velero_pod}

if [ ${WITH_OPERATOR_LOGS} == "true" ]; then
echo
echo "====== Operator logs ======"
echo
${OC_BINARY} -n ${MIG_NS} logs ${operator_pod} -c ansible
${OC_BINARY} -n ${MIG_NS} logs ${operator_pod} -c operator
echo
fi

# Restic DS spawns multiple pods , collect them first
declare -a restic_pods                                                                                                                                                                                             
readarray -t restic_pods <<< $(oc -n openshift-migration get pods | grep restic | cut -d " " -f1)

for pod in ${restic_pods[@]}; do
	echo
	echo "====== Restic logs ======"
	echo
	${OC_BINARY} -n ${MIG_NS} logs ${pod}
done
echo
echo "##### Process E2E namespaces #####"
echo
declare -a bad_pods
for ns in ${E2E_NS}; do
	echo
	echo "====== $ns all ======"
	echo
	${OC_BINARY} -n ${ns} get all
	echo
	echo "====== $ns pvc ======"
	echo
	${OC_BINARY} -n ${ns} get pvc
	echo
	echo "====== $ns check pods not in running state ======"
	echo
	readarray -t bad_pods <<< $(${OC_BINARY} -n ${ns} get pods --no-headers --field-selector=status.phase!=Running | cut -d " " -f1)
	if [ ${#bad_pods[@]} -ne 0 ]; then
		echo
		echo "====== $ns extract logs for not running pods ======"
		echo
		for bad_pod in ${bad_pods[@]}; do
			echo "Processing pod : ${bad_pod}"
			${OC_BINARY} -n $ns logs ${bad_pod}
		done
	fi
	unset bad_pods
done
