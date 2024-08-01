# mod_cluster-with-tls: Securing a load balancer with TLS 

The `mod_cluster-with-tls` quickstart will demonstrate how to secure the communication pathways used by mod_cluster
when acting as a load balancer. 

Mod_cluster can be configured for use with a variety of load balancers. The load balancer configuration we use for 
this quickstart will be based on the Wildfly server profile `standalone-load-balancer.xml` which integrates Undertow 
and mod_cluster to provide a feature-rich load balancer solution.

For the latest documentation on mod_cluster, see the 
[Wildfly High Availablity Guide](https://docs.wildfly.org/30/High_Availability_Guide.html#load-balancing)

## Introduction

Before we begin, some background on the design and configuration of mod_cluster is in order as this will be relevant
to configuration discussion which follows.

### Key features of mod_cluster

Unlike most load balancers, in which the relationship between the load balancer and its workers
is configured statically by files on the load balancer itself, mod_cluster detects its workers dynamically, by a 
process we call discovery. Discovery can be multicast-based (as used in this quickstart) or statically configured 
on the workers themselves. 

Additionally, mod_cluster supports communication between workers and the load balancer via the mod_cluster Management 
Protocol (MCMP) which permits workers to register with the load balancer as well as inform
the load balancer of changing circumstances on the worker, such as the status of deployments and processing load 
levels.

So, for the purpose of securing communication with mod_cluster, there are three key communication pathways to consider:
* client to load balancer (application traffic)
* load balancer to worker (application traffic)
* worker to load balancer (MCMP traffic)

Securing mod_cluster means securing these various communication pathways between the client applications, the 
load balancer and the backend workers processing the client requests.

> [!NOTE]
> There is a fourth communication pathway, from load balancer to worker, used for multicast discovery, but this 
> is not secured by TLS; we will mention it briefly in what follows when appropriate.

### Configuration of load balancer and workers

As already mentioned, we will be using a load balancing setup where:
* our load balancer will be a Wildfly server instance started with the `standalone-load-balancer.xml` server profile
* our worker nodes will be Wildfly server instances started with the `standalone-ha.xml` profile

The `standalone-load-balancer.xml` server profile uses multicast-discovery by default, which provides a configuration which works 
out-of-the-box. The `standalone-ha.xml` server profile provides that any deployments on those workers will support 
the clustering features of failover and load-balancing. 

Load balancing with mod_cluster supports feature configuration on both the load balancer node and the worker nodes.

#### Configuration of mod_cluster on the load balancer

When using mod_cluster in this configuration, mod_cluster is implemented as an Undertow filter, named mod-cluster, with 
the following default configuration in the Undertow subsystem:
```xml
<subsystem xmlns="urn:jboss:domain:undertow:14.0" default-virtual-host="default-host" default-servlet-container="default" default-server="default-server" statistics-enabled="${wildfly.undertow.statistics-enabled:${wildfly.statistics-enabled:false}}">     <byte-buffer-pool name="default"/>
    <buffer-cache name="default"/>
    <server name="default-server">
        <http-listener name="default" socket-binding="http" redirect-socket="https" enable-http2="true"/>
        <http-listener name="management" socket-binding="mcmp-management" enable-http2="true"/>
        <host name="default-host" alias="localhost">
            <filter-ref name="load-balancer"/>
        </host>
    </server>
    <servlet-container name="default"/>
    <filters>
        <mod-cluster name="load-balancer" management-socket-binding="mcmp-management" advertise-socket-binding="modcluster" enable-http2="true" max-retries="3">
            <single-affinity/>
        </mod-cluster>
    </filters>
</subsystem>
```
With respect to previous discussion, we note the following:
* the attribute `advertise-socket-binding` specifies the server socket binding to be used for discovery (the load balancer will 'advertise' its presence on a multicast address configured in the socket binding)
* the attribute `management-socket-binding` specifies the server socket binding which accepts MCMP requests from the workers
* the http-listener element `default` is where the load balancer listens for incoming application traffic from clients; this listener (http, not https) is currently configured not to use TLS
* the http-listener element `management` is where the load balancer listens for incoming MCMP traffic from workers; this listener (http, not https) is currently configured not to use TLS 

Not all attributes of the element `<mod-cluster/>` defined in the schema are shown here in this default configuration. 
For a full list of configurable elements and attributes, see the [modcluster filter model reference](https://docs.wildfly.org/30/wildscribe/subsystem/undertow/configuration/filter/mod-cluster/index.html)

#### Configuration of mod_cluster on the workers

On the workers, the key configuration element for mod_cluster is the modcluster subsystem, with the default 
configuration shown below:
```xml
<subsystem xmlns="urn:jboss:domain:modcluster:6.0">
    <proxy name="default" advertise-socket="modcluster" listener="ajp">
        <dynamic-load-provider>
            <load-metric type="cpu"/>
        </dynamic-load-provider>
    </proxy>
</subsystem>
```
For the purposes of our discussion, we note the following:
* the attribute `advertise-socket` refers to a socket binding on the worker which is used to listen for multicast advertisements
* the listener attribute `ajp` refers to the Undertow listener on the worker that will be used for receiving application 
traffic from the load balancer. In this case, the binary `ajp` protocol is used. 

> {!NOTE]
> The `ajp` protocol is a binary protocol used between the load balancer and its workers for performance reasons. 
> However, mod_cluster supports a variety of backend protocols, including ajp, http, http2 and https, among others.

Not all attributes of the modcluster subsystem defined in the schema are shown here. For a full list of configurable
elements and attributes, see the [modcluster subsystem model reference](https://docs.wildfly.org/30/wildscribe/subsystem/modcluster/index.html)

Now that we have a basic understanding of how mod_cluster works and how it is configured, we return to the task of securing 
mod_cluster with TLS.

### The sample application

In order to eventually test the load balancer and its workers once configured, we need a sample application to deploy 
on the worker nodes.

In what follows, we shall use the application [clusterbench](https://github.com/clusterbench/clusterbench), a J2EE
application tailored for testing J2EE application servers such as Wildfly and the clustering features they provide.

Our aim here is not to test the features of the load balancer itself, but rather to demonstrate that, with discovery
so configured, a client can make invocations on a deployment via the load balancer and that we see the requests
balanced between worker nodes.

### Setting up the Wildfly instances

In what follows, we will install and configure three instances of Wildfly to represent the load balancer and two workers
and run these three instances on `localhost` using the loopback network `lo` for communication between instances. 

We refer to the instances as follows:
* `BALANCER_HOME` is the directory containing the Wildfly installation to be used for the load balancer
* `WORKER1_HOME` is the directory containing the Wildfly installation to be used for the first worker
* `WORKER2_HOME` is the directory containing the Wildfly installation to be used for the second worker

Additionally, we assume that a user called `quickstartUser` and a password called`quickstartPwd1!` is defined on each 
of the worker instances. This user and password combination will be used for authentication of the client application
to the backend workers. For example, to add the user on WORKER1_HOME:
```shell
$WORKER1_HOME/bin/add-user.sh -a -u 'quickstartUser' -p 'quickstartPwd1!'
```
Similarly, add the same user and password for WORKER2_HOME.

As mentioned earlier, dynamic discovery is enabled by default when using the Wildfly standalone-load-balancer.xml
server profile. To use multicast advertisement, we need to make sure that multicast is enabled on our network of choice. 
However, multicast is not enabled by default for the loopback interface `lo`. Use the following commands to enable
multicast on the loopback interface:
```shell
# setup multicast routing on localhost
route add -net 224.0.0.0 netmask 240.0.0.0 dev lo
ifconfig lo multicast
```

At this stage, the load balancer and its workers are in a working configuration in which unencrypted protocols (http, 
ajp) are used across the communication pathways mentioned earlier. 

> [!TIP]
> As an exercise, you could try to verify that the present configuration works as expected by starting the load balancer, 
> the two workers, deploying clusterbench on the two workers and running the client application. THis is optional.
> Instructions for performing these steps can be found in what follows.

In the next sesion, we continue the discussion by describing the additional configuration required for encrypting 
the communication pathways using TLS.

## Scenario: mod_cluster with TLS

Securing the load balancer using TLS amounts to (1) identifying each communication pathway in use and (2) setting up 
the resources and configuration required to ensure that TLS is in effect across that communication pathway. 
As mentioned previously, the resources and configuration to be used at a given endpoint also depend on whether that 
endpoint is functioning as a client or as a server (i.e. initiating the communication or receiving it).

### An overview of the configuration changes required

Given that there are a number of configuration changes required, here we provide an overview of those changes:
1. Create the keystore and truststore files to be used to allow TLS-based encryption across communication pathways 
2. Copy those keystore and truststore files to their required locations in the Wildfly server installations $BALANCER, $WORKER1 and $WORKER2
3. Configure the load balancer for TLS:
   - in the Elytron subsystem:
     - set up a client SSLContext and a server SSLContext using the associated keystores and truststores
   - in the Undertow subsystem
     - set up an https-listener using the server SSLContext to receive encrypted application traffic from the client
     - set up the mod-cluster filter attribute `ssl-context` to use the client SSLContext to send encrypted application traffic to the workers
     - set up an https-listener using the server SSLContext to receive MCMP traffic from the workers
4. Configure the worker(s) for TLS:
   - in the Elytron subsystem
     - set up the client SSLContext and the server SSLContext using the associated keystores and truststores
   - in the Undertow subsystem:
     - set up an https-listener using the server SSLContext to receive encrypted application traffic from the load balancer
   - in the modcluster subsystem:
     - set the ssl-context attribute of the `<proxy/>` element using the client SSLContext to send encrypted MCMP traffic to the load balancer
     - change the attribute listener to use the `https` protocol instead of `ajp` to force the load balancer to send application traffic using https

We now move into the actual configuration of the load balancer and worker instances.

### Creating the security infrastructure

The starting point for use of TLS encryption between a client and a server is to provide the keystores and truststores 
which the client and server will use during the TLS protocol handshake. 

For a given node, the keystore holds a private key and a public key certificate representing the node; the truststore 
holds a set of certificates which the node implicitly trusts. During TLS handshake, client and server exchange 
certificates to first verify each others identity and then establish a shared key to be used for encryption. The 
exchange of certificates and verification of identity may be one-way or two-way.

In one-way TLS authentication, the client requests the certificate of the server and validates the server's certificate 
against the client's truststore. In two-way or mutual TLS authentication, this is extended by the server
also requesting the certificate identifying the client and validating the certificate against th server's truststore.
If any validation fails, the TLS handshake fails. In this example, we consider only one-way authentication using 
self-signed certificates. 

We use the JDK command `keytool` to create the necessary keystore and truststore pairs for the server and the client. 
The stores are named as follows:
* server keystore `tlsServer.keystore` with store password `serverKeySecret` contains the server's private key and its public certificate with distinguished name `cn=localhost`
* server truststore `tlsServer.truststore` with store password `serverTrustSecret` contains the client's public certificate with distinguished name `cn=client` 
* client keystore `tlsClient.keystore` with store password `clientKeySecret` contains the client's private key and its public certificate with distinguished name `cn=client`
* client truststore `tlsClient.truststore` with store password `clientTrustSecret` contains the server's public certificate with distinguished name `cn=localhost`

> [!NOTE] 
> Despite the fact that we have three nodes to represent by certificates (i.e. a client, a load balancer and a worker), 
> because all our nodes are running on localhost, we can and do reuse the client and server keystores and truststores 
> across different communication pathways. 

> [!TIP]
> The distinguished name for a public certificate representing a node is not arbitrary. Certificate validation 
> will fail of the hostname in the URL used to connect to the node does not match the distinguished name of the 
> certificate returned by the node. We start the servers using the java system property jboss.bind.address=localhost
> precisely for this reason. Not doing so causes clients to connect using 127.0.0.1 which will fail validation.

#### Generating the keystores and truststores

The script `configure-keystores.sh` can be used to generate these files. This script contains the keytool commands
to generate the required keystores and truststore for our example. Review the comments in the shell script to see how
the stores are created. 

To generate the stores, simply open a new terminal window, navigate to the base directory for this quickstart, $PROJECT_HOME, 
and run the shell script as follows:
```shell
./configure-keystores.sh
```

You should see the following output in the terminal window:
```shell
Generating 2,048 bit RSA key pair and self-signed certificate (SHA256withRSA) with a validity of 365 days
	for: CN=localhost
[Storing tlsServer.keystore]
Certificate stored in file <tlsServer.cer>
Generating 2,048 bit RSA key pair and self-signed certificate (SHA256withRSA) with a validity of 365 days
	for: CN=Client
[Storing tlsClient.keystore]
Certificate stored in file <tlsClient.cer>
Certificate was added to keystore
[Storing tlsServer.truststore]
Certificate was added to keystore
[Storing tlsClient.truststore]
```
#### Copying the keystores and truststores

Once generated, these keystore and truststore files need to be copied to the correct locations for the clients and 
servers that use them. Determining the correct locations depends on two factors:
* which connection pathways need to use TLS?
* on each connection pathway, which endpoint acts as client and which acts as server?

From a TLS handshake point of view, considering our processes client, load balancer, worker1 and worker2:
* the client acts a TLS client only
* the load balancer acts as a TLS server when receiving application traffic from the client or when receiving MCMP traffic from workers, and acts as a TLS client when forwarding application traffic to workers
* the worker acts as a TLS server when receiving application traffic from the load balancer and acts as a TLS client when sending MCMP messages to the load balancer

These facts will become important when configuring the required SSLContexts on the client, the load balancer and 
the workers. 

For now, use the following commands to copy the keystores and truststores to their required locations. Open a new 
terminal window and navigate to the $PROJECT_HOME directory. 

The client application requires access to the client keystore and truststore, as it acts as a TLS client, and so these 
need to be placed on its classpath. Run the following command to copy the stores for the client application:
```shell
cp tlsClient.keystore tlsCient.truststore $PROJECT_HOME/client/src/main/resources
```

The load balancer acts as a TLS server for the client application traffic as well as the worker MCMP traffic, and acts 
as a TLS client for the worker application traffic. It needs to have both sets of keystores and truststores available.
Using the same terminal window, run the following command to copy the stores to the standalone configutaion directory 
of the $BALANCER:
```shell
cp tlsClient.keystore tlsCient.truststore tlsServer.keystore tlsServer.truststore $BALANCER/standalone/configuration
```

Each worker acts as a TLS server for the application traffic coming from the balancer, and acts as a TLS client for the 
MCMP traffic going to the balancer. It also needs to have both sets of keystores and truststores available.
Using the same terminal window, run the following command to copy the stores to the standalone configutaion directory of 
the workers, $WORKER1 and $WORKER2:
```shell
cp tlsClient.keystore tlsCient.truststore tlsServer.keystore tlsServer.truststore $WORKER1/standalone/configuration
cp tlsClient.keystore tlsCient.truststore tlsServer.keystore tlsServer.truststore $WORKER2/standalone/configuration
```

The keystores and truststores have now been generated and copied to their required locations. We now continue with
the server configuration changes required to enable TLS.

### Starting and configuring the load balancer

To configure the load-balancer, we provide a Wildfly CLI script `configure-loadbalancer.cli` which contains the CLI
commands for setting up TLS on the load balancer. Review the CLI script and its comments to understand what configuration 
changes are required on the load balancer. 

In a new terminal window, start the load-balancer instance using the standalone-load-balancer.xml server profile:
```shell
$BALANCER_HOME/bin/standalone.sh -c standalone-load-balancer.xml -Djboss.node.name=balancer -Djboss.bind.address=localhost
```
We have provided an appropriate name for this Wildfly instance (`balancer`) as well as specified the domain name
of `localhost` for the bind address. In our case, this will cause any URLs used to open communication channels to use
a domain name of `localhost` instead of 127.0.0.1, which will match the server certificate distinguished name
`cn=localhost`. 

With the server started, in a new terminal window, navigate to the $PROJECT_HOME directory and run the configuration 
script for the loadbalancer instance:
```shell
$BALANCER_HOME/bin/jboss-cli.sh --connect --controller=localhost:9990 --file=./configure-loadbalancer.cli 
```
You should see the following output from the execution of the CLI script:
```shell
The batch executed successfully
process-state:reload-required
``````
At this stage, the load balancer is now configured for TLS and you may shut the loadbalancer instance down.

### Starting and configuring the workers

To configure the workers, we provide a Wildfly CLI script `configure-worker.cli` which contains the CLI
commands for setting up TLS on the worker nodes. Review the CLI script and its comments to understand what configuration
changes are required on the workers.

In a new terminal window, start the worker1 instance using the standalone-ha.xml server profile:
```shell
$WORKER1_HOME/bin/standalone.sh -c standalone-ha.xml -Djboss.node.name=worker1 -Djboss.bind.address=localhost -Djboss.socket.binding.port-offset=100
```
As with the load balancer instance, we have provided an appropriate name for this Wildfly instance as well as specified
the domain name of `localhost` for the bind address. Additionally, because all three of the server instances are running
on the same host, to avoid port conflicts, we need to additionally specify a socket binding port offset, 100 in this case.

With the server started, in a new terminal window, navigate to the $PROJECT_HOME directory and run the configuration 
script for the worker1 instance, using a controller port which has been adjusted for the port offset used to start 
the server:
```shell
$WORKER1_HOME/bin/jboss-cli.sh --connect --controller=localhost:10090 --file=./configure-worker.cli 
```
You should see the following output from the execution of the CLI script:
```shell
The batch executed successfully
process-state:reload-required
``````
At this stage, the worker1 is now configured for TLS and you may shut the worker1 instance down.

Now, in a new terminal window, start the worker2 instance using the standalone-ha.xml server profile:
```shell
$WORKER1_HOME/bin/standalone.sh -c standalone-ha.xml -Djboss.node.name=worker2 -Djboss.bind.address=localhost -Djboss.socket.binding.port-offset=200
```
As with the load balancer instance, we have provided an appropriate name for this Wildfly instance as well as specified
the domain name of `localhost` for the bind address. Additionally, because all three of the server instances are running
on the same host, to avoid port conflicts, we need to additionally specify a socket binding port offset, 200 in this case.

With the server started, in a new terminak window, navigate to the $PROJECT_HOME directory and run the configuration 
script for the worker2 instance, using a controller port which has been adjusted for the port offset used to start 
the server:
```shell
$WORKER1_HOME/bin/jboss-cli.sh --connect --controller=localhost:10190 --file=./configure-worker.cli 
```
You should see the following output from the execution of the CLI script:
```shell
The batch executed successfully
process-state:reload-required
``````
At this stage, the worker2 is now configured for TLS and you may shut the worker2 instance down.

### Testing the configured setup

At this point, we have created our keystores and truststores, copied them to the required locations within the balancer 
and worker installation directories, and configured the load balancer and the worker serer profiles with the necessary 
configuration changes. These configuration changes include defining client and server SSLContexts based on our
keystore and trustore files, as well as updating the parameters for the Undertow listeners and attributes used to 
configure communications between client, load balancer and workers.

It is now time to check that our changes work as expected by starting the load balancer and its two workers, deploying
the sample application to the workers and running the test client.

#### Starting the load balancer

In a new terminal, start the load-balancer instance using the standalone-load-balancer.xml server profile:
```shell
$BALANCER_HOME/bin/standalone.sh -c standalone-load-balancer.xml -Djboss.node.name=balancer -Djboss.bind.address=localhost
```
Here, we have provided an appropriate name for this Wildfly instance (`balancer`) as well as specified the domain name
of `localhost` for the bind address.

#### Starting the workers

First, in a new terminal, start the worker1 instance using the standalone-ha.xml server profile:
```shell
$WORKER1_HOME/bin/standalone.sh -c standalone-ha.xml -Djboss.node.name=worker1 -Djboss.bind.address=localhost -Djboss.socket.binding.port-offset=100
```
As with the load balancer instance, we have provided an appropriate name for this Wildfly instance as well as specified
the domain name of `localhost` for the bind address. Additionally, because all three of the server instances are running
on the same host, to avoid port conflicts, we need to additionally specify a socket binding port offset, 100 in this case.

After a few seconds, you should see output in the load balancer terminal window indicating that worker1 has been
successfully registered with the load balancer:
```shell
13:44:20,172 INFO  [io.undertow] (default task-1) UT005053: Registering node worker1, connection: https://localhost:8543/?#
13:44:20,174 INFO  [io.undertow] (default task-1) UT005045: Registering context /, for node worker1
13:44:20,175 INFO  [io.undertow] (default task-1) UT005045: Registering context /wildfly-services, for node worker1
``````
Notice the connection on which the worker1 has been registered: https://localhost:8543. This URL indicates that any
application HTTP request traffic from the load balancer to worker1 will be sent to this URL; in other words, the application
traffic will be sent over an encrypted communication pathway.

Now, in a second new terminal, start the worker2 instance using the standalone-ha.xml server profile:
```shell
$WORKER2_HOME/bin/standalone.sh -c standalone-ha.xml -Djboss.node.name=worker2 -Djboss.bind.address=localhost -Djboss.socket.binding.port-offset=200
```
As before, we provide an appropriate name for this instance as well as a port offset of 200 to avoid port conflicts with
the load balancer and worker1.

After a few seconds, you should see output in the load balancer terminal window indicating that worker2 has been
successfully registered with the load balancer:
```shell
13:44:20,172 INFO  [io.undertow] (default task-1) UT005053: Registering node worker2 connection: https://localhost:8643/?#
13:44:20,174 INFO  [io.undertow] (default task-1) UT005045: Registering context /, for node worker2
13:44:20,175 INFO  [io.undertow] (default task-1) UT005045: Registering context /wildfly-services, for node worker2
``````

As with worker1, notice that worker2 is registered with a backend connection of https://localhost:8643 and so uses
a TLS encrypted communication pathway to receive application traffic.

At this stage, the load balancer is started, the two worker nodes are started and registered with the load balancer, and
we are ready to deploy our test application.

#### Deploy and run the sample application

As part of the project build process, the clusterbench jar was downloaded into $PROJECT_HOME/server/deployment and
the wildfly-maven-plugin has been configured to point to this deployment.

To deploy clusterbench on worker1, open a new terminal, navigate to the $PROJECT_HOME/server and run the command:
```shell
mvn wildfly:deploy -Dwildfly.hostname=localhost -Dwildfly.port=10090
```
In the terminal window for worker1, you should see that the deployment was successfully deployed on worker1. Also, in
the terminal window for the load balancer, you should see that several new `context`s have been registered on the load
balancer for worker1:
```shell
14:07:58,069 INFO  [io.undertow] (default task-1) UT005045: Registering context /clusterbench-granular, for node worker1
14:07:58,408 INFO  [io.undertow] (default task-1) UT005045: Registering context /clusterbench-passivating, for node worker1
14:07:58,410 INFO  [io.undertow] (default task-1) UT005045: Registering context /clusterbench, for node worker1
``````
In mod_cluster terms, a context represents the context path of a deployment. The worker worker1 has registered three
new contexts on the load balancer, each representing a deployment on worker1. If the deployment were to be removed,
the context paths would be removed from the load balancer.

To deploy clusterbench on worker2, open a new terminal, navigate to the $PROJECT_HOME/server directory and run the
command:
```shell
mvn wildfly:deploy -Dwildfly.hostname=localhost -Dwildfly.port=10190
```
In the terminal window for worker2, you should see that the deployment was successfully deployed on worker2. Also, in
the terminal window for the load balancer, you should see that several new `context`s have been registered on the load
balancer for worker2:
```shell
14:08:25,113 INFO  [io.undertow] (default task-1) UT005045: Registering context /clusterbench-granular, for node worker2
14:08:25,459 INFO  [io.undertow] (default task-1) UT005045: Registering context /clusterbench-passivating, for node worker2
14:08:25,461 INFO  [io.undertow] (default task-1) UT005045: Registering context /clusterbench, for node worker2
``````

#### Configuring the application client

he sample application clusterbench is now deployed on both workers. We can test the operation of the load balancer
by invoking the sample application client. But before we do, we need to explain how the application client is 
configured, both in general and specifically for use with TLS.

The application client is a standalone EJB client application called RemoteEJBClient which allows making invocations 
on a stateless session bean (SLSB) called RemoteStatelessEJBImpl which is part of the clusterbench deployment. The
application starts out by creating an EJB client proxy for the bean, specifying the deployment module it is deployed in,
its bean implementation and the interface containing the method to be invoked. 

Over and above the proxy creation, the application client needs to be configured in various ways. For this purpose, 
a wildfly-config.xml file has been defined which the application client will use to findout where the deployment can be 
found, which security credentials and contexts to use, as well as any transport related configuration. 

> [!NOTE]
> The client application accesses the workers via the load bakancer, and so the transport protocol used by the client 
> needs to be HTTP. Within EJB client parlance, we say that this application client is an EJB/HTTP client application.

The wildfly-config.xml file contains several sections, each related to the configuration requirements above.

The element `<jboss-ejb-client/>` of wildfly-config.xml allows specifying configuration for the EJBClientContext used by the 
client. In our example, it looks as follows:
```xml
<jboss-ejb-client xmlns="urn:jboss:wildfly-client-ejb:3.2">
    <connections>
        <!-- points to the load balancer and its invoker context -->
        <connection uri="https://localhost:8443/wildfly-services"/>
    </connections>
</jboss-ejb-client>
```
This XML snippet indicates to the EJB client application that deployed modules may be found at the specified URI,
https://localhost:8443. This is a URL pointing to the load balancer, with the https protocol specified, indicating 
that the client should connect to the load balancer using TLS. Additionally, the /wildfly-services context path is 
required whenever we use EJB with the HTTP transport; in other words, EJB/HTTP. THis context path allows Undertow 
to correctly interpret the invocation as an EJB invocation (as opposed to an invocation on a servlet). For more 
information on using the `<jboss-ejb-client/>` element to configure EJB client applications, see the 
[Wildfly Client Configuration Guide, Section 3.2](https://docs.wildfly.org/30/Client_Guide.html#jboss-ejb-client)

The element `<http-client/>` of wildfly-config.xml similarly allows configuration of the EJB/HTTP transport used by the 
client to connect to Undertow. In our example, we have:
```xml
    <http-client xmlns="urn:wildfly-http-client:1.0">
        <!-- default connection and connection pooling properties used by the HTTP client transport-->
        <defaults>
            <eagerly-acquire-session value="true"/>
            <buffer-pool buffer-size="2000" max-size="10" direct="true" thread-local-size="1"/>
            <enable-http2 value="false"/>
        </defaults>
    </http-client>
```
This XML snippet provides defaults for the EJB/HTTP transport used by the client and are included here for information
only. These default settings in the wildfly-config.xml are not specifically related to the use of TLS. For more
information on using the `<http-client/>` element to configure EJB client applications, see the
[EAP Development Guide, Configuring Clients, Section 19.1.3](https://access.redhat.com/documentation/en-us/red_hat_jboss_enterprise_application_platform/7.4/html/development_guide/configuring_clients#http_client_configuration_using_the_wildfly_config_file)

The element <authentication-context/> of wildfly-config.xml allows configuration of security parameters of the client 
and is the configuration section most relevant to our discussion. In our example, we have:
```xml
<authentication-client xmlns="urn:elytron:client:1.6">
    <!-- authentication configuration -->
    <authentication-rules>
        <rule use-configuration="default"/>
    </authentication-rules>
    <authentication-configurations>
        <configuration name="default">
            <sasl-mechanism-selector selector="DIGEST-MD5"/>
            <set-user-name name="quickstartUser"/>
            <credentials>
                <clear-password password="quickstartPwd1!"/>
            </credentials>
            <providers>
                <use-service-loader/>
            </providers>
        </configuration>
    </authentication-configurations>
    <!-- SSL configuration -->
    <key-stores>
        <key-store name="client-keystore" type="PKCS12">
            <resource name="tlsClient.keystore"/>
            <key-store-clear-password password="clientKeySecret"/>
        </key-store>
        <key-store name="client-truststore" type="PKCS12">
            <resource name="tlsClient.truststore"/>
            <key-store-clear-password password="clientTrustSecret"/>
        </key-store>
    </key-stores>
    <ssl-contexts>
        <ssl-context name="client-ssl-context">
            <trust-store key-store-name="client-truststore"/>
            <key-store-ssl-certificate key-store-name="client-keystore" alias="client">
                <key-store-clear-password password="clientKeySecret"/>
            </key-store-ssl-certificate>
            <protocol names="TLSv1.3 TLSv1.2"/>
        </ssl-context>
    </ssl-contexts>
    <ssl-context-rules>
        <rule use-ssl-context="client-ssl-context">
            <match-protocol name="https"/>
        </rule>
    </ssl-context-rules>
</authentication-client>
```

This XML snippet is divided up into two sections: an authentication section, where the usename and password that make up 
the credentials that the client will present to the worker instances when authenticating, as well as the keystore, 
trustsore and associated SSLContext the client will use when establishing the TLS-encrypted (https) connection from 
the client to the load balancer. You will observe that the SSLContext permits specifying the TLS versions that the 
client will offer to the server, ordered by preference. For more information on using the `<authentication-client/>` 
element to configure EJB client applications, see the
[Wildfly Client Configuration Guide, Section 3.1](https://docs.wildfly.org/30/Client_Guide.html#authentication-client)

#### Runing the application client

With the configuration of the application client explained, we are now ready to execute the application client. 

To execute the client, simply open a new terminal window, navigate to the $PROJECT_HOME/client directory and run 
the command:
```shell
mvn exec:exec 
```
You should see the response
```shell
Invoking method getNodeName(): result = <node>
```
where node takes the value worker1 or worker2,depending on which worker the invocation landed on. Repeatedly executing
this command should show the request being directed to the different worker nodes.


## Conclusion

In this quickstart, we looked at how to configure a load balancer with TLS.

