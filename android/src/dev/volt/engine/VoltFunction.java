package dev.volt.engine;

import org.json.JSONArray;

public interface VoltFunction {
    Object call(JSONArray args) throws Exception;
}
