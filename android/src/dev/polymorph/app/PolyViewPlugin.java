package dev.polymorph.app;

import android.view.View;

import org.json.JSONObject;

/**
 * Native view inside a script screen: {type: "native", plugin: "ClassName", id: "...", props: {...}}.
 * create() runs once per id; update() runs on every re-render with the new props.
 */
public interface PolyViewPlugin {
    View create(PolyPlugin.Host host, JSONObject props) throws Exception;
    void update(View view, JSONObject props) throws Exception;
}
