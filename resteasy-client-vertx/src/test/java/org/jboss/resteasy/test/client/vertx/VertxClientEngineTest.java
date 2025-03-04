package org.jboss.resteasy.test.client.vertx;

import static junit.framework.TestCase.assertTrue;
import static junit.framework.TestCase.fail;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientResponseContext;
import javax.ws.rs.client.ClientResponseFilter;
import javax.ws.rs.client.CompletionStageRxInvoker;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.InvocationCallback;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.engines.vertx.VertxClientHttpEngine;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class VertxClientEngineTest {
    Vertx vertx;
    HttpServer server;
    Client client;
    ScheduledExecutorService executorService;

    @Before
    public void before() {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer();
        executorService = Executors.newSingleThreadScheduledExecutor();
    }

    @After
    public void stop() throws Exception {
        if (client != null) {
            client.close();
        }
        CountDownLatch latch = new CountDownLatch(1);
        vertx.close(ar -> latch.countDown());
        latch.await(2, TimeUnit.MINUTES);
        executorService.shutdownNow();
    }

    private Client client() throws Exception {
        if (server.actualPort() == 0) {
            CompletableFuture<Void> fut = new CompletableFuture<>();
            server.listen(0, ar -> {
                if (ar.succeeded()) {
                    fut.complete(null);
                } else {
                    fut.completeExceptionally(ar.cause());
                }
            });
            fut.get(2, TimeUnit.MINUTES);
        }
        if (client == null) {
            client = ((ResteasyClientBuilder) ClientBuilder
                    .newBuilder()
                    .scheduledExecutorService(executorService))
                    .httpEngine(new VertxClientHttpEngine(vertx)).build();
        }
        return client;
    }

    @Test
    public void testSimple() throws Exception {
        server.requestHandler(req -> {
            HttpServerResponse response = req.response();
            if (req.getHeader("User-Agent").contains("Apache")) {
                response.setStatusCode(503).end();
            } else if (!"abracadabra".equals(req.getHeader("Password"))) {
                response.setStatusCode(403).end();
            } else {
                req.response().end("Success");
            }
        });

        final Response response = client().target(baseUri()).request()
                .header("Password", "abracadabra")
                .get();

        assertEquals(200, response.getStatus());
        assertEquals("Success", response.readEntity(String.class));
    }


    @Test
    public void testHTTP() throws Exception {
        Vertx vertx = Vertx.vertx();
        Client client = ((ResteasyClientBuilder) ClientBuilder
                .newBuilder()
                .scheduledExecutorService(executorService))
                .httpEngine(new VertxClientHttpEngine(vertx)).build();
        final Response resp = client.target("http://example.com").request().get();
        assertEquals(200, resp.getStatus());
    }

    @Test
    public void testHTTPS() throws Exception {
        Vertx vertx = Vertx.vertx();
        HttpClientOptions options = new HttpClientOptions();
        options.setSsl(true);
        Client client = ((ResteasyClientBuilder) ClientBuilder
                .newBuilder()
                .scheduledExecutorService(executorService))
                .httpEngine(new VertxClientHttpEngine(vertx, options)).build();
        final Response resp = client.target("https://example.com").request().get();
        assertEquals(200, resp.getStatus());
    }

    @Test
    public void testHTTP2() throws Exception {
        Vertx vertx = Vertx.vertx();
        HttpClientOptions options = new HttpClientOptions();
        options.setSsl(true);
        options.setProtocolVersion(HttpVersion.HTTP_2);
        options.setUseAlpn(true);
        Client client = ((ResteasyClientBuilder) ClientBuilder
                .newBuilder()
                .scheduledExecutorService(executorService))
                .httpEngine(new VertxClientHttpEngine(vertx, options)).build();
        final Response resp = client.target("https://nghttp2.org/httpbin/get").request().get();
        assertEquals(200, resp.getStatus());
    }

    @Test
    public void testHTTP2ByEngineRegistration() {
        Vertx vertx = Vertx.vertx();
        HttpClientOptions options = new HttpClientOptions();
        options.setSsl(true);
        options.setProtocolVersion(HttpVersion.HTTP_2);
        options.setUseAlpn(true);
        Client client = ClientBuilder
                .newBuilder()
                .register(new VertxClientHttpEngine(vertx, options))
                .build();
        final Response resp = client.target("https://nghttp2.org/httpbin/get").request().get();
        assertEquals(200, resp.getStatus());
        Assert.assertTrue(resp.readEntity(String.class).contains("nghttp2.org"));

    }

    @Test
    public void testSimpleResponseRx() throws Exception {
        server.requestHandler(req -> {
            HttpServerResponse response = req.response();
            if (req.getHeader("User-Agent").contains("Apache")) {
                response.setStatusCode(503).end();
            } else if (!"abracadabra".equals(req.getHeader("Password"))) {
                response.setStatusCode(403).end();
            } else {
                req.response().putHeader("Content-Type", "text/plain").end("Success");
            }
        });

        final CompletionStage<Response> cs = client().target(baseUri()).request()
                .header("Password", "abracadabra").rx(CompletionStageRxInvoker.class)
                .get();

        Response response = cs.toCompletableFuture().get();
        assertEquals(200, response.getStatus());
        assertEquals("Success", response.readEntity(String.class));
    }


    @Test
    public void testSimpleStringRx() throws Exception {
        server.requestHandler(req -> {
            HttpServerResponse response = req.response();
            if (req.getHeader("User-Agent").contains("Apache")) {
                response.setStatusCode(503).end();
            } else if (!"abracadabra".equals(req.getHeader("Password"))) {
                response.setStatusCode(403).end();
            } else {
                req.response().putHeader("Content-Type", "text/plain").end("Success");
            }
        });

        final CompletionStage<String> cs = client().target(baseUri()).request()
                .header("Password", "abracadabra").rx(CompletionStageRxInvoker.class)
                .get(String.class);

        String response = cs.toCompletableFuture().get();
        assertEquals("Success", response);
    }

    @Test
    public void testBigly() throws Exception {
        server.requestHandler(new EchoHandler());
        final byte[] valuableData = randomAlpha().getBytes(StandardCharsets.UTF_8);
        final Response response = client().target(baseUri()).request()
                .post(Entity.entity(valuableData, MediaType.APPLICATION_OCTET_STREAM_TYPE));

        assertEquals(200, response.getStatus());
        assertArrayEquals(valuableData, response.readEntity(byte[].class));
    }

    @Test
    public void testFutureResponse() throws Exception {
        server.requestHandler(new EchoHandler());
        final String valuableData = randomAlpha();
        final Future<Response> response = client().target(baseUri()).request()
                .buildPost(Entity.entity(valuableData, MediaType.APPLICATION_OCTET_STREAM_TYPE))
                .submit();

        final Response resp = response.get(10, TimeUnit.SECONDS);
        assertEquals(200, resp.getStatus());
        assertEquals(valuableData, resp.readEntity(String.class));
    }

    @Test
    public void testFutureString() throws Exception {
        server.requestHandler(new EchoHandler());
        final String valuableData = randomAlpha();
        final Future<String> response = client().target(baseUri()).request()
                .buildPost(Entity.entity(valuableData, MediaType.APPLICATION_OCTET_STREAM_TYPE))
                .submit(String.class);

        final String result = response.get(10, TimeUnit.SECONDS);
        assertEquals(valuableData, result);
    }

    private String randomAlpha() {
        final StringBuilder builder = new StringBuilder();
        final Random r = new Random();
        for (int i = 0; i < 20 * 1024 * 1024; i++) {
            builder.append((char) ('a' + (char) r.nextInt('z' - 'a')));
            if (i % 100 == 0) builder.append('\n');
        }
        return builder.toString();
    }

    @Test
    public void testCallbackString() throws Exception {
        server.requestHandler(new EchoHandler());
        final String valuableData = randomAlpha();
        CompletableFuture<String> cf = new CompletableFuture<>();
        client().target(baseUri()).request()
                .buildPost(Entity.entity(valuableData, MediaType.APPLICATION_OCTET_STREAM_TYPE))
                .submit(new InvocationCallback<String>() {
                    @Override
                    public void completed(String s) {
                        cf.complete(s);
                    }

                    @Override
                    public void failed(Throwable throwable) {
                        cf.completeExceptionally(throwable);
                    }
                });

        final String result = cf.get(10, TimeUnit.SECONDS);
        assertEquals(valuableData, result);
    }

    @Test
    public void testTimeout() throws Exception {
        server.requestHandler(req -> {
            vertx.setTimer(1000, id -> {
                req.response().end();
            });
        });
        try {
            Invocation.Builder property = client()
                    .target(baseUri())
                    .request()
                    .property(VertxClientHttpEngine.REQUEST_TIMEOUT_MS, Duration.ofMillis(500));
            property
                    .get();
            fail();
        } catch (ProcessingException e) {
            assertTrue(e.getCause() instanceof TimeoutException);
        }
    }

    @Test
    public void testDeferContent() throws Exception {
        server.requestHandler(new EchoHandler());
        final byte[] valuableData = randomAlpha().getBytes(StandardCharsets.UTF_8);
        final Response response = client().target(baseUri()).request()
                .post(Entity.entity(new StreamingOutput() {
                    @Override
                    public void write(OutputStream output) throws IOException, WebApplicationException {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                        output.write(valuableData);
                    }
                }, MediaType.APPLICATION_OCTET_STREAM_TYPE));

        assertEquals(200, response.getStatus());
        assertArrayEquals(valuableData, response.readEntity(byte[].class));
    }

    @Test
    public void testFilterBufferReplay() throws Exception {
        final String greeting = "Success";
        final byte[] expected = greeting.getBytes(StandardCharsets.UTF_8);
        server.requestHandler(req -> {
            req.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/plain").end(greeting);
        });

        final byte[] content = new byte[expected.length];
        final ClientResponseFilter capturer = new ClientResponseFilter() {
            @Override
            public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) throws IOException {
                responseContext.getEntityStream().read(content);
            }
        };

        try (InputStream response = client().register(capturer).target(baseUri()).request()
                .get(InputStream.class)) {
            // ignored, we are checking filter
        }

        assertArrayEquals(expected, content);
    }

    @Test
    public void testServerFailure1() throws Exception {
        server.requestHandler(req -> {
            req.response().close();
        });

        try {
            client().target(baseUri()).request().get();
            fail();
        } catch (ProcessingException ignore) {
            // Expected
        }
    }

    @Test
    public void testServerFailure2() throws Exception {
        server.requestHandler(req -> {
            HttpServerResponse resp = req.response();
            resp.setChunked(true).write("something");
            vertx.setTimer(1000, id -> {
                // Leave it some time to receive the response headers and start processing the response
                resp.close();
            });
        });

        try {
            Response response = client().target(baseUri()).request().get();
            response.readEntity(String.class);
            fail();
        } catch (ProcessingException ignore) {
            // Expected
        }
    }

    public URI baseUri() {
        return URI.create("http://localhost:" + server.actualPort());
    }

    static class EchoHandler implements Handler<HttpServerRequest> {
        @Override
        public void handle(HttpServerRequest req) {
            req.bodyHandler(body -> {
                String type = req.getHeader(HttpHeaders.CONTENT_TYPE);
                if (type == null) {
                    type = "text/plain";
                }
                req.response()
                        .putHeader(HttpHeaders.CONTENT_TYPE, type)
                        .end(body);
            });
        }
    }
}
