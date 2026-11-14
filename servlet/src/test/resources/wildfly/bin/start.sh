/opt/jboss/wildfly/bin/add-user.sh --realm ManagementRealm --user $WILDFLY_USERNAME --password $WILDFLY_PASSWORD
/opt/jboss/wildfly/bin/standalone.sh --server-config standalone-ha.xml -Djboss.bind.address=0.0.0.0 -Djboss.bind.address.management=0.0.0.0 -Djboss.bind.address.private=$SERVER_NAME -Djboss.socket.binding.port-offset=$PORT_OFFSET -Djboss.node.name=$SERVER_NAME
