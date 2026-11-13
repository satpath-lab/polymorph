import Foundation
import CryptoKit
import Compression

public enum PolyError: Error, CustomStringConvertible {
    case script(String), load(String)
    public var description: String { switch self { case .script(let s), .load(let s): return s } }
}

/// One running script. Every call runs on the engine's own queue, so scripts never block the UI.
public final class PolyEngine {
    public let lang: String
    public private(set) var dir: URL
    let queue: DispatchQueue
    private var handle: UnsafeMutableRawPointer?
    var objects: [String: (String, [Any]) throws -> Any?] = [:]
    var listeners: [String: [(Any?) -> Void]] = [:]
    static let key = DispatchSpecificKey<PolyEngine>()
    static var resources: URL = Bundle.main.resourceURL ?? Bundle.main.bundleURL

    static let mains = ["main.lua": "lua", "main.js": "js", "main.py": "python", "main.rb": "ruby", "main.php": "php"]
    static let ext = ["lua": "lua", "js": "js", "python": "py", "ruby": "rb", "php": "php"]

    public init(dir: URL, main: String? = nil) throws {
        let m = main ?? PolyEngine.mains.keys.sorted().first { FileManager.default.fileExists(atPath: dir.appendingPathComponent($0).path) }
        guard let m, let l = PolyEngine.mains[m] ?? PolyEngine.mains["main." + (m as NSString).pathExtension] else { throw PolyError.load("no main.lua / main.js / main.py / main.rb / main.php in \(dir.path)") }
        guard poly_has(l) != 0 else { throw PolyError.load("this app was built without \(l)") }
        lang = l; self.dir = dir
        queue = DispatchQueue(label: "poly.script.\(l)")
        queue.setSpecific(key: PolyEngine.key, value: self)
        PolyHost.install()
        if l == "python" {
            let fw = Bundle.main.privateFrameworksURL?.appendingPathComponent("Python.framework/Versions/Current")
            let home = fw.flatMap { FileManager.default.fileExists(atPath: $0.appendingPathComponent("lib").path) ? $0 : nil } ?? PolyEngine.resources.appendingPathComponent("python")
            setenv("VOLT_PYHOME", home.path, 1)
        }
        let harness = PolyEngine.resources.appendingPathComponent("poly/harness.\(PolyEngine.ext[l]!)").path
        var err: UnsafeMutablePointer<CChar>?
        let h = queue.sync { poly_open(l, dir.path, dir.appendingPathComponent(m).path, harness, &err) }
        if h == nil {
            let msg = err.map { String(cString: $0) } ?? "engine failed to start"
            if let err { poly_free(err) }
            throw PolyError.load(msg)
        }
        handle = h
    }

    deinit { if let h = handle { let l = lang; queue.sync { poly_close(l, h) } } }

    public func call(_ fn: String, _ json: String) -> String {
        let run = { () -> String in
            guard let h = self.handle, let r = poly_call(self.lang, h, fn, json) else { return "{\"error\":\"engine closed\"}" }
            defer { poly_free(r) }
            return String(cString: r)
        }
        return DispatchQueue.getSpecific(key: PolyEngine.key) === self ? run() : queue.sync(execute: run)
    }

    public func step(state: Any?, event: Any?) throws -> [String: Any] {
        let r = JSON.parse(call("step", JSON.string(["state": state ?? NSNull(), "event": event ?? NSNull()]))) as? [String: Any] ?? [:]
        if let e = r["error"] { throw PolyError.script("\(e)") }
        return r
    }

    @discardableResult public func invoke(_ fn: String, _ args: Any...) throws -> Any? {
        let r = JSON.parse(call(fn, JSON.string(args))) as? [String: Any] ?? [:]
        if let e = r["error"] { throw PolyError.script("\(e)") }
        return r["value"].flatMap { $0 is NSNull ? nil : $0 }
    }

    public func invokeAsync(_ fn: String, _ args: Any..., done: @escaping (Any?, Error?) -> Void) {
        queue.async {
            let r = JSON.parse(self.call(fn, JSON.string(args))) as? [String: Any] ?? [:]
            DispatchQueue.main.async { if let e = r["error"] { done(nil, PolyError.script("\(e)")) } else { done(r["value"], nil) } }
        }
    }

    /// Make Swift code callable from scripts as native.<name>.<method>(...)
    @discardableResult public func expose(_ name: String, _ handler: @escaping (String, [Any]) throws -> Any?) -> PolyEngine { objects[name] = handler; return self }
    @discardableResult public func on(_ event: String, _ fn: @escaping (Any?) -> Void) -> PolyEngine { listeners[event, default: []].append(fn); return self }

    func emit(_ name: String, _ data: Any?) {
        let fns = (listeners[name] ?? []) + (listeners["*"] ?? [])
        DispatchQueue.main.async { fns.forEach { $0(data) } }
    }
}

/// A script function handed to Swift; call it later to run it inside the script.
public final class PolyCallback {
    weak var engine: PolyEngine?
    let id: Int
    init(_ e: PolyEngine, _ id: Int) { engine = e; self.id = id }
    public func call(_ args: Any...) {
        guard let e = engine else { return }
        e.queue.async { _ = e.call("__cb", JSON.string([self.id] + args)) }
    }
}

// MARK: - where the scripts come from

public enum ScriptSource {
    case bundled(String)
    case folder(URL)
    case code(file: String, text: String)
    case url(URL, publicKey: String?)

    static var store: URL {
        let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("poly-scripts")
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    public func resolve() throws -> URL {
        switch self {
        case .bundled(let p): return PolyEngine.resources.appendingPathComponent(p)
        case .folder(let u): return u
        case .code(let f, let t):
            let d = ScriptSource.store.appendingPathComponent("inline-\(abs(t.hashValue))")
            try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
            try t.write(to: d.appendingPathComponent(f), atomically: true, encoding: .utf8)
            return d
        case .url(let u, _):
            let cur = ScriptSource.store.appendingPathComponent(Hash.short(u.absoluteString)).appendingPathComponent("current")
            if !FileManager.default.fileExists(atPath: cur.path) { _ = try fetch() }
            return cur
        }
    }

    /// Downloads a script bundle; verifies its signature; returns true if the code changed.
    public func fetch() throws -> Bool {
        guard case .url(let u, let key) = self else { return false }
        let zip = try HTTP.get(u)
        if let key, !key.isEmpty {
            let sigText = String(decoding: try HTTP.get(URL(string: u.absoluteString + ".sig")!), as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
            guard let der = Data(base64Encoded: key), let sig = Data(base64Encoded: sigText),
                  let pub = try? P256.Signing.PublicKey(derRepresentation: der),
                  let s = try? P256.Signing.ECDSASignature(derRepresentation: sig),
                  pub.isValidSignature(s, for: zip) else { throw PolyError.load("script bundle signature is invalid; update rejected") }
        }
        let base = ScriptSource.store.appendingPathComponent(Hash.short(u.absoluteString))
        let cur = base.appendingPathComponent("current"), tmp = base.appendingPathComponent("next")
        let stamp = base.appendingPathComponent("sha")
        let sha = Hash.short(zip)
        if (try? String(contentsOf: stamp, encoding: .utf8)) == sha, FileManager.default.fileExists(atPath: cur.path) { return false }
        try? FileManager.default.removeItem(at: tmp)
        try Zip.extract(zip, to: tmp)
        try? FileManager.default.removeItem(at: cur)
        try FileManager.default.moveItem(at: tmp, to: cur)
        try sha.write(to: stamp, atomically: true, encoding: .utf8)
        return true
    }
}

enum Hash {
    static func short(_ s: String) -> String { short(Data(s.utf8)) }
    static func short(_ d: Data) -> String { SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined().prefix(16).description }
}

enum HTTP {
    static func get(_ u: URL, headers: [String: String] = [:]) throws -> Data {
        var req = URLRequest(url: u, timeoutInterval: 30)
        headers.forEach { req.setValue($1, forHTTPHeaderField: $0) }
        let (data, status, _) = try send(req)
        guard status < 400 else { throw PolyError.load("HTTP \(status) for \(u)") }
        return data
    }

    static func send(_ req: URLRequest) throws -> (Data, Int, [String: String]) {
        let sem = DispatchSemaphore(value: 0)
        var out: (Data, Int, [String: String])?, failure: Error?
        URLSession.shared.dataTask(with: req) { d, r, e in
            if let e { failure = e } else {
                let h = (r as? HTTPURLResponse)
                var hd: [String: String] = [:]
                h?.allHeaderFields.forEach { hd["\($0.key)".lowercased()] = "\($0.value)" }
                out = (d ?? Data(), h?.statusCode ?? 0, hd)
            }
            sem.signal()
        }.resume()
        sem.wait()
        if let failure { throw failure }
        return out!
    }
}

/// Minimal .zip reader (stored + deflate), enough for script bundles.
enum Zip {
    static func extract(_ data: Data, to dir: URL) throws {
        let b = [UInt8](data)
        func u16(_ o: Int) -> Int { Int(b[o]) | Int(b[o + 1]) << 8 }
        func u32(_ o: Int) -> Int { u16(o) | u16(o + 2) << 16 }
        guard let eocd = stride(from: b.count - 22, through: max(0, b.count - 65557), by: -1).first(where: { u32($0) == 0x06054b50 }) else { throw PolyError.load("not a zip file") }
        var p = u32(eocd + 16)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for _ in 0..<u16(eocd + 10) {
            guard u32(p) == 0x02014b50 else { throw PolyError.load("bad zip") }
            let method = u16(p + 10), csize = u32(p + 20), usize = u32(p + 24), nlen = u16(p + 28), xlen = u16(p + 30), clen = u16(p + 32), off = u32(p + 42)
            let name = String(decoding: b[(p + 46)..<(p + 46 + nlen)], as: UTF8.self)
            p += 46 + nlen + xlen + clen
            if name.hasSuffix("/") { continue }
            guard !name.split(separator: "/").contains(".."), !name.hasPrefix("/") else { throw PolyError.load("bad path in bundle: \(name)") }
            let start = off + 30 + u16(off + 26) + u16(off + 28)
            let raw = Data(b[start..<(start + csize)])
            var file: Data
            if method == 0 { file = raw }
            else if method == 8 {
                var dst = [UInt8](repeating: 0, count: max(usize, 1))
                let n = raw.withUnsafeBytes { src in compression_decode_buffer(&dst, dst.count, src.bindMemory(to: UInt8.self).baseAddress!, raw.count, nil, COMPRESSION_ZLIB) }
                guard n == usize else { throw PolyError.load("could not unpack \(name)") }
                file = Data(dst.prefix(n))
            } else { throw PolyError.load("unsupported zip method in \(name)") }
            let dest = dir.appendingPathComponent(name)
            try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
            try file.write(to: dest)
        }
    }
}

enum JSON {
    static func parse(_ s: String) -> Any? { try? JSONSerialization.jsonObject(with: Data(s.utf8), options: [.fragmentsAllowed]) }
    static func string(_ v: Any) -> String {
        guard let d = try? JSONSerialization.data(withJSONObject: clean(v), options: [.fragmentsAllowed]) else { return "null" }
        return String(decoding: d, as: UTF8.self)
    }
    static func clean(_ v: Any) -> Any {
        switch v {
        case let d as [String: Any]: return d.mapValues(clean)
        case let a as [Any]: return a.map(clean)
        case let c as PolyCallback: return ["__volt_cb": c.id]
        case is NSNull, is String, is NSNumber, is Bool, is Int, is Double: return v
        case Optional<Any>.none: return NSNull()
        default: return "\(v)"
        }
    }
}
