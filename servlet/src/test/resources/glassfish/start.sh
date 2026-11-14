#!/bin/bash -e

echo "AS_ADMIN_MASTERPASSWORD=changeit" > "${AS_ADMIN_PASSWORDFILE}"
echo "AS_ADMIN_PASSWORD=${ADMIN_PASSWORD}" >> "${AS_ADMIN_PASSWORDFILE}"

DOMAIN_ADMIN_PORT=$(($DOMAIN_PORT_BASE + 48))

AS_MASTER_PASSWORDFILE=/tmp/password.txt
echo "AS_ADMIN_MASTERPASSWORD=changeit" > "${AS_MASTER_PASSWORDFILE}"
echo "AS_ADMIN_NEWPASSWORD=${ADMIN_PASSWORD}" >> "${AS_MASTER_PASSWORDFILE}"

echo "Creating ${DOMAIN_NAME} domain"
asadmin --host ${ADMIN_HOST} --port ${DOMAIN_ADMIN_PORT} --user=${ADMIN_USERNAME} --passwordfile ${AS_ADMIN_PASSWORDFILE} create-domain --portbase ${DOMAIN_PORT_BASE} --savemasterpassword=true --keytooloptions CN="${ADMIN_HOST}" ${DOMAIN_NAME}

echo "Starting ${DOMAIN_NAME} domain"
asadmin --host ${ADMIN_HOST} --port ${DOMAIN_ADMIN_PORT} --user=${ADMIN_USERNAME} --passwordfile ${AS_MASTER_PASSWORDFILE} start-domain ${DOMAIN_NAME}

echo "Creating ${CLUSTER_NAME} cluster"
asadmin --host ${ADMIN_HOST} --port ${DOMAIN_ADMIN_PORT} --user=${ADMIN_USERNAME} create-cluster --gmsenabled=true --multicastaddress 228.9.3.1 --multicastport 2048 ${CLUSTER_NAME}

echo "Configuring ${CLUSTER_NAME} cluster"
asadmin --host ${ADMIN_HOST} --port ${DOMAIN_ADMIN_PORT} --user=${ADMIN_USERNAME} set ${CLUSTER_NAME}-config.availability-service.web-container-availability.disable-jreplica=true
asadmin --host ${ADMIN_HOST} --port ${DOMAIN_ADMIN_PORT} --user=${ADMIN_USERNAME} set ${CLUSTER_NAME}-config.web-container.session-config.session-manager.manager-properties.property.relaxCacheVersionSemantics=true

echo "Creating ${SERVER_NAME} instance"
asadmin --host ${ADMIN_HOST} --port ${DOMAIN_ADMIN_PORT} --user=${ADMIN_USERNAME} create-local-instance --portbase ${SERVER_PORT_BASE} --cluster ${CLUSTER_NAME} ${SERVER_NAME}

echo "Starting ${SERVER_NAME} instance"
asadmin --host ${ADMIN_HOST} --port ${DOMAIN_ADMIN_PORT} --user=${ADMIN_USERNAME} start-local-instance ${SERVER_NAME}

# Wait a bit to ensure log was created
sleep 1s
echo "START-SCRIPT-COMPLETE"
tail -f -n1000 /opt/gfinstall/glassfish/nodes/localhost-test-domain/${SERVER_NAME}/logs/server.log
