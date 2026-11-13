# Example: VoltForge as an SDK in an existing app

`HostActivity.java` is an ordinary Android activity. It uses `voltengine.aar` to:

1. Call a Python function (`price`) that calls back into a Java object (`Tax`) and a Java lambda (`hostVersion`).
2. Pass a Python callback to Java (`Clock.every`). Java calls it later, and Python sends `tick` events to a Java listener.
3. Replace the Python script at runtime with JavaScript from a string.
4. Replace it again with a signed Lua bundle downloaded from `volt.py serve` (`/b/pricing-lua.zip`).
5. Open a Lua-scripted screen and receive its result in `onActivityResult`.

```bash
python3 examples/android-host/build.py       # → data/build/android-host.apk
python3 volt.py serve                         # for step 4 (the emulator reaches it at 10.0.2.2:8787)
```

In a Gradle project you would instead copy the `.aar` into `app/libs/` and add `implementation(files("libs/voltengine.aar"))`. `build.py` does the same steps by hand so this repo needs no Android Studio.
