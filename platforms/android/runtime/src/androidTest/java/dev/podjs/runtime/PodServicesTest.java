package dev.podjs.runtime;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class PodServicesTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    @Before public void resetDatabase() { context().deleteDatabase("podjs.sqlite"); }

    private PodServices newService(final AtomicReference<String> line, final AtomicReference<CountDownLatch> latch) {
        return new PodServices(context(), event -> { line.set(event); CountDownLatch current = latch.get(); if (current != null) current.countDown(); });
    }

    private JSONObject request(final PodServices services, final AtomicReference<String> line,
                               final AtomicReference<CountDownLatch> latch, final JSONObject command) throws Exception {
        CountDownLatch current = new CountDownLatch(1); latch.set(current);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { services.dispatch(command); } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertTrue("service response timed out", current.await(5, TimeUnit.SECONDS));
        return new JSONObject(line.get());
    }

    private JSONObject command(int id, String method, JSONObject args) throws Exception {
        return new JSONObject().put("t", "service.request").put("version", 1)
                .put("id", id).put("method", method).put("args", args);
    }

    @Test public void transactionRollsBackOnStatementFailure() throws Exception {
        AtomicReference<String> line = new AtomicReference<>(); AtomicReference<CountDownLatch> latch = new AtomicReference<>();
        PodServices services = newService(line, latch);
        JSONObject created = request(services, line, latch, command(1, "sql.execute",
                new JSONObject().put("sql", "CREATE TABLE tx_test (id INTEGER PRIMARY KEY, value TEXT)")));
        assertTrue(created.getBoolean("ok"));
        JSONArray statements = new JSONArray().put(new JSONObject().put("sql", "INSERT INTO tx_test(value) VALUES ('committed?')"))
                .put(new JSONObject().put("sql", "INSERT INTO missing_table(value) VALUES ('rollback')"));
        JSONObject failed = request(services, line, latch, command(2, "sql.transaction", new JSONObject().put("statements", statements)));
        assertFalse(failed.getBoolean("ok"));
        JSONObject query = request(services, line, latch, command(3, "sql.query",
                new JSONObject().put("sql", "SELECT COUNT(*) AS count FROM tx_test")));
        assertEquals(0, ((JSONArray) query.get("value")).getJSONObject(0).getInt("count"));
        services.close();
    }

    @Test public void queryBoundsAndPagingAreEnforced() throws Exception {
        AtomicReference<String> line = new AtomicReference<>(); AtomicReference<CountDownLatch> latch = new AtomicReference<>();
        PodServices services = newService(line, latch);
        JSONObject setup = request(services, line, latch, command(10, "sql.execute",
                new JSONObject().put("sql", "CREATE TABLE page_test (id INTEGER)")));
        assertTrue(setup.getBoolean("ok"));
        JSONArray inserts = new JSONArray();
        for (int i = 0; i < 1001; i++) inserts.put(new JSONObject().put("sql", "INSERT INTO page_test(id) VALUES (?)")
                .put("params", new JSONArray().put(i)));
        JSONObject populated = request(services, line, latch, command(11, "sql.transaction",
                new JSONObject().put("statements", inserts)));
        assertTrue(populated.getBoolean("ok"));
        JSONObject tooMany = request(services, line, latch, command(2000, "sql.query",
                new JSONObject().put("sql", "SELECT id FROM page_test ORDER BY id")));
        assertFalse(tooMany.getBoolean("ok"));
        JSONObject page = request(services, line, latch, command(2001, "sql.query",
                new JSONObject().put("sql", "SELECT id FROM page_test WHERE id >= ? ORDER BY id LIMIT ?")
                        .put("params", new JSONArray().put(20).put(3))));
        JSONArray rows = page.getJSONArray("value");
        assertEquals(3, rows.length()); assertEquals(20, rows.getJSONObject(0).getInt("id"));
        services.close();
    }

    @Test public void databasePersistsAcrossPodServicesInstances() throws Exception {
        AtomicReference<String> line = new AtomicReference<>(); AtomicReference<CountDownLatch> latch = new AtomicReference<>();
        PodServices first = newService(line, latch);
        assertTrue(request(first, line, latch, command(30, "sql.execute", new JSONObject()
                .put("sql", "CREATE TABLE persist_test (value TEXT)"))).getBoolean("ok"));
        assertTrue(request(first, line, latch, command(31, "sql.execute", new JSONObject()
                .put("sql", "INSERT INTO persist_test(value) VALUES ('survives')"))).getBoolean("ok"));
        first.close();
        PodServices second = newService(line, latch);
        JSONObject result = request(second, line, latch, command(32, "sql.query", new JSONObject()
                .put("sql", "SELECT value FROM persist_test")));
        assertEquals("survives", result.getJSONArray("value").getJSONObject(0).getString("value"));
        second.close();
    }
}
