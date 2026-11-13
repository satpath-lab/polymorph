import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
import UserNotifications

/// Runs a script-driven screen. Use PolyScreen(source:) inside any SwiftUI app, or let PolyMain run a whole app.
public final class PolyModel: ObservableObject {
    @Published var screen: [String: Any] = [:]
    @Published var failure: String?
    @Published var toast: String?
    @Published var dialog: [String: Any]?
    var inputs: [String: String] = [:]
    var engine: PolyEngine?
    var state: Any?
    var dir: URL?
    let source: ScriptSource
    let key: String
    let dev: String?
    var version = -1
    var onFinish: ((Any?) -> Void)?
    var setup: ((PolyEngine) -> Void)?

    public init(source: ScriptSource, key: String = "main", dev: String? = nil) {
        self.source = source; self.key = key; self.dev = dev
        state = UserDefaults.standard.object(forKey: "poly.state.\(key)").flatMap { JSON.parse($0 as? String ?? "") }
        if let s = UserDefaults.standard.string(forKey: "poly.ui.\(key)"), let ui = JSON.parse(s) as? [String: Any] { screen = ui }
    }

    var accent: Color { Color(hex: screen["accent"]) ?? Color(red: 0.42, green: 0.23, blue: 1) }

    func styled(_ n: [String: Any]) -> [String: Any] {
        var out: [String: Any] = [:]
        if let theme = screen["theme"] as? [String: Any] {
            (theme["all"] as? [String: Any])?.forEach { out[$0] = $1 }
            (theme[n["type"] as? String ?? "column"] as? [String: Any])?.forEach { out[$0] = $1 }
        }
        if let styles = screen["styles"] as? [String: Any], let st = n["style"] {
            for name in (st as? [Any] ?? [st]) { (styles["\(name)"] as? [String: Any])?.forEach { out[$0] = $1 } }
        }
        n.forEach { out[$0] = $1 }
        return out
    }

    func mediaURL(_ src: String) -> URL? {
        if src.isEmpty { return nil }
        if src.hasPrefix("http") || src.hasPrefix("file:") { return URL(string: src) }
        if src.hasPrefix("/") { return URL(fileURLWithPath: src) }
        return dir?.appendingPathComponent("assets").appendingPathComponent(src)
    }

    func start() {
        PolyHost.ui = { [weak self] kind, v in self?.hostUI(kind, v) }
        DispatchQueue.global().async {
            do {
                let d: URL
                if let dev = self.dev {
                    d = ScriptSource.store.appendingPathComponent("dev")
                    try? FileManager.default.removeItem(at: d)
                    try Zip.extract(try HTTP.get(URL(string: "\(dev)/api/bundle/\(self.key)?ch=dev")!), to: d)
                } else {
                    if case .url = self.source {
                        do { _ = try self.source.fetch() } catch { print("script update failed:", error) }
                        d = (try? self.source.resolve()) ?? PolyEngine.resources.appendingPathComponent("app")
                    } else { d = try self.source.resolve() }
                }
                try self.open(d)
                if self.dev != nil { self.watch() }
            } catch { DispatchQueue.main.async { self.failure = "\(error)" } }
        }
    }

    func open(_ d: URL) throws {
        let e = try PolyEngine(dir: d)
        setup?(e)
        engine = e; dir = d
        send(nil, nil)
    }

