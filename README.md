# Java 12+ AWS Lambda Custom Runtime

# About

This custom AWS Java runtime make it easy to specify any JDK version (including pre-releases published by the
`AdoptOpenJDK` project) and makes deploying the runtime to AWS as easy as running `mvn install`. The runtime uses the
`jlink` utility of the Java Platform Module System to create a stripped-down (lean) build of the runtime. It also uses
`Application Class-Data Sharing`, a feature introduced in Java 13. Both of these help to reduce the JDK startup time.

# Change JDK Version

The version of the JDK that will be used when deploying the runtime can be configured using the following
properties in the configuration of `gmavenplus-plugin`:

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
   </properties>
    <!-- etc. -->
</configuration>
```

# Deploy Runtime to AWS

The custom runtime is automatically deployed to AWS when running `mvn install`. To build the runtime without deploying
to AWS run `mvn package`.

# TODO (Public Consumption)

* Make it possible to configure the region that is used with LambdaClient.
* Make it possible to supply a custom name for the published runtime.
* Make it possible to add custom modules to `--add-modules`.

# Credit

This project was forked from [ andthearchitect/aws-lambda-java-runtime](https://github.com/andthearchitect/aws-lambda-java-runtime)
which provided a great starting point!