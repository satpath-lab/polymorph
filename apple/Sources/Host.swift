import Foundation
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// App-wide registry: anything exposed here is callable from every script as native.<name>.<method>(...)
public enum Poly {
    static var objects: [String: (String, [Any]) throws -> Any?] = [:]
    public static func expose(_ name: String, _ handler: @escaping (_ method: String, _ args: [Any]) throws -> Any?) { objects[name] = handler }
    public static func function(_ name: String, _ fn: @escaping ([Any]) throws -> Any?) { objects[name] = { _, a in try fn(a) } }
}

enum PolyHost {
    static var installed = false
    static var ui: ((String, Any?) -> Void)?

    static func install() {
        guard !installed else { return }
        installed = true
        poly_set_host { req in
            let out = PolyHost.handle(req.map { String(cString: $0) } ?? "{}")
            return strdup(out)
        }
    }

    static func handle(_ raw: String) -> String {
        let engine = DispatchQueue.getSpecific(key: PolyEngine.key)
        guard let r = JSON.parse(raw) as? [String: Any] else { return "{\"error\":\"bad request\"}" }
        switch r["op"] as? String ?? "" {
        case "log":
            print("[script]", r["text"] ?? "")
            engine?.emit("log", r["text"])
            return "{}"
        case "emit":
            engine?.emit(r["name"] as? String ?? "", decode(r["data"], engine))
            return "{}"
        default:
            do {
                let name = r["plugin"] as? String ?? "", method = r["method"] as? String ?? ""
                let a = r["args"]
                let args: [Any] = (a as? [Any] ?? [a ?? [:]]).map { decode($0, engine) ?? NSNull() }
                let v = try dispatch(engine, name, method, args)
                return JSON.string(["value": v ?? NSNull()])
            } catch { return JSON.string(["error": "\(error)"]) }
        }
    }

    static func decode(_ v: Any?, _ e: PolyEngine?) -> Any? {
        if let d = v as? [String: Any] {
            if d.count == 1, let id = d["__volt_cb"] as? Int, let e { return PolyCallback(e, id) }
            return d.mapValues { decode($0, e) ?? NSNull() }
        }
        if let a = v as? [Any] { return a.map { decode($0, e) ?? NSNull() } }
        return v
    }

    static func dispatch(_ e: PolyEngine?, _ name: String, _ method: String, _ args: [Any]) throws -> Any? {
        if let f = e?.objects[name] ?? Poly.objects[name] { return try f(method, args) }
        let o = args.first as? [String: Any] ?? [:]
        let ud = UserDefaults.standard
        switch name {
        case "device":
            #if canImport(UIKit)
            let (plat, model) = ("ios", UIDevice.current.model)
            #else
            let (plat, model) = ("macos", "Mac")
            #endif
            return ["platform": plat, "model": model, "os": ProcessInfo.processInfo.operatingSystemVersionString, "lang": Locale.current.identifier]
        case "storage":
            var s = ud.dictionary(forKey: "poly.store") ?? [:]
            let key = o["key"] as? String ?? (args.first as? String ?? "")
            switch method {
            case "get": return s[key]
            case "all": return s
            case "set": s[key] = o["value"] ?? (args.count > 1 ? args[1] : NSNull()); ud.set(s, forKey: "poly.store"); return true
            case "remove": s[key] = nil; ud.set(s, forKey: "poly.store"); return true
            default: break
            }
        case "clipboard":
            #if canImport(UIKit)
            if method == "set" { UIPasteboard.general.string = o["text"] as? String; return true }
            return UIPasteboard.general.string
            #else
            if method == "set" { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(o["text"] as? String ?? "", forType: .string); return true }
            return NSPasteboard.general.string(forType: .string)
            #endif
        case "toast": DispatchQueue.main.async { ui?("toast", o["text"] ?? method) }; return true
        case "open":
            if let u = URL(string: o["url"] as? String ?? method) { DispatchQueue.main.async { ui?("open", u) } }
            return true
        case "vibrate": DispatchQueue.main.async { ui?("vibrate", nil) }; return true
        case "http":
            guard let u = URL(string: o["url"] as? String ?? method) else { throw PolyError.script("http needs a url") }
            var req = URLRequest(url: u, timeoutInterval: (o["timeout"] as? Double ?? 15000) / 1000)
            req.httpMethod = (o["method"] as? String ?? "GET").uppercased()
            (o["headers"] as? [String: Any])?.forEach { req.setValue("\($1)", forHTTPHeaderField: $0) }
            if let b = o["body"], !(b is NSNull) {
                if let s = b as? String { req.httpBody = Data(s.utf8) } else { req.httpBody = Data(JSON.string(b).utf8); req.setValue("application/json", forHTTPHeaderField: "Content-Type") }
            }
            let (d, status, h) = try HTTP.send(req)
            let text = String(decoding: d, as: UTF8.self)
            var r: [String: Any] = ["status": status, "ok": status < 400, "body": text, "headers": h]
            if o["json"] as? Bool == true, let j = JSON.parse(text) { r["json"] = j }
            return r
        case "files":
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("poly-user")
            try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
            let f = base.appendingPathComponent(o["path"] as? String ?? "").standardizedFileURL
            guard f.path.hasPrefix(base.standardizedFileURL.path) else { throw PolyError.script("path outside app storage") }
            switch method {
            case "read": return try? String(contentsOf: f, encoding: .utf8)
            case "write": try FileManager.default.createDirectory(at: f.deletingLastPathComponent(), withIntermediateDirectories: true); try (o["text"] as? String ?? "").write(to: f, atomically: true, encoding: .utf8); return true
            case "delete": try? FileManager.default.removeItem(at: f); return true
            case "exists": return FileManager.default.fileExists(atPath: f.path)
            case "list": return (try? FileManager.default.contentsOfDirectory(atPath: f.path)) ?? []
            default: break
            }
        case "app":
            if method == "info" { return ["lang": e?.lang ?? "", "dir": e?.dir.path ?? ""] }
            if method == "update" { DispatchQueue.main.async { ui?("update", nil) }; return true }
        default: break
        }
        throw PolyError.script("nothing exposed as '\(name)' (use Poly.expose or engine.expose)")
    }
}
