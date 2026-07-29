import javassist.util.proxy.ProxyFactory;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;

public class JerseyBundleSmoke {

    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("java.version=" + System.getProperty("java.version"));

        check("client-bootstrap", () -> {
            Client c = ClientBuilder.newBuilder().build();
            require(c.target("https://example.com").path("p").queryParam("q", "1")
                    .getUri().toString().equals("https://example.com/p?q=1"), "URI built");
        });

        check("client-config-basic-auth", () -> {
            ClientConfig cfg = new ClientConfig();
            cfg.register(HttpAuthenticationFeature.basic("u", "p"));
            Client c = ClientBuilder.newClient(cfg);
            expectConnectFailure(() -> c.target("https://127.0.0.1:1/x").request().get());
        });

        check("client-post-entity", () -> {
            Client c = ClientBuilder.newBuilder().build();
            expectConnectFailure(() -> c.target("https://127.0.0.1:1/x").request()
                    .accept(MediaType.APPLICATION_JSON)
                    .post(Entity.entity("{}", MediaType.APPLICATION_JSON)));
        });

        check("javassist-proxy-generation", () -> {
            ProxyFactory f = new ProxyFactory();
            f.setInterfaces(new Class[]{Runnable.class});
            require(f.createClass() != null, "proxy class created");
        });

        System.out.println(failures == 0 ? "SMOKE PASS" : "SMOKE FAIL (" + failures + ")");
        System.exit(failures == 0 ? 0 : 1);
    }

    private interface Check { void run() throws Exception; }

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

    private static void expectConnectFailure(Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected connection failure, request succeeded");
        } catch (ProcessingException expected) {
            // Expected
        }
    }
}
