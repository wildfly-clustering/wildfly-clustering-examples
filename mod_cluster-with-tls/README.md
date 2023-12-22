# mod_cluster-with-tls: Securing a load balancer with TLS 

The `mod_cluster-with-tls` quickstart will demonstrate how to secure the communication pathways used by mod_cluster
when acting as a load balancer. Mod_cluster can be configured for use with a variety of load balancers. The load balancer 
configuration we use for this quickstart will be based on the Wildfly server profile `load-balancer.xml` which 
integrates Undertow and mod_cluster to provide a feature-rich load balancer solution.

For the latest documentation on mod_cluster, see the 
[Wildfly High Availablity Guide](https://docs.wildfly.org/30/High_Availability_Guide.html#load-balancing)

### Introduction

Before we begin, some background on the design and configuration of mod_cluster is in order as this will be relevant
to configuration discussion which follows.

#### Key features of mod_cluster

Unlike most load balancers, in which the relationship between the load balancer and its workers
is configured statically by files on the load balancer itself, mod_cluster detects its workers dynamically, by a 
process we call discovery. Discovery can be multicast-based (as used in this quickstart) or statically configured 
on the workers themselves. Additionally, mod_cluster supports communication between workers and the load balancer via
the mod_cluster Management Protocol (MCMP) which permits workers to register with the load balancer as well as inform
the load balancer of changing circumstances on the worker, such as the status of deployments and processing load 
levels.

So, for the purpose of securing communication with mod_cluster, there are three key communication pathways to consider:
* client to load balancer (application traffic)
* load balancer to worker (application traffic)
* worker to load balancer (MCMP traffic)

Securing mod_cluster means securing these various communication pathways between the client applications, the 
load balancer and the backend workers processing the client requests.

NOTE: There is a fourth communication pathway, from load balancer to worker, used for multicast discovery, but this 
is not secured by TLS; we will mention it briefly in what follows when appropriate.

#### Configuration of load balancer and workers

As mentioned earlier, we will be using a load balancing setup where:
* our load balancer will be a Wildfly server instance started with the `load-balancer.xml` server profile
* our worker nodes will be Wildfly server instances started with the `standalone-ha.xml` profile

The `load-balancer.xml` server profile uses multicast-discovery by default, which provides a configuration which works 
out-of-the-box. The `standalone-ha.xml` server profile provides that any deployments on those workers will support 
the clustering features of failover and load-balancing. 

Load balancing with mod_cluster supports feature configuration on both the load balancer node and the worker nodes.

##### Configuration of mod_cluster on the load balancer

When using mod_cluster in this configuration, mod_cluster is implemented as an Undertow filter, mod-cluster, with 
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

Not all attributes of the element <mod-cluster/> defined in the schema are shown here in this default configuration. 
For a full list of configurable elements and attributes, see the [modcluster filter model reference](https://docs.wildfly.org/30/wildscribe/subsystem/undertow/configuration/filter/mod-cluster/index.html)

##### Configuration of mod_cluster on the workers

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
traffic from the load balancer. In this case, the binary `ajp` protocol is used 

Not all attributes of the modcluster subsystem defined in the schema are shown here. For a full list of configurable
elements and attributes, see the [modcluster subsystem model reference](https://docs.wildfly.org/30/wildscribe/subsystem/modcluster/index.html)

Now that we have a basic understanding of how mod_cluster works and how it is configured, we return to the task of securing 
mod_cluster with TLS.

### Setting up the Wildfly instances

In what follows, we will install and configure three instances of Wildfly to represent the load balancer and two workers
and run these three instances on `localhost` using the loopback network `lo` for communication between instances. 

In what follows, we refer to the instances as follows:
* `BALANCER_HOME` is the directory containing the Wildfly installation to be used for the load balancer
* `WORKER1_HOME` is the directory containing the Wildfly installation to be used for the first worker
* `WORKER2_HOME` is the directory containing the Wildfly installation to be used for the first worker

Additionally, we assume that a user called `quickstartUser` and a password called`quickstartPwd1!` is defined on each 
of the worker instances. This user and password combination will be used for authentication of the client application
to the backend workers. For example, to add the user on WORKER1_HOME:
```shell
$WORKER1_HOME/bin/add-user.sh -a -u 'quickstartUser' -p 'quickstartPwd1!'
```
Similarly, add the same user and password for WORKER2_HOME.

With these three Wildfy instances installed, there are still a number of additional changes that need to be made to 
the server configuration files in order to set up TLS, and we shall provide instructions on how to do this in what follows. 
But before, starting, here is an overview of the changes that will need to be made:
1. Create the keystore and truststore files to be used
2. Enable multicast on `lo`
3. Create the keystores and truststores for TLS and copy them to their required locations
3. Configure the load balancer
   1. In the Elytron subsystem, set up the client SSLContext and the server SSLContext using the created keystores and truststores
   2. In the Undertow subsystem, set up an https-listener using the server SSLContext to receive encrypted application traffic from the client
   3. In the Undertow subsystem, set up the mod-cluster filter attribute `ssl-context` to use the client SSLContext to send encrypted application traffic to the workers
   4. In the Undertow subsystem, set up an https-listener using the server SSLContext to receive MCMP traffic from the workers
4. Configure the worker(s)
   1. In the Elytron subsystem, set up the client SSLContext and the server SSLContext using the created keystores and truststores
   2. In the Undertow subsystem, set up an https-listener using the server SSLContext to receive encrypted application traffic from the load balancer
   3. In the modcluster subsystem, set the ssl-context attribute of the proxy element using the client SSLContext to send encrypted MCMP traffic to the load balancer
   4. In the modcluster subsystem, change the attribute listener to use the `https` protocol instead of `ajp`

We now move into the actual configuration of the load balancer and worker instances.

### Creating the security infrastructure

The starting point for use of TLS encryption between a client and a server is to provide the keystores and truststores 
which the client and server will use during the TLS protocol handshake. For a given node with a domain name, the 
keystore holds a private key and a public certificate representing the node and the truststore holds a set of 
certificates which the node implicitly trusts. During TLS handshake, client and server exchange certificates to verify 
each others identity. In one-way authentication, the client requests the certificate of the server and validates the 
server's certificate against its truststore. In two-way or mutual TLS authentication, this is extended by the server
also requesting the certificate identifying the client. In this example, we consider only one-way authentication 
using self-signed certificates. 

We use keytool to create the keystore and truststore pairs for the server and the client. The keystores and truststores
are named as follows:
* server keystore `tlsServer.keystore` with store password `serverKeySecret` contains the server's private key and public certificate with distinguished name `cn=localhost`
* server truststore `tlsServer.truststore` with store password `serverTrustSecret` contains the client's public certificate with distinguished name `cn=client` 
* client keystore `tlsClient.keystore` with store password `clientKeySecret` contains the client's private key and public certificate with distinguished name `cn=client`
* client truststore `tlsClient.truststore` with store password `clientTrustSecret` contains the server's public certificate with distinguished name `cn=localhost`

NOTE: Despite the fact that we have three nodes to represent by certificates (i.e. a client, a load balancer and a worker), 
because all our nodes are running on localhost, we can reuse the client and server keystores and truststores across
different communication pathways. 

The script `configure-keystores.sh` can be used to generate these files. Simply run the shell script as follows in the current directory of this quickstart:
```shell
./configure-keystores.sh
```

Once generated, these keystore and truststore files need to be copied to the correct locations. Determining the 
correct locations depends on two factors:
* which connection pathways need to use TLS?
* on each connection pathway, which endpoint acts as client and which acts as server?

From a TLS handshake point of view:
* the client acts a TLS client only
* the load balancer acts as a TLS server when receiving application traffic from the client or when receiving MCMP traffic from workers, and acts as a TLS client when forwarding application traffic to workers
* the worker acts as a TLS server when receiving application traffic from the load balancer and acts as a TLS client when sending MCMP messages to the load balancer 

These facts will become important when configuring the required SSLContexts on the client, the load balancer and the workers.

### Setting up multicast for the loopback interface

In the load-balancer.xml server profile, multicast advertisement is enabled by default. This is to allow discovery
to work out-of-the-box, in other words, with no additional configuration. However, multicast is not enabled by default
for the loopback interface `lo` and so the following commands need to be executed:
```shell
# setup multicast routing on localhost
route add -net 224.0.0.0 netmask 240.0.0.0 dev lo
ifconfig lo multicast
```

### Installing and configuring the load balancer

To configure the load-balancer, we provide a Wildfly CLI script `configure-loadbalancer.cli` which contains the CLI
commands for setting up TLS on the load balancer. First, start the load-balancer instance using the load-balancer.xml 
server profile:
```shell
$BALANCER_HOME/bin/standalone.sh -c load-balaner.xml -Djboss.node.name=balancer -Djboss.bind.address=localhost
```
We have provided an appropriate name for this Wildfly instance (`balancer`) as well as specified the domain name
of `localhost` for the bind address. In our case, this will cause any URLs used to open communication channels to use
a domain name of `localhost` instead of 127.0.0.1, which will match the server certificate distinguished name
`cn=localhost`. 

With the server started, run the configuration script for the loadbalancer instance:
```shell
$BALANCER_HOME/bin/jboss-cli.sh --connect --controller=localhost:9990 --file=./configure-loadbalancer.cli 
```
You should see the following output from the execution of the CLI script:
```shell
The batch executed successfully
process-state:reload-required
``````
At this stage, the load balancer is now configured for TLS and you may shut the loadbalancer instance down.

### Installing and configuring the workers

To configure the workers, we provide a Wildfly CLI script `configure-worker.cli` which contains the CLI
commands for setting up TLS on the worker nodes. 

First, start the worker1 instance using the standalone-ha.xml server profile:
```shell
$WORKER1_HOME/bin/standalone.sh -c standalone-ha.xml -Djboss.node.name=worker1 -Djboss.bind.address=localhost -Djboss.socket.binding.port-offset=100
```
As with the load balancer instance, we have provided an appropriate name for this Wildfly instance as well as specified
the domain name of `localhost` for the bind address. Additionally, because all three of the server instances are running
on the same host, to avoid port conflicts, we need to additionally specify a socket binding port offset, 100 in this case.

With the server started, run the configuration script for the worker1 instance, using a controller port which has been
adjusted for the port offset used to start the server:
```shell
$WORKER1_HOME/bin/jboss-cli.sh --connect --controller=localhost:10090 --file=./configure-worker.cli 
```
You should see the following output from the execution of the CLI script:
```shell
The batch executed successfully
process-state:reload-required
``````
At this stage, the worker1 is now configured for TLS and you may shut the worker1 instance down.

Second, start the worker2 instance using the standalone-ha.xml server profile:
```shell
$WORKER1_HOME/bin/standalone.sh -c standalone-ha.xml -Djboss.node.name=worker2 -Djboss.bind.address=localhost -Djboss.socket.binding.port-offset=200
```
As with the load balancer instance, we have provided an appropriate name for this Wildfly instance as well as specified
the domain name of `localhost` for the bind address. Additionally, because all three of the server instances are running
on the same host, to avoid port conflicts, we need to additionally specify a socket binding port offset, 200 in this case.

With the server started, run the configuration script for the worker2 instance, using a controller port which has been
adjusted for the port offset used to start the server:
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



