package dev.polymorph.sdk;

import org.json.JSONArray;

public interface PolyFunction {
    Object call(JSONArray args) throws Exception;
}