    func watch() {
        guard let dev, let u = URL(string: "\(dev)/api/watch/\(key)?ch=dev&v=\(version)") else { return }
        var req = URLRequest(url: u); req.timeoutInterval = 40
        URLSession.shared.dataTask(with: req) { d, _, _ in
            let v = (d.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any])?["v"] as? Int ?? self.version
            if v != self.version {
                let first = self.version < 0
                self.version = v
                if !first { self.reload() }
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + (d == nil ? 2 : 0)) { self.watch() }
        }.resume()
    }

    func reload() {
        DispatchQueue.global().async {
            do {
                if let dev = self.dev {
                    let d = ScriptSource.store.appendingPathComponent("dev")
                    try? FileManager.default.removeItem(at: d)
                    try Zip.extract(try HTTP.get(URL(string: "\(dev)/api/bundle/\(self.key)?ch=dev")!), to: d)
                    try self.open(d)
                } else {
                    if (try? self.source.fetch()) == true { try self.open(try self.source.resolve()) }
                }
            } catch { DispatchQueue.main.async { self.failure = "\(error)" } }
        }
    }

    func send(_ type: String?, _ id: String?, _ value: Any? = nil) {
        guard let e = engine else { return }
        var ev: [String: Any]? = nil
        if let type {
            ev = ["type": type, "id": id ?? "", "inputs": inputs, "store": UserDefaults.standard.dictionary(forKey: "poly.store") ?? [:]]
            if let value { ev?["value"] = value }
        }
        let st = state
        e.queue.async {
            do {
                let r = try e.step(state: st, event: ev)
                var newState = r["state"]
                var fx: [String: Any] = [:]
                if var d = newState as? [String: Any] {
                    for k in d.keys where k.hasPrefix("_") { fx[String(k.dropFirst())] = d.removeValue(forKey: k) }
                    newState = d
                }
                let ui = r["ui"] as? [String: Any]
                DispatchQueue.main.async {
                    self.state = newState
                    UserDefaults.standard.set(JSON.string(newState ?? NSNull()), forKey: "poly.state.\(self.key)")
                    if let ui { self.screen = ui; self.failure = nil; self.dialog = ui["dialog"] as? [String: Any]; UserDefaults.standard.set(JSON.string(ui), forKey: "poly.ui.\(self.key)") }
                    self.effects(fx)
                }
            } catch { DispatchQueue.main.async { self.failure = "\(error)" } }
        }
    }

    func effects(_ fx: [String: Any]) {
        for (k, v) in fx {
            switch k {
            case "toast": hostUI("toast", v)
            case "open": if let u = URL(string: str(v)) { hostUI("open", u) }
            case "vibrate": hostUI("vibrate", nil)
            case "clipboard": _ = try? PolyHost.dispatch(engine, "clipboard", "set", [["text": str(v)]])
            case "store":
                var s = UserDefaults.standard.dictionary(forKey: "poly.store") ?? [:]
                (v as? [String: Any])?.forEach { s[$0] = $1 is NSNull ? nil : $1 }
                UserDefaults.standard.set(s, forKey: "poly.store")
            case "notify":
                for case let n as [String: Any] in (v as? [Any] ?? [v]) {
                    let c = UNMutableNotificationContent(); c.title = str(n["title"]); c.body = str(n["text"])
                    let at = Double(num(n["at"]) ?? 0)
                    UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { ok, _ in
                        guard ok else { return }
                        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: at > 0 ? UNTimeIntervalNotificationTrigger(timeInterval: at, repeats: false) : nil))
                    }
                }
            case "finish": onFinish?(v)
            default: toast = "\(k) isn't available on Apple yet"
            }
        }
    }

    func hostUI(_ kind: String, _ v: Any?) {
        switch kind {
        case "toast":
            toast = str(v)
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.2) { if self.toast == str(v) { self.toast = nil } }
        case "open":
            #if canImport(UIKit)
            if let u = v as? URL { UIApplication.shared.open(u) }
            #else
            if let u = v as? URL { NSWorkspace.shared.open(u) }
            #endif
        case "vibrate":
            #if canImport(UIKit)
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            #endif
        case "update": reload()
        default: break
        }
    }
}

public struct PolyScreen: View {
    @StateObject var m: PolyModel

    public init(source: ScriptSource, key: String = "main", dev: String? = nil, setup: ((PolyEngine) -> Void)? = nil, onFinish: ((Any?) -> Void)? = nil) {
        let model = PolyModel(source: source, key: key, dev: dev)
        model.setup = setup; model.onFinish = onFinish
        _m = StateObject(wrappedValue: model)
    }

    public var body: some View {
        let body = (m.screen["body"] as? [String: Any]).map(m.styled)
        VStack(spacing: 0) {
            if let f = m.failure {
                ScrollView { VStack(alignment: .leading, spacing: 12) {
                    Text("⚠ Your script failed").font(.title2.bold()).foregroundColor(.red)
                    Text(f).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                    Text(m.dev != nil ? "Fix the code and save. The app reloads by itself." : "Restart the app after fixing the script.").foregroundColor(.secondary)
                }.padding(20) }
            } else if let body {
                if m.screen["scroll"] as? Bool == false { Node(n: body, vertical: true).frame(maxHeight: .infinity, alignment: .top) }
                else { ScrollView { Node(n: body, vertical: true) } }
                if let tabs = m.screen["tabs"] as? [String: Any] { tabBar(tabs) }
            } else { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity) }
        }
        .background((Color(hex: m.screen["bg"]) ?? Color(red: 0.96, green: 0.96, blue: 0.97)).ignoresSafeArea())
        .overlay(alignment: .bottom) {
            if let t = m.toast { Text(t).padding(.horizontal, 16).padding(.vertical, 10).background(.thinMaterial).clipShape(Capsule()).padding(.bottom, 40) }
        }
        .alert(str(m.dialog?["title"]), isPresented: Binding(get: { m.dialog != nil }, set: { if !$0 { m.dialog = nil } })) {
            ForEach(Array((m.dialog?["buttons"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }.enumerated()), id: \.offset) { _, b in
                Button(str(b["text"])) { m.send("tap", str(b["id"])) }
            }
        } message: { Text(str(m.dialog?["text"])) }
        .navigationTitle(str(m.screen["title"]))
        .environmentObject(m)
        .onAppear { if m.engine == nil { m.start(); PolyTest.run(m) } }
    }

    func tabBar(_ tabs: [String: Any]) -> some View {
        HStack {
            ForEach(Array((tabs["items"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }.enumerated()), id: \.offset) { _, t in
                let on = str(t["id"]) == str(tabs["selected"])
                Button { m.send("tap", str(t["id"])) } label: {
                    VStack(spacing: 2) { Text(str(t["icon"])).font(.title3); Text(str(t["label"])).font(.caption).foregroundColor(on ? m.accent : .secondary) }.frame(maxWidth: .infinity)
                }.buttonStyle(.plain).opacity(on ? 1 : 0.6)
            }
        }.padding(.vertical, 8).background(.bar)
    }
}

/// Test hook: POLY_TEST="tap:add,submit:new=Milk" sends events, POLY_SNAPSHOT=/path.png saves the window, then quits.
enum PolyTest {
    static func run(_ m: PolyModel) {
        let env = ProcessInfo.processInfo.environment
        guard let snap = env["POLY_SNAPSHOT"] else { return }
        let steps = (env["POLY_TEST"] ?? "").split(separator: ",").map(String.init)
        var t = 2.0
        for s in steps {
            let parts = s.split(separator: ":", maxSplits: 1).map(String.init)
            guard parts.count == 2 else { continue }
            DispatchQueue.main.asyncAfter(deadline: .now() + t) {
                let kv = parts[0] == "submit" ? parts[1].split(separator: "=", maxSplits: 1).map(String.init) : [parts[1]]
                if kv.count == 2 { m.inputs[kv[0]] = kv[1] }
                m.send(parts[0], kv[0])
            }
            t += 0.6
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + t + 1.2) {
            print("POLY_STATE", JSON.string(m.state ?? NSNull()))
            print("POLY_TITLE", str(m.screen["title"]))
            if let f = m.failure { print("POLY_ERROR", f) }
            let body = (m.screen["body"] as? [String: Any]).map(m.styled) ?? [:]
            let r = ImageRenderer(content: Node(n: body, vertical: true).environmentObject(m).frame(width: 420).padding(.vertical, 8)
                .background(Color(hex: m.screen["bg"]) ?? Color(red: 0.96, green: 0.96, blue: 0.97)))
            r.scale = 2
            #if os(macOS)
            try? r.nsImage.flatMap { $0.tiffRepresentation }.flatMap { NSBitmapImageRep(data: $0)?.representation(using: .png, properties: [:]) }?.write(to: URL(fileURLWithPath: snap))
            NSApp.terminate(nil)
            #endif
        }
    }
}

#if POLY_APP
@main
struct PolyMain: App {
    let cfg: [String: Any] = (Bundle.main.url(forResource: "poly", withExtension: "json").flatMap { try? Data(contentsOf: $0) }.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]) ?? [:]
    var body: some Scene {
        WindowGroup {
            let src: ScriptSource = (cfg["source"] as? String) == "remote"
                ? .url(URL(string: str(cfg["url"]))!, publicKey: cfg["publicKey"] as? String)
                : .bundled("app")
            NavigationStack { PolyScreen(source: src, key: str(cfg["app"]).isEmpty ? "main" : str(cfg["app"]), dev: (cfg["dev"] as? String).flatMap { $0.isEmpty ? nil : $0 }) }
        }
    }
}
#endif
