# mod_cluster-discovery: Exploring discovery options with mod_cluster

The `mod_cluster-discovery` quickstart will focus on the ways in which mod_cluster can be configured to discover the 
set of workers to be used for load balancing. 

Mod_cluster can be configured for use with a variety of load balancers. The load balancer configuration we use for this 
example will be based on the Wildfly server profile `standalone-load-balancer.xml` which integrates Undertow and 
mod_cluster to provide a feature-rich load balancer solution.

For the latest documentation on mod_cluster, see the
[Wildfly High Availablity Guide](https://docs.wildfly.org/30/High_Availability_Guide.html#load-balancing)

## Introduction

Before we begin, some background on the design and configuration of mod_cluster is in order as this will be relevant
to configuration discussion which follows.

### Key features of mod_cluster

Unlike most load balancers, in which the relationship between the load balancer and its workers is configured statically 
by configuration files on the load balancer itself, mod_cluster detects its workers dynamically, by a process we call
discovery. Discovery can be multicast-based (the default), or statically configured on the workers.

Additionally, mod_cluster supports communication between workers and the load balancer via the mod_cluster Management 
Protocol (MCMP) which permits workers to register with the load balancer as well as inform the load balancer of 
changing circumstances on the worker, such as the status of deployments and processing load levels.

In this quickstart, we shall explore the configuration requirements for both dynamic and static discovery of workers.

### Configuration of load balancer and workers

As mentioned earlier, we will be using a load balancing setup where:
* our load balancer will be a Wildfly server instance started with the `standalone-load-balancer.xml` server profile
* our worker nodes will be Wildfly server instances started with the `standalone-ha.xml` profile

The `standalone-load-balancer.xml` server profile uses dynamic, multicast-discovery by default, which provides a 
discovery configuration which works out-of-the-box. The `standalone-ha.xml` server profile ensures that any deployments
on those workers will support the clustering features of failover and load-balancing.

Load balancing with mod_cluster supports feature configuration on both the load balancer node and the worker nodes. 
In the next section, we review the configuration options relevant to discovery.

#### Configuration of mod_cluster on the load balancer

When using the `standalone-load-balancer.xml` server profile, mod_cluster is implemented as an Undertow filter, named 
mod-cluster, with the following default configuration in the Undertow subsystem:
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
* the http-listener element `default` is where the load balancer listens for incoming application traffic from clients; this listener uses the http protocol by default
* the http-listener element `management` is where the load balancer listens for incoming MCMP traffic from workers; this listener uses the http protocol by default

Not all attributes of the element `<mod-cluster/>` defined in the schema are shown here in this default configuration.
For a full list of configurable elements and attributes, see the [modcluster filter model reference](https://docs.wildfly.org/30/wildscribe/subsystem/undertow/configuration/filter/mod-cluster/index.html)

#### Configuration of mod_cluster on the workers

On the workers, the key configuration element is the modcluster subsystem, with the default configuration shown below:
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
  traffic from the load balancer. In this case, the binary `ajp` protocol is used for communication between the load balancer and the workers

Not all attributes of the modcluster subsystem defined in the schema are shown here. For a full list of configurable
elements and attributes, see the [modcluster subsystem model reference](https://docs.wildfly.org/30/wildscribe/subsystem/modcluster/index.html)

Now that we have a basic understanding of how mod_cluster works and how it is configured, we explore how dynamic 
discovery works with mod_cluster as the default discovery mode.

### The sample application

In order to test the load balancer and its workers once configured, we need a sample application to deploy on the 
worker nodes. 

In what follows, we shall use the application [clusterbench](https://github.com/clusterbench/clusterbench), a J2EE 
application tailored for testing J2EE application servers such as Wildfly and the clustering features they provide. 

Our aim here is not to test the features of the load balancer itself, but rather to demonstrate that, with discovery 
so configured, a client can make invocations on a deployment via the load balancer and that we see the requests 
balanced between worker nodes.

### Setting up the Wildfly instances

In what follows, we will install and configure three instances of Wildfly to represent the load balancer and two workers.
These instances will be run on `localhost` using the loopback network `lo` for communication between instances.

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

## Scenario I: mod_cluster with dynamic discovery

As mentioned earlier, dynamic discovery is enabled by default when using the Wildfly standalone-load-balancer.xml server
profile. This is to allow discovery to work out-of-the-box; in other words, with no additional configuration of the 
load balancer or its workers.

### Setting up multicast for the loopback interface

To use multicast advertisement, we need to make sure that multicast is enabled on our network of choice. However, 
multicast is not enabled by default for the loopback interface `lo`. Use the following commands to enable multicast
on the loopback interface:
```shell
# setup multicast routing on localhost
route add -net 224.0.0.0 netmask 240.0.0.0 dev lo
ifconfig lo multicast
```

### Starting the load balancer

In a new terminal, start the load-balancer instance using the standalone-load-balancer.xml server profile:
```shell
$BALANCER_HOME/bin/standalone.sh -c standalone-load-balancer.xml -Djboss.node.name=balancer -Djboss.bind.address=localhost
```
Here, we have provided an appropriate name for this Wildfly instance (`balancer`) as well as specified the domain name
of `localhost` for the bind address. 

### Starting the workers

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
13:44:20,172 INFO  [io.undertow] (default task-1) UT005053: Registering node worker1, connection: ajp://127.0.0.1:8109/?#
13:44:20,174 INFO  [io.undertow] (default task-1) UT005045: Registering context /, for node worker1
13:44:20,175 INFO  [io.undertow] (default task-1) UT005045: Registering context /wildfly-services, for node worker1
``````

Now, in a second new terminal, start the worker2 instance using the standalone-ha.xml server profile:
```shell
$WORKER2_HOME/bin/standalone.sh -c standalone-ha.xml -Djboss.node.name=worker2 -Djboss.bind.address=localhost -Djboss.socket.binding.port-offset=200
```
As before, we provide an appropriate name for this instance as well as a port offset of 200 to avoid port conflicts with 
the load balancer and worker1. 

After a few seconds, you should see output in the load balancer terminal window indicating that worker2 has been
successfully registered with the load balancer:
```shell
13:44:20,172 INFO  [io.undertow] (default task-1) UT005053: Registering node worker2 connection: ajp://127.0.0.1:8209/?#
13:44:20,174 INFO  [io.undertow] (default task-1) UT005045: Registering context /, for node worker2
13:44:20,175 INFO  [io.undertow] (default task-1) UT005045: Registering context /wildfly-services, for node worker2

``````

Under the covers, here is what happened:
* the load balancer instance started and began advertising the host:port combination for its MCMP management address
* the worker instances started and began listening for multicast advertisements from the load balancer. 
  Upon receipt of the host:port of the load balancers management interface, each worker used MCMP messages to register
  themselves with the load balancer.

At this stage, the load balancer is started, the two worker nodes are started and registered with the load balancer, and 
we are ready to deploy our test application.  

### Deploy and run the sample application

As part of the project build process, the clusterbench jar was downloaded into $PROJECT_HOME/server/deployment and 
the wildfly-maven-plugin has been configured to point to this deployement.

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
new contexts on the load balancer, each represeting a deployment on worker1. If the deployment were to be removed, 
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
The sample application clusterbench is now deployed on both workers. We can test the operation of the load balancer 
by invoing the sample application client. To execute the client, open a new terminal window, navigate to the 
$PROJECT_HOME/client directory and run the command:
```shell
mvn exec:exec 
```
You should see the response
```shell
Invoking method getNodeName(): result = <node>
```
where node takes the value worker1 or worker2,depending on which worker the invocation landed on. Repeatedly executing
this command should show the request being directed to the different worker nodes.

### Conclusion

We have seen that dynamic (multicast-based) discovery is enabled by default when using the standalone-load-balancer.xml 
server profile of Wildfly. Once multicast is configured for the network over which the load balancer and workers
communicate, discovery happens dynamically upon the load balancer and the workers being started.

In the next section, we shall show the configuration changes required to adjust the discovery method from dynamic
to static discovery.

## Scenario II: mod_cluster with static discovery

In cases where multicast is not available or desired, discovery can be configured statically. In this case, each
worker must point to the load balancer location. Upon startup, the worker will use the MCMP to register itself
with the load balancer, using the load balancer host:port provided.

### Re-configuring the load balancer for static discovery

To configure the load-balancer, we provide a Wildfly CLI script `configure-loadbalancer.cli` which contains the CLI
commands for setting up static discovery on the load balancer. Review the comments in the CLI script to understand 
the changes made. 

Now, start the load-balancer instance using the load-balancer.xml server profile:
```shell
$BALANCER_HOME/bin/standalone.sh -c load-balancer.xml -Djboss.node.name=balancer -Djboss.bind.address=localhost
```

With the server started, run the configuration script for the loadbalancer instance:
```shell
$BALANCER_HOME/bin/jboss-cli.sh --connect --controller=localhost:9990 --file=./configure-loadbalancer.cli 
```
You should see the following output from the execution of the CLI script:
```shell
The batch executed successfully
process-state:reload-required
``````
At this stage, the load balancer is now configured for static discovery and you may shut the loadbalancer instance down.

### Re-configuring the workers for static discovery

To configure the workers, we provide a Wildfly CLI script `configure-worker.cli` which contains the CLI
commands for setting up static discovery on the worker nodes. Review the comments in the CLI script to understand
the changes made.

First, start the worker1 instance using the standalone-ha.xml server profile:
```shell
$WORKER1_HOME/bin/standalone.sh -c standalone-ha.xml -Djboss.node.name=worker1 -Djboss.bind.address=localhost -Djboss.socket.binding.port-offset=100
```

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
At this stage, the worker1 is now configured for static discovery and you may shut the worker1 instance down.

Second, start the worker2 instance using the standalone-ha.xml server profile:
```shell
$WORKER2_HOME/bin/standalone.sh -c standalone-ha.xml -Djboss.node.name=worker2 -Djboss.bind.address=localhost -Djboss.socket.binding.port-offset=200
```

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
At this stage, the worker2 is now configured for static discovery and you may shut the worker2 instance down.

### Restarting the load balancer/workers setup 

At this point, you may now restart the newly configured load balancer and the two worker nodes to verify that our
switch to static discovery works as expected.

First, start the load-balancer instance using the load-balancer.xml server profile:
```shell
$BALANCER_HOME/bin/standalone.sh -c load-balancer.xml -Djboss.node.name=balancer -Djboss.bind.address=localhost
```
You should see the usual server startup messages in the load balancer terminal window. Now start the first worker 
instance worker1 using the standalone-ha.xml
server profile:
```shell
$WORKER1_HOME/bin/standalone.sh -c standalone-ha.xml -Djboss.node.name=worker1 -Djboss.bind.address=localhost -Djboss.socket.binding.port-offset=100
```
You should see the usual server startup messages in the worker1 terminal window. After a few seconds, in the load 
balancer terminal window, you should see messages indicating that worker1 has now successfully registered itself 
with the load balancer:
```shell
14:39:28,848 INFO  [io.undertow] (default task-1) UT005053: Registering node worker1, connection: ajp://localhost:8109/?#
14:39:38,893 INFO  [io.undertow] (default task-1) UT005045: Registering context /clusterbench-granular, for node worker1
14:39:38,898 INFO  [io.undertow] (default task-1) UT005045: Registering context /clusterbench, for node worker1
14:39:38,907 INFO  [io.undertow] (default task-1) UT005045: Registering context /clusterbench-passivating, for node worker1
14:39:39,026 INFO  [io.undertow] (default task-1) UT005045: Registering context /, for node worker1
14:39:39,029 INFO  [io.undertow] (default task-2) UT005045: Registering context /wildfly-services, for node worker1
```
As we deployed clusterbench-ee10.ear on the server instance worker1, its deployments are again active and we see that 
they are likewise registered with the load balancer.

Now start the second worker instance worker2 using the standalone-ha.xml server profile:
```shell
$WORKER1_HOME/bin/standalone.sh -c standalone-ha.xml -Djboss.node.name=worker1 -Djboss.bind.address=localhost -Djboss.socket.binding.port-offset=100
```
You should see the usual server startup messages in the worker2 terminal window. As before, after a few seconds, in the 
load balancer terminal window, you should see messages indicating that worker2 has now successfully registered itself 
with the load balancer.

### Running the sample application

As before, we can test the operation of the load balancer and workers using static discovery by invoing the sample 
application client. To execute the client, open a new terminal window, navigate to the $PROJECT_HOME/client directory 
and run the command:
```shell
mvn exec:exec 
```
You should see the response
```shell
Invoking method getNodeName(): result = <node>
```
where node takes the value worker1 or worker2,depending on which worker the invocation landed on.  Repeatedly executing
this command should show the request being directed to the different worker nodes.

## Conclusion

In this quickstart, we demonstrated the use of dynamic and static discovery when using Wildfy and its 
standalone-load-balancer.xml server profile as a load balancer.


