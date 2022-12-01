package com.brcolow.aws.lambda;

import jdk.jfr.Configuration;
import jdk.management.jfr.FlightRecorderMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static com.dow.aws.lambda.Bootstrap.ThrowingFunction.unchecked;
import static java.util.Objects.requireNonNull;

/**
 * The custom API runtime Bootstrap is responsible for interfacing with the custom runtime API and invoking our
 * Lambda request handler. Currently the only supported request handler methods that the Lambda must implement are:
 * <pre>{@code
 * public void handleRequest(InputStream input, OutputStream output throws IOException {}
 * }</pre>
 * <pre>{@code
 * public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {}
 * }</pre>
 */
public class Bootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger(Bootstrap.class);
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final String LAMBDA_VERSION_DATE = "2018-06-01";
    private static final String LAMBDA_RUNTIME_URL_TEMPLATE = "http://{0}/{1}/runtime/invocation/next";
    private static final String LAMBDA_INVOCATION_URL_TEMPLATE = "http://{0}/{1}/runtime/invocation/{2}/response";
    private static final String LAMBDA_INIT_ERROR_URL_TEMPLATE = "http://{0}/{1}/runtime/init/error";
    private static final String LAMBDA_ERROR_URL_TEMPLATE = "http://{0}/{1}/runtime/invocation/{2}/error";

    // Headers sent with runtime/invocation/next resource:
    // https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html#runtimes-api-next
    private static final String LAMBDA_RUNTIME_AWS_REQUEST_ID = "Lambda-Runtime-Aws-Request-Id";
    private static final String LAMBDA_RUNTIME_DEADLINE_MS = "Lambda-Runtime-Deadline-Ms";
    private static final String LAMBDA_RUNTIME_INVOKED_FUNCTION_ARN = "Lambda-Runtime-Invoked-Function-Arn";
    private static final String LAMBDA_RUNTIME_TRACE_ID = "Lambda-Runtime-Trace-Id";
    private static final String LAMBDA_RUNTIME_CLIENT_CONTEXT = "Lambda-Runtime-Client-Context";
    private static final String LAMBDA_RUNTIME_COGNITO_IDENTITY = "Lambda-Runtime-Cognito-Identity";

    static {
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.out");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
    }

    public static void main(String[] args) {
        // List of environment variables available to a Lambda (a custom runtime runs in such an environment):
        // https://docs.aws.amazon.com/lambda/latest/dg/lambda-environment-variables.html
        Objects.requireNonNull(System.getenv("AWS_LAMBDA_RUNTIME_API"));
        Objects.requireNonNull(System.getenv("LAMBDA_TASK_ROOT"));
        Objects.requireNonNull(System.getenv("_HANDLER"));
        String runtimeApi = System.getenv("AWS_LAMBDA_RUNTIME_API");
        LOGGER.info("AWS_LAMBDA_RUNTIME_API: \"" + runtimeApi + "\".");
        String taskRoot = System.getenv("LAMBDA_TASK_ROOT");
        LOGGER.info("LAMBDA_TASK_ROOT: \"" + taskRoot + "\".");
        String handlerName = System.getenv("_HANDLER");
        LOGGER.info("_HANDLER: \"" + handlerName + "\".");
        // This is an environment variable we use to add debugging/profiling capabilities to the runtime.
        LOGGER.info("AWS_LAMBDA_TEST: " + "\"" + System.getenv("AWS_LAMBDA_TEST") + "\".");
        if (handlerName.isBlank() || !handlerName.contains("::")) {
            String initErrorUrl = MessageFormat.format(LAMBDA_INIT_ERROR_URL_TEMPLATE, runtimeApi, LAMBDA_VERSION_DATE);
            postError(initErrorUrl, String.format("_HANDLER environment variable \"%s\" was blank or malformed (must " +
                            "contain \"::\" separator)",
                    handlerName), "InitError");
            LOGGER.error(String.format("_HANDLER environment variable \"%s\" was blank or malformed (must contain " +
                            "\"::\" separator)",
                    handlerName));
            return;
        }
        // Get the handler class and method name from the Lambda Configuration in the format of <class>::<method>
        String[] handlerParts = handlerName.split("::");
        Class handlerClass;
        Method handlerMethod;
        // Find the Handler and Method on the classpath
        try {
            handlerClass = getHandlerClass(taskRoot, handlerParts[0]);
        } catch (MalformedURLException | ClassNotFoundException ex) {
            String initErrorUrl = MessageFormat.format(LAMBDA_INIT_ERROR_URL_TEMPLATE, runtimeApi, LAMBDA_VERSION_DATE);
            postError(initErrorUrl, String.format("Could not find/load Lambda request handler class: \"%s\"",
                    handlerParts[0]), "InitError");
            LOGGER.error("exception: ", ex);
            return;
        }

        handlerMethod = getHandlerMethod(handlerClass, handlerParts[1]);
        if (handlerMethod == null) {
            String initErrorUrl = MessageFormat.format(LAMBDA_INIT_ERROR_URL_TEMPLATE, runtimeApi, LAMBDA_VERSION_DATE);
            postError(initErrorUrl, String.format("Could not find Lambda request handler method: \"%s\"",
                    handlerParts[1]), "InitError");
            return;
        }

        String requestId = null;
        String runtimeUrl = MessageFormat.format(LAMBDA_RUNTIME_URL_TEMPLATE, runtimeApi, LAMBDA_VERSION_DATE);
        var request = HttpRequest.newBuilder().uri(URI.create(runtimeUrl)).build();
        if (System.getenv("AWS_LAMBDA_TEST").equals("true")) {
            LOGGER.info("Handler class: " + handlerClass);
            LOGGER.info("Handler method: " + handlerMethod);
        }
        // Main event loop
        while (true) {
            // Get next Lambda Event
            try {
                if (System.getenv("AWS_LAMBDA_TEST").equals("true")) {
                    LOGGER.info("Retrieving next lambda event via request: " + request);
                }
                HttpResponse<InputStream> response = HTTP_CLIENT.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());
                if (System.getenv("AWS_LAMBDA_TEST").equals("true")) {
                    LOGGER.info("Lambda event response: " + response);
                    // This is used by the local-lambda runner to stop the lambda runtime after a single event.
                    if (response.statusCode() == 204 &&
                            response.headers().firstValue("Local-Lambda-Shutdown").orElse("").equals("true")) {
                        break;
                    }
                }
                requestId = response.headers().firstValue(LAMBDA_RUNTIME_AWS_REQUEST_ID).orElse("");
                // This allows for creating custom AWS X-Ray subsegments on our custom runtime.
                // See: https://github.com/aws/aws-xray-sdk-java/commit/67172dc861e785b8f8f230c7c6ba00aac562de71
                // aws-xray-sdk-java from version 2.9.0 on will read this property.
                System.setProperty("com.amazonaws.xray.traceHeader",
                        response.headers().firstValue(LAMBDA_RUNTIME_TRACE_ID).orElse(""));
                String invokedFunctionArn = response.headers()
                        .firstValue(LAMBDA_RUNTIME_INVOKED_FUNCTION_ARN).orElse("");
                long deadlineMillis = Long.parseLong(response.headers()
                        .firstValue(LAMBDA_RUNTIME_DEADLINE_MS).orElse("0"));
                String functionName = "";
                // arn:aws:lambda:us-east-2:123456789012:function:custom-runtime
                String[] arnParts = invokedFunctionArn.split(":");
                if (arnParts.length == 7) {
                    functionName = arnParts[6];
                }

                FlightRecorderMXBean bean = null;
                long recId = 0;
                if (System.getenv("AWS_LAMBDA_TEST").equals("true")) {
                    // TODO: It would be nice if we could access the FlightRecorderMXBean reflectively so that we
                    //  wouldn't have to include jlink modules jdk.jfr,jdk.management.jfr even when not in test mode.

                    // The preconfigured profiles provided by the JDK can be found in JAVA.HOME/lib/jfr, OpenJDK comes with
                    // two: "default" (approx. 1% overhead) and "profile" (approx. 2% overhead).
                    Configuration jfrConfig = Configuration.getConfiguration("profile");
                    bean = ManagementFactory.getPlatformMXBean(FlightRecorderMXBean.class);
                    recId = bean.newRecording();
                    bean.setPredefinedConfiguration(recId, jfrConfig.getName());
                    bean.startRecording(recId);
                }

                // Invoke handler method.
                String result = invoke(handlerClass, handlerMethod, response.body(), String.format("{\n" +
                                "\"awsRequestId\": \"%s\",\n" +
                                "\"logGroupName\": \"%s\",\n" +
                                "\"logStreamName\": \"%s\",\n" +
                                "\"functionName\": \"%s\",\n" +
                                "\"functionVersion\": \"%s\",\n" +
                                "\"invokedFunctionArn\": \"%s\",\n" +
                                "\"remainingTimeInMillis\": \"%d\",\n" +
                                "\"memoryLimitInMB\": \"%d\"\n" +
                                "}", requestId, System.getenv("AWS_LAMBDA_LOG_GROUP_NAME"),
                        System.getenv("AWS_LAMBDA_LOG_STREAM_NAME"), functionName,
                        System.getenv("AWS_LAMBDA_FUNCTION_VERSION"), invokedFunctionArn,
                        Math.max((int) (deadlineMillis - System.currentTimeMillis()), 0),
                        Integer.parseInt(System.getenv("AWS_LAMBDA_FUNCTION_MEMORY_SIZE"))));

                // Post the results of Handler Invocation
                String invocationUrl = MessageFormat.format(LAMBDA_INVOCATION_URL_TEMPLATE, runtimeApi,
                        LAMBDA_VERSION_DATE, requestId);
                HTTP_CLIENT.send(HttpRequest.newBuilder().uri(URI.create(invocationUrl)).POST(
                        HttpRequest.BodyPublishers.ofString(result)).build(), HttpResponse.BodyHandlers.ofString());

                if (System.getenv("AWS_LAMBDA_TEST").equals("true")) {
                    LOGGER.info("Stopping JFR flight recording.");
                    bean.stopRecording(recId);

                    long streamId = bean.openStream(recId, null);
                    File jfrFileStream = new File(
                            "lambda_profile_" + System.currentTimeMillis() + "_" + streamId + ".jfr");

                    try (var fos = new FileOutputStream(jfrFileStream); var bos = new BufferedOutputStream(fos)) {
                        while (true) {
                            byte[] data = bean.readStream(streamId);
                            if (data == null) {
                                bos.flush();
                                break;
                            }
                            bos.write(data);
                        }
                    }

                    bean.closeStream(streamId);
                    bean.closeRecording(recId);
                }
            } catch (Exception ex) {
                String initErrorUrl = MessageFormat.format(LAMBDA_ERROR_URL_TEMPLATE, runtimeApi,
                        LAMBDA_VERSION_DATE, requestId);
                postError(initErrorUrl, "Invocation Error", "RuntimeError");
                LOGGER.error("exception: ", ex);
            }
        }
    }

    private static final String ERROR_RESPONSE_TEMPLATE =
            '{' +
            "  \"errorMessage\": \"%s\"," +
            "  \"errorType\": \"%s\"" +
            '}';

    private static void postError(String errorUrl, String errMsg, String errType) {
        String error = String.format(ERROR_RESPONSE_TEMPLATE, errMsg ,errType);
        try {
            HTTP_CLIENT.send(HttpRequest.newBuilder().uri(URI.create(errorUrl)).POST(
                    HttpRequest.BodyPublishers.ofString(error)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException ex) {
            LOGGER.error("exception: ", ex);
        }
    }

    private static URL[] initClasspath(String taskRoot) {
        File cwd = new File(taskRoot);

        List<File> classPath = new ArrayList<>();

        // Add top level dir
        classPath.add(new File(taskRoot));

        // Find any Top level jars or jars in the lib folder
        for (File file : requireNonNull(cwd.listFiles((dir, name) -> name.endsWith(".jar") || name.equals("lib")))) {
            if (file.getName().equals("lib") && file.isDirectory()) {
                // Collect all jars in /lib directory
                Collections.addAll(classPath, requireNonNull(file.listFiles((dir, name) -> name.endsWith(".jar"))));
            } else {
                // Add top level dirs and jar files
                classPath.add(file);
            }
        }

        // Convert Files to URLs
        return classPath.stream().map(unchecked(file -> file.toURI().toURL())).toArray(URL[]::new);
    }

    private static Class getHandlerClass(String taskRoot, String className)
            throws MalformedURLException, ClassNotFoundException {
        URL[] classPathUrls = initClasspath(taskRoot);
        URLClassLoader cl = URLClassLoader.newInstance(classPathUrls);
        return cl.loadClass(className);
    }

    private static Method getHandlerMethod(Class handlerClass, String methodName) {
        for (Method method : handlerClass.getMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }

        return null;
    }

    private static String invoke(Class<?> handlerClass, Method handlerMethod, InputStream response, String contextJson)
            throws Exception {
        Object handlerClassObj = handlerClass.getConstructor().newInstance();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        Object[] args;
        if (handlerMethod.getParameterCount() == 2) {
            // public void handleRequest(InputStream input, OutputStream output)
            args = new Object[]{response, baos};
        } else if (handlerMethod.getParameterCount() == 3) {
            // public void handleRequest(InputStream input, OutputStream output, String context)
            args = new Object[]{response, baos, contextJson};
        } else {
            LOGGER.error("public void handleRequest method does not take 2 or 3 arguments! Your handleRequest " +
                    "takes " + handlerMethod.getParameterCount() + " methods.");
            throw new RuntimeException("public void handleRequest method does not take 2 or 3 arguments! Your " +
                    "handleRequest takes " + handlerMethod.getParameterCount() + " methods.");
        }
        handlerMethod.invoke(handlerClassObj, args);
        return baos.toString(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unused")
    @FunctionalInterface
    public interface ThrowingFunction<T, R, E extends Exception> {
        R apply(T arg) throws E;

        static <T, R> Function<T, R> unchecked(final ThrowingFunction<T, R, ?> f) {
            return requireNonNull(f).uncheck();
        }

        default Function<T, R> uncheck() {
            return t -> {
                try {
                    return apply(t);
                } catch (final Exception ex) {
                    throw new RuntimeException(ex.getMessage(), ex);
                }
            };
        }
    }

}