package com.ecommerce.product.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.product.exception.GlobalExceptionHandler;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the service does when its database is stuck or saturated, on PostgreSQL with the production schema:
 * a bounded wait for a row lock (D2), a bounded wait for a connection (D1), 503 instead of 500 (L4) and a
 * bounded number of log lines however many requests fail (L3). Also the bounds on list results (D3).
 *
 * <p>Same {@code @SpringBootTest} configuration as {@link ProductApiIT}, so the application context and the
 * Postgres container are shared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "management.tracing.sampling.probability=0.0"
})
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ProductResilienceIT extends PostgresIntegrationTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ListAppender<ILoggingEvent> handlerLog = new ListAppender<>();
    private Logger handlerLogger;
    /** Every logger, so that Hibernate's, Spring's and Hikari's own lines are counted too. */
    private final ListAppender<ILoggingEvent> everything = new ListAppender<>();
    private Logger rootLogger;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE products, categories RESTART IDENTITY CASCADE");
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        handlerLog.start();
        handlerLogger.addAppender(handlerLog);
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        everything.start();
        rootLogger.addAppender(everything);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(handlerLog);
        handlerLog.stop();
        rootLogger.detachAppender(everything);
        everything.stop();
        everything.list.clear();
        pool.shutdownNow();
    }

    @Test
    void connectionWaitIsBoundedAt5s_notTheHikariDefaultOf30s() throws SQLException {
        assertThat(dataSource.unwrap(HikariDataSource.class).getConnectionTimeout()).isEqualTo(5000);
    }

    /**
     * pgjdbc has no socket timeout by default: a database that stops answering without closing the connection
     * (dropped packets, a frozen host, a failover without a reset) would hold the request thread for minutes, long
     * after Hikari's 5 s, which only bounds the wait for a connection.
     */
    @Test
    void theDriverBoundsConnectAndRead_soAFrozenDatabaseCannotHoldAThreadForMinutes() throws SQLException {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);

        Properties driver = pool.getDataSourceProperties();
        assertThat(driver.getProperty("connectTimeout")).isEqualTo("5");
        assertThat(driver.getProperty("socketTimeout")).isEqualTo("30");
        assertThat(driver.getProperty("tcpKeepAlive")).isEqualTo("true");
        // and the driver really applied it: JDBC reports the socket read timeout of the live connection
        try (Connection connection = pool.getConnection()) {
            assertThat(connection.getNetworkTimeout()).isEqualTo(30_000);
        }
    }

    // ───────────────────────── D2: a stuck row lock ─────────────────────────

    /**
     * A holder keeps the product row locked (an admin's psql session, a stalled transaction). Buyers of that product
     * used to queue behind it for as long as it lasted, each pinning a pooled connection. Now they give up after
     * the lock timeout with a 503, and nothing is half-applied.
     */
    @Test
    void buyersQueuedBehindAStuckRowLock_failWith503AfterTheLockTimeout_andTheStockIsUntouched() throws Exception {
        long id = insertProduct("LOCK-1", 10);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> holdRowLock(id, locked, release));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        int buyers = 5;
        long start = System.nanoTime();
        List<Future<HttpResponse<String>>> responses = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            responses.add(pool.submit(() -> patchStock(id, -1)));
        }
        for (Future<HttpResponse<String>> response : responses) {
            HttpResponse<String> r = response.get(30, TimeUnit.SECONDS);
            assertThat(r.statusCode()).as(r.body()).isEqualTo(503);
            assertThat(r.body()).contains("\"message\":\"Service temporarily unavailable\"")
                    .doesNotContain("canceling statement").doesNotContain("postgres").doesNotContain("org.");
        }
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        assertThat(waited).as("gave up at the 5 s lock timeout").isBetween(Duration.ofMillis(4000), Duration.ofSeconds(12));
        assertThat(stockOf(id)).as("no buyer got through").isEqualTo(10);
        assertThat(handlerLog.list).as("%d failed requests, bounded log output", buyers)
                .hasSizeLessThanOrEqualTo(1)
                .allSatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.WARN);
                    assertThat(e.getThrowableProxy()).isNull();
                    assertThat(e.getFormattedMessage()).contains("lock timeout");
                });
        assertNothingElseIsLoud();

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        // The lock is free again: the very next buyer is served at once and exactly one unit is taken.
        long freeAt = System.nanoTime();
        assertThat(patchStock(id, -1).statusCode()).isEqualTo(200);
        assertThat(Duration.ofNanos(System.nanoTime() - freeAt)).isLessThan(Duration.ofSeconds(3));
        assertThat(stockOf(id)).isEqualTo(9);
    }

    // ───────────────────────── D1: a saturated pool ─────────────────────────

    @Test
    void whenEveryConnectionIsTaken_requestsFailWith503Within5s_logOneLine_andRecover() throws Exception {
        long id = insertProduct("POOL-1", 3);
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        List<Connection> taken = new ArrayList<>();
        try {
            for (int i = 0; i < hikari.getMaximumPoolSize(); i++) {
                taken.add(hikari.getConnection());
            }

            int clients = 20;
            long start = System.nanoTime();
            List<Future<HttpResponse<String>>> responses = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                responses.add(pool.submit(() -> get("/api/v1/products/" + id)));
            }
            for (Future<HttpResponse<String>> response : responses) {
                HttpResponse<String> r = response.get(30, TimeUnit.SECONDS);
                assertThat(r.statusCode()).as(r.body()).isEqualTo(503);
                assertThat(r.body()).contains("\"message\":\"Service temporarily unavailable\"").doesNotContain("Hikari");
            }
            Duration waited = Duration.ofNanos(System.nanoTime() - start);

            assertThat(waited).as("requests wait the 5 s connection timeout, not 30 s").isBetween(Duration.ofMillis(4000), Duration.ofSeconds(12));
            assertThat(handlerLog.list).as("%d failed requests, bounded log output", clients)
                    .hasSizeLessThanOrEqualTo(1)
                    .allSatisfy(e -> {
                        assertThat(e.getLevel()).isEqualTo(Level.WARN);
                        assertThat(e.getThrowableProxy()).isNull();
                        assertThat(e.getFormattedMessage()).as("the line names the reason, not just the wrapper").contains("request timed out");
                    });
            assertNothingElseIsLoud();
        } finally {
            for (Connection c : taken) {
                c.close();
            }
        }

        assertThat(get("/api/v1/products/" + id).statusCode()).as("recovers as soon as connections are free").isEqualTo(200);
    }

    /**
     * A killed backend or a database restart in the middle of a transaction: the statement fails, then the rollback
     * fails on the dead connection and its exception replaces the original one. That used to reach the client as a 500
     * and the log as one stack trace of several hundred lines per broken connection.
     */
    @Test
    void aKilledDatabaseBackend_midTransaction_is503_withNoRollbackOverrideTrace_andTheServiceRecovers() throws Exception {
        long id = insertProduct("KILL-1", 10);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> holdRowLock(id, locked, release));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<HttpResponse<String>> buyer = pool.submit(() -> patchStock(id, -1));
        // the buyer's transaction is now waiting for the row lock: find its backend and kill it
        int killed = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (killed == 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
            killed = jdbc.queryForObject("SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity "
                    + "WHERE wait_event_type = 'Lock' AND datname = current_database() AND pid <> pg_backend_pid()", Integer.class);
        }
        assertThat(killed).as("a backend waiting for the lock was found and terminated").isEqualTo(1);

        HttpResponse<String> response = buyer.get(30, TimeUnit.SECONDS);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        assertThat(response.body()).contains("\"message\":\"Service temporarily unavailable\"");
        assertThat(handlerLog.list).hasSizeLessThanOrEqualTo(1).allSatisfy(e -> assertThat(e.getThrowableProxy()).isNull());
        assertNothingElseIsLoud();
        assertThat(stockOf(id)).as("the killed transaction changed nothing").isEqualTo(10);
        assertThat(patchStock(id, -1).statusCode()).as("the pool replaces the broken connection").isEqualTo(200);
        assertThat(stockOf(id)).isEqualTo(9);
    }

    @Test
    void reads_doNotUseOpenEntityManagerInView() {
        assertThat(context.getBeansOfType(OpenEntityManagerInViewInterceptor.class))
                .as("a read outside a transaction would keep its connection until the response was written").isEmpty();
    }

    /** Hibernate (two lines per failed JDBC call) and Spring's rollback-override trace are off: only our own throttled line is left. */
    private void assertNothingElseIsLoud() {
        List<ILoggingEvent> loud = everything.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList();
        assertThat(loud).as("WARN/ERROR lines from every logger: %s", loud.stream().map(e -> e.getLoggerName() + ": " + e.getFormattedMessage()).toList())
                .noneMatch(e -> e.getLoggerName().startsWith("org.hibernate"))
                .noneMatch(e -> e.getLoggerName().equals("org.springframework.transaction.interceptor.TransactionInterceptor"))
                .hasSizeLessThanOrEqualTo(2);
    }

    // ───────────────────────── D3: results are bounded ─────────────────────────

    @Test
    void categoryList_isBoundedByDesign() throws Exception {
        jdbc.update("INSERT INTO categories (name, created_at) SELECT 'cat-' || g, now() FROM generate_series(1, 1100) g");

        HttpResponse<String> r = get("/api/v1/categories");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body().split("\"parentCategoryId\"", -1).length - 1).isEqualTo(1000);
        assertThat(r.body()).startsWith("[{\"id\":1,").doesNotContain("\"name\":\"cat-1100\"");
    }

    @Test
    void productsOfACategory_comeInPagesOfAtMost100_activeOnly_inIdOrder() throws Exception {
        jdbc.update("INSERT INTO categories (id, name, created_at) VALUES (10, 'Big', now())");
        jdbc.update("INSERT INTO products (sku, name, price, stock_quantity, category_id, status, created_at) "
                + "SELECT 'P-' || g, 'Product ' || g, 1.00, 5, 10, 'ACTIVE', now() FROM generate_series(1, 150) g");
        jdbc.update("INSERT INTO products (sku, name, price, stock_quantity, category_id, status, created_at) "
                + "SELECT 'GONE-' || g, 'Gone ' || g, 1.00, 0, 10, 'INACTIVE', now() FROM generate_series(1, 20) g");

        String first = get("/api/v1/products/category/10").body();
        String second = get("/api/v1/products/category/10?page=1&size=100").body();

        assertThat(first.split("\"sku\"", -1).length - 1).as("default page").isEqualTo(100);
        assertThat(first).contains("\"sku\":\"P-1\"").contains("\"sku\":\"P-100\"").doesNotContain("\"sku\":\"P-101\"").doesNotContain("GONE");
        assertThat(second.split("\"sku\"", -1).length - 1).as("the remaining active products").isEqualTo(50);
        assertThat(second).contains("\"sku\":\"P-101\"").contains("\"sku\":\"P-150\"").doesNotContain("GONE");
        assertThat(get("/api/v1/products/category/10?size=101").statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/products/category/10?size=2147483647").statusCode()).isEqualTo(400);
    }

    // ───────────────────────── helpers ─────────────────────────

    private long insertProduct(String sku, int stock) {
        jdbc.update("INSERT INTO products (sku, name, price, stock_quantity, status, created_at) VALUES (?, ?, 5.00, ?, 'ACTIVE', now())",
                sku, "Product " + sku, stock);
        return jdbc.queryForObject("SELECT id FROM products WHERE sku = ?", Long.class, sku);
    }

    private int stockOf(long id) {
        return jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, id);
    }

    /** Locks the row the way a stalled transaction would and keeps the lock until told to let go. */
    private void holdRowLock(long id, CountDownLatch locked, CountDownLatch release) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT id FROM products WHERE id = " + id + " FOR UPDATE");
            }
            locked.countDown();
            release.await(60, TimeUnit.SECONDS);
            c.rollback();
        } catch (SQLException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> patchStock(long id, int delta) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v1/products/" + id + "/stock"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"delta\":" + delta + "}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
