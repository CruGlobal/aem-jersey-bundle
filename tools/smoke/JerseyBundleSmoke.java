import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javassist.util.proxy.ProxyFactory;
import org.glassfish.hk2.api.ActiveDescriptor;
import org.glassfish.hk2.api.Context;
import org.glassfish.hk2.api.Proxiable;
import org.glassfish.hk2.api.ProxyCtl;
import org.glassfish.hk2.api.ServiceHandle;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;

import javax.inject.Scope;
import javax.inject.Singleton;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Runtime checks for the repackaged bundle. Each check asserts observable
 * behaviour -- bytes on the wire, a generated proxy class -- rather than merely
 * that some exception was thrown, so a check cannot pass on a bundle whose
 * providers are missing.
 */
public class JerseyBundleSmoke {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("java.version=" + System.getProperty("java.version"));
        System.out.println("note: a JUL WARNING about HK2 failing to reify DataSourceProvider "
                + "(NoClassDefFoundError javax/activation/DataSource) is EXPECTED on stderr here. "
                + "javax.activation is an OSGi Import-Package rather than an embedded jar, and it "
                + "was removed from the JDK in 11, so it is absent from this flat classpath but "
                + "present in AEM. The exit code and the SMOKE PASS/FAIL line are authoritative.");

        Recorder rec = new Recorder();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", rec);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        try {
            check("client-bootstrap", () -> {
                Client c = ClientBuilder.newBuilder().build();
                require(c.target("https://example.com").path("p").queryParam("q", "1")
                        .getUri().toString().equals("https://example.com/p?q=1"), "URI built");
            });

            // Asserts the Authorization header actually reaches the wire. The
            // previous form only asserted that a connect to a dead port threw
            // ProcessingException, which stays true if the auth feature is
            // broken or absent entirely.
            check("client-basic-auth-header-on-wire", () -> {
                ClientConfig cfg = new ClientConfig();
                cfg.register(HttpAuthenticationFeature.basic("u", "p"));
                Client c = ClientBuilder.newClient(cfg);
                Response r = c.target(base).path("/auth").request().get();
                require(r.getStatus() == 200, "200 from local server");
                String expected = "Basic " + Base64.getEncoder()
                        .encodeToString("u:p".getBytes(StandardCharsets.UTF_8));
                require(expected.equals(rec.lastAuth),
                        "Authorization header " + expected + " but was " + rec.lastAuth);
                c.close();
            });

            // Asserts the entity was serialised and delivered. A connect
            // failure never invokes a MessageBodyWriter, so the old form could
            // not detect a bundle with no writers registered.
            check("client-post-entity-on-wire", () -> {
                Client c = ClientBuilder.newBuilder().build();
                Response r = c.target(base).path("/post").request()
                        .accept(MediaType.APPLICATION_JSON)
                        .post(Entity.entity("{\"k\":\"v\"}", MediaType.APPLICATION_JSON));
                require(r.getStatus() == 200, "200 from local server");
                require("{\"k\":\"v\"}".equals(rec.lastBody),
                        "posted body round-tripped, was " + rec.lastBody);
                require(rec.lastContentType != null
                                && rec.lastContentType.startsWith(MediaType.APPLICATION_JSON),
                        "Content-Type application/json, was " + rec.lastContentType);
                require("hello".equals(r.readEntity(String.class)), "response entity read");
                c.close();
            });

            // Connection failures should still surface as a ConnectException
            // root cause rather than any ProcessingException, which Jersey also
            // uses for missing providers and connector faults.
            check("connect-failure-is-connect-exception", () -> {
                Client c = ClientBuilder.newBuilder().build();
                try {
                    c.target("http://127.0.0.1:1/x").request().get();
                    throw new AssertionError("expected connection failure, request succeeded");
                } catch (ProcessingException expected) {
                    Throwable root = expected;
                    while (root.getCause() != null) root = root.getCause();
                    require(root instanceof ConnectException,
                            "root cause ConnectException, was " + root.getClass().getName());
                }
                c.close();
            });

            // The reason javassist is pinned: 3.20.0-GA cannot define classes
            // on JDK 16+. Subclassing an application class (not a JDK
            // interface) makes javassist define the proxy in this bundle's own
            // package, which is the path that actually broke.
            check("javassist-proxy-generation", () -> {
                ProxyFactory f = new ProxyFactory();
                f.setSuperclass(ProxiedService.class);
                Class<?> proxy = f.createClass();
                require(proxy != null, "proxy class created");
                require(ProxiedService.class.isAssignableFrom(proxy), "proxy subclasses the service");
            });

            // End-to-end through HK2's own proxy machinery, which is what
            // actually needs javassist at runtime -- the raw ProxyFactory check
            // above does not touch HK2's ClassLoaderProvider wiring.
            check("hk2-proxiable-service", () -> {
                ServiceLocator locator = ServiceLocatorUtilities.createAndPopulateServiceLocator(
                        "smoke-" + System.nanoTime());
                ServiceLocatorUtilities.addClasses(locator,
                        ProxiableScopeContext.class, ProxiedService.class);
                ProxiedService svc = locator.getService(ProxiedService.class);
                require(svc != null, "service resolved");
                require(svc instanceof ProxyCtl, "resolved service is an HK2 proxy");
                require("real".equals(svc.value()), "proxy delegates to the real instance");
                locator.shutdown();
            });
        } finally {
            server.stop(0);
        }

        System.out.println(failures == 0 ? "SMOKE PASS" : "SMOKE FAIL (" + failures + ")");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Captures what the client actually sent. */
    private static final class Recorder implements com.sun.net.httpserver.HttpHandler {
        volatile String lastAuth;
        volatile String lastBody;
        volatile String lastContentType;

        @Override
        public void handle(HttpExchange ex) throws java.io.IOException {
            lastAuth = ex.getRequestHeaders().getFirst("Authorization");
            lastContentType = ex.getRequestHeaders().getFirst("Content-Type");
            try (InputStream in = ex.getRequestBody()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                lastBody = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        }
    }

    @Scope
    @Proxiable
    @Retention(RetentionPolicy.RUNTIME)
    @Target({java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD})
    public @interface ProxiableScope {
    }

    /** Minimal always-active context so HK2 will proxy services in the scope. */
    @Singleton
    public static class ProxiableScopeContext implements Context<ProxiableScope> {
        private final Map<ActiveDescriptor<?>, Object> instances = new HashMap<>();

        @Override
        public Class<? extends Annotation> getScope() {
            return ProxiableScope.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <U> U findOrCreate(ActiveDescriptor<U> descriptor, ServiceHandle<?> root) {
            Object existing = instances.get(descriptor);
            if (existing == null) {
                existing = descriptor.create(root);
                instances.put(descriptor, existing);
            }
            return (U) existing;
        }

        @Override
        public boolean containsKey(ActiveDescriptor<?> descriptor) {
            return instances.containsKey(descriptor);
        }

        @Override
        public void destroyOne(ActiveDescriptor<?> descriptor) {
            instances.remove(descriptor);
        }

        @Override
        public boolean supportsNullCreation() {
            return false;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void shutdown() {
            instances.clear();
        }
    }

    @ProxiableScope
    public static class ProxiedService {
        public String value() {
            return "real";
        }
    }

    private interface Check {
        void run() throws Exception;
    }

    private static void check(String name, Check c) {
        try {
            c.run();
            System.out.println("  PASS  " + name);
        } catch (Throwable t) {
            failures++;
            System.out.println("  FAIL  " + name + " -> " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private static void require(boolean cond, String what) {
        if (!cond) throw new AssertionError("expected: " + what);
    }
}
