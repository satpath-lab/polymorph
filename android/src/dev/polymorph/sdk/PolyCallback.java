package dev.polymorph.sdk;

import org.json.JSONArray;

public final class PolyCallback {
    final PolyEngine engine;
    final long id;

    PolyCallback(PolyEngine engine, long id) { this.engine = engine; this.id = id; }

    public void call(Object... args) { engine.post(() -> { try { engine.callback(id, args, false); } catch (Exception e) { engine.emit("error", String.valueOf(e.getMessage())); } }); }

    public Object callSync(Object... args) throws Exception { return engine.run(() -> engine.callback(id, args, true)); }

    public void release() { engine.post(() -> { try { engine.raw("__release", new JSONArray().put(id).toString()); } catch (Exception ignored) {} }); }

    @Override public String toString() { return "PolyCallback#" + id; }
}
