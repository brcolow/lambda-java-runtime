package com.dow.aws.lambda;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        String runtimeApi = System.getenv("AWS_LAMBDA_RUNTIME_API");
        String taskRoot = System.getenv("LAMBDA_TASK_ROOT");
        String handlerName = System.getenv("_HANDLER");
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

        // Main event loop
        //noinspection InfiniteLoopStatement
        while (true) {
            // Get next Lambda Event
            try {
                HttpResponse<InputStream> response = HTTP_CLIENT.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());
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
                // Invoke Handler Method
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
                try (Stream<Path> walk = Files.walk(Paths.get("."))) {
                    List<String> jfrRecordings = walk
                            .filter(p -> !Files.isDirectory(p))
                            .map(p -> p.toString().toLowerCase())
                            .filter(f -> f.endsWith("jfr"))
                            .collect(Collectors.toList());
                    LOGGER.info("jfr recordings: " + jfrRecordings);
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
            "'{'" +
            "  \"errorMessage\": \"{0}\"," +
            "  \"errorType\": \"{1}\"" +
            "'}'";

    private static void postError(String errorUrl, String errMsg, String errType) {
        String error = MessageFormat.format(ERROR_RESPONSE_TEMPLATE, errMsg, errType);
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
            LOGGER.error("public void handleRequest method does not take 2 or 3 arguments!");
            throw new RuntimeException("public void handleRequest method does not take 2 or 3 arguments!");
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