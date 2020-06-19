# Java 12+ AWS Lambda Custom Runtime

# About

This custom AWS Java runtime make it easy to specify any JDK version (including pre-releases published by the
`AdoptOpenJDK` project) and makes deploying the runtime to AWS as easy as running `mvn install`. The runtime uses the
`jlink` utility of the Java Platform Module System to create a stripped-down (lean) build of the JDK. It also uses
`Application Class-Data Sharing`, a feature introduced in Java 13. Both of these help to reduce the JDK startup time.

# Change JDK Version

The version of the JDK that will be used when deploying the runtime can be configured using the following
properties in the configuration of `gmavenplus-plugin` in `pom.xml`:

```pom
<configuration>
   <properties>
     <repoVersion>13</repoVersion>
     <version>13U</version>
     <type>jdk</type>
     <arch>x64</arch>
     <os>linux</os>
     <impl>hotspot</impl>
     <!-- https://github.com/AdoptOpenJDK/openjdk13-binaries/releases -->
     <releaseDate>2019-09-24-08-26</releaseDate>
     <!-- etc. -->
   </properties>
    <!-- etc. -->
</configuration>
```

# Configure AWS Region

You can change the region that will be used for deploying the runtime by the `awsRegion` property of the
`gmavenplus-plugin` configurationin `pom.xml`:

```pom
<configuration>
   <properties>
      <awsRegion>US_WEST_2</awsRegion>
      <!-- etc. -->
   </properties>
    <!-- etc. -->
</configuration>
```

# Configure jlink Modules

Because we use jlink to create a stripped-down build of the JDK it is necessary to explicitly add the modules that are
needed to run whatever lambdas will be run by the custom runtime. The list of modules can be specified by the `jlinkModules`
property of the `gmavenplus-plugin` configuration in `pom.xml`:

```pom
<configuration>
   <properties>
      <jlinkModules>java.net.http,java.desktop,java.logging,java.naming,java.sql,java.xml,org.slf4j,org.slf4j.simple</jlinkModules>
      <!-- etc. -->
   </properties>
    <!-- etc. -->
</configuration>
```

# Deploy Runtime to AWS

The first step to deploy the runtime to your AWS account is to clone this repo. Next, the custom runtime is
deployed to AWS by running `mvn install`. To build the runtime without deploying to AWS (for testing, for example) 
run `mvn package`.

# TODO (Public Consumption)

* Make it possible to supply a custom name for the published runtime.

# Credit

This project was forked from [ andthearchitect/aws-lambda-java-runtime](https://github.com/andthearchitect/aws-lambda-java-runtime)
which provided a great starting point!