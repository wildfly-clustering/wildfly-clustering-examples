/opt/jboss/wildfly/bin/add-user.sh --realm ManagementRealm --user $WILDFLY_USERNAME --password $WILDFLY_PASSWORD
/opt/jboss/wildfly/bin/standalone.sh --server-config standalone-ha.xml -Djboss.socket.binding.port-offset=$PORT_OFFSET -Djboss.node.name=$SERVER_NAME
