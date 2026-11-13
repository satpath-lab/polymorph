import SwiftUI
import WebKit
import AVKit

extension Color {
    init?(hex: Any?) {
        guard var s = hex as? String, s.hasPrefix("#") else { return nil }
        s.removeFirst()
        guard let v = UInt64(s, radix: 16) else { return nil }
        let a, r, g, b: Double
        if s.count == 8 { (a, r, g, b) = (Double(v >> 24 & 255), Double(v >> 16 & 255), Double(v >> 8 & 255), Double(v & 255)) }
        else if s.count == 6 { (a, r, g, b) = (255, Double(v >> 16 & 255), Double(v >> 8 & 255), Double(v & 255)) }
        else { return nil }
        self = Color(.sRGB, red: r / 255, green: g / 255, blue: b / 255, opacity: a / 255)
    }
}

func num(_ v: Any?) -> CGFloat? { (v as? NSNumber).map { CGFloat($0.doubleValue) } }
func str(_ v: Any?) -> String { v.map { $0 is NSNull ? "" : "\($0)" } ?? "" }

func insets(_ v: Any?) -> EdgeInsets {
    if let n = num(v) { return EdgeInsets(top: n, leading: n, bottom: n, trailing: n) }
    if let a = v as? [Any] {
        let x = a.map { num($0) ?? 0 }
        if x.count == 2 { return EdgeInsets(top: x[0], leading: x[1], bottom: x[0], trailing: x[1]) }
        if x.count == 4 { return EdgeInsets(top: x[0], leading: x[3], bottom: x[2], trailing: x[1]) }
    }
    return EdgeInsets()
}

struct Node: View {
    let n: [String: Any]
    let vertical: Bool
    @EnvironmentObject var m: PolyModel

    var type: String { n["type"] as? String ?? "column" }
    var id: String { str(n["id"]) }

    var body: some View {
        if n["visible"] as? Bool == false { EmptyView() } else { styled(content) }
    }

    func kids() -> [[String: Any]] { (n["children"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }.map(m.styled) }

    func stack(_ axis: Axis) -> AnyView {
        let gap = num(n["gap"]) ?? 0
        let items = kids()
        let between = (n["justify"] as? String) == "between"
        let al = n["align"] as? String
        let views = ForEach(Array(items.enumerated()), id: \.offset) { i, c in
            if between && i > 0 { Spacer(minLength: 0) }
            Node(n: c, vertical: axis == .vertical)
        }
        if axis == .vertical {
            let h: HorizontalAlignment = al == "center" ? .center : al == "end" ? .trailing : .leading
            return AnyView(VStack(alignment: h, spacing: gap) { views }.frame(maxWidth: .infinity, alignment: Alignment(horizontal: h, vertical: .center)))
        }
        let v: VerticalAlignment = al == "start" ? .top : al == "end" ? .bottom : .center
        return AnyView(HStack(alignment: v, spacing: gap) { views })
    }

    @ViewBuilder var content: some View {
        switch type {
        case "column": stack(.vertical)
        case "card": stack(.vertical)
        case "row": stack(.horizontal)
        case "hscroll": ScrollView(.horizontal, showsIndicators: false) { stack(.horizontal) }
        case "stack": ZStack { ForEach(Array(kids().enumerated()), id: \.offset) { _, c in Node(n: c, vertical: true) } }
        case "grid":
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: num(n["gap"]) ?? 8), count: max(1, n["columns"] as? Int ?? 2)), spacing: num(n["gap"]) ?? 8) {
                ForEach(Array(kids().enumerated()), id: \.offset) { _, c in Node(n: c, vertical: true) }
            }
        case "text":
            let t = Text(str(n["text"]))
                .font(n["mono"] as? Bool == true ? .system(size: num(n["size"]) ?? 16, design: .monospaced) : .system(size: num(n["size"]) ?? 16))
                .fontWeight(n["bold"] as? Bool == true ? .bold : (num(n["weight"]).map { $0 >= 600 ? .semibold : .regular } ?? .regular))
                .italic(n["italic"] as? Bool == true)
                .foregroundColor(Color(hex: n["color"]) ?? .primary)
                .multilineTextAlignment(n["align"] as? String == "center" ? .center : n["align"] as? String == "end" ? .trailing : .leading)
                .lineLimit(n["lines"] as? Int)
            if n["selectable"] as? Bool == true { t.textSelection(.enabled) } else { t }
        case "button":
            Button { m.send("tap", id) } label: {
                Text(str(n["text"])).font(.system(size: num(n["size"]) ?? 16, weight: .semibold))
                    .foregroundColor(Color(hex: n["color"]) ?? .white)
                    .padding(.vertical, 12).padding(.horizontal, 16)
                    .frame(maxWidth: (vertical || n["flex"] != nil || str(n["width"]) == "fill") ? .infinity : nil)
                    .background(Color(hex: n["bg"]) ?? m.accent)
                    .clipShape(RoundedRectangle(cornerRadius: num(n["radius"]) ?? 12))
            }.buttonStyle(.plain).disabled(n["enabled"] as? Bool == false)
        case "input":
            let b = Binding(get: { m.inputs[id] ?? str(n["value"]) }, set: { m.inputs[id] = $0 })
            Group {
                if n["kind"] as? String == "password" { SecureField(str(n["hint"]), text: b) }
                else { TextField(str(n["hint"]), text: b, axis: n["multiline"] as? Bool == true ? .vertical : .horizontal) }
            }
            .textFieldStyle(.plain).padding(12)
            .background(Color(hex: n["bg"]) ?? Color.gray.opacity(0.12)).clipShape(RoundedRectangle(cornerRadius: 10))
            .onSubmit { m.send("submit", id) }
        case "switch", "checkbox":
            let t = Toggle(str(n["text"]), isOn: Binding(get: { n["value"] as? Bool ?? false }, set: { m.send("change", id, $0) })).tint(m.accent)
            #if os(macOS)
            if type == "checkbox" { t.toggleStyle(.checkbox) } else { t.toggleStyle(.switch) }
            #else
            t.toggleStyle(.switch)
            #endif
        case "select", "radio":
            let opts: [(String, String)] = (n["options"] as? [Any] ?? []).map { o in
                if let d = o as? [String: Any] { return (str(d["value"]), str(d["label"] ?? d["value"])) }
                return (str(o), str(o))
            }
            let p = Picker(str(n["text"]), selection: Binding(get: { str(n["value"]) }, set: { m.send("change", id, $0) })) {
                ForEach(opts, id: \.0) { Text($0.1).tag($0.0) }
            }
            if type == "radio" { p.pickerStyle(.segmented) } else { p.pickerStyle(.menu) }
        case "slider": PolySlider(n: n)
        case "progress":
            if let v = num(n["value"]) { ProgressView(value: Double(v)).tint(Color(hex: n["color"]) ?? m.accent) } else { ProgressView() }
        case "spacer": Spacer().frame(height: num(n["size"]) ?? 8)
        case "divider": Divider().overlay(Color(hex: n["color"]))
        case "image": PolyImage(src: str(n["src"]), fit: n["fit"] as? String == "contain")
        case "list":
            LazyVStack(spacing: num(n["gap"]) ?? 8) {
                let items = (n["items"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }
                ForEach(Array(items.enumerated()), id: \.offset) { i, c in
                    Node(n: m.styled(c), vertical: true).onAppear { if i == items.count - 1, let e = n["onEnd"] as? String { m.send("tap", e) } }
                }
            }
        case "chart": PolyChart(n: n, accent: m.accent)
        case "canvas": PolyCanvas(n: n)
        case "web": PolyWeb(url: str(n["url"]), html: n["html"] as? String, base: m.dir?.appendingPathComponent("assets"))
        case "video":
            if let u = m.mediaURL(str(n["src"])) { VideoPlayer(player: AVPlayer(url: u)) }
        default: Text("⚠ \(type) is not available on Apple yet").font(.caption).foregroundColor(.orange)
        }
    }

    func styled<V: View>(_ v: V) -> some View {
        let tapId = ["column", "row", "card", "stack", "grid", "image", "text"].contains(type) ? id : ""
        let card = type == "card"
        let fill = str(n["width"]) == "fill" || (!vertical && n["flex"] != nil) || (vertical && n["width"] == nil && ["row", "card", "column", "stack", "grid", "list", "input", "select", "slider", "progress", "chart", "canvas", "web", "video", "divider", "hscroll"].contains(type))
        return v
            .padding(n["padding"] != nil ? insets(n["padding"]) : card ? EdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16) : EdgeInsets())
            .frame(width: num(n["width"]), height: num(n["height"]))
            .frame(maxWidth: fill ? .infinity : nil, maxHeight: str(n["height"]) == "fill" ? .infinity : nil, alignment: .leading)
            .background(background)
            .clipShape(RoundedRectangle(cornerRadius: num(n["radius"]) ?? (card ? 16 : 0)))
            .overlay(RoundedRectangle(cornerRadius: num(n["radius"]) ?? (card ? 16 : 0)).stroke(Color(hex: n["border"]) ?? .clear, lineWidth: num(n["borderWidth"]) ?? 1))
            .shadow(color: .black.opacity((num(n["elevation"]) ?? (card ? 1 : 0)) > 0 ? 0.08 : 0), radius: num(n["elevation"]) ?? (card ? 4 : 0), y: 1)
            .opacity(Double(num(n["opacity"]) ?? 1))
            .padding(insets(n["margin"]))
            .contentShape(Rectangle())
            .onTapGesture { if !tapId.isEmpty { m.send("tap", tapId) } }
    }

    @ViewBuilder var background: some View {
        if let g = n["gradient"] as? [Any], g.count >= 2 {
            LinearGradient(colors: g.compactMap { Color(hex: $0) }, startPoint: .leading, endPoint: .trailing)
        } else if let c = Color(hex: n["bg"]), type != "button" { c } else if type == "card" { Color.white } else { Color.clear }
    }
}

struct PolySlider: View {
    let n: [String: Any]
    @EnvironmentObject var m: PolyModel
    @State var v: Double = .nan
    var body: some View {
        let lo = Double(num(n["min"]) ?? 0), hi = Double(num(n["max"]) ?? 100)
        Slider(value: Binding(get: { v.isNaN ? Double(num(n["value"]) ?? CGFloat(lo)) : v }, set: { v = $0 }), in: lo...max(hi, lo + 1), step: Double(num(n["step"]) ?? 1)) { editing in
            if !editing, !v.isNaN { m.send("change", str(n["id"]), v); v = .nan }
        }.tint(Color(hex: n["color"]) ?? m.accent)
    }
}

struct PolyImage: View {
    let src: String, fit: Bool
    @EnvironmentObject var m: PolyModel
    var body: some View {
        if src.hasPrefix("http"), let u = URL(string: src) {
            AsyncImage(url: u) { $0.resizable().aspectRatio(contentMode: fit ? .fit : .fill) } placeholder: { Color.gray.opacity(0.15) }.clipped()
        } else if let u = m.mediaURL(src), let img = PlatformImage(contentsOfFile: u.path) {
            Image(platform: img).resizable().aspectRatio(contentMode: fit ? .fit : .fill).clipped()
        } else { Color.gray.opacity(0.15) }
    }
}

#if canImport(UIKit)
typealias PlatformImage = UIImage
extension Image { init(platform: UIImage) { self.init(uiImage: platform) } }
#else
typealias PlatformImage = NSImage
extension Image { init(platform: NSImage) { self.init(nsImage: platform) } }
#endif

struct PolyChart: View {
    let n: [String: Any], accent: Color
    var body: some View {
        let series = (n["series"] as? [Any])?.compactMap { $0 as? [String: Any] } ?? [["values": n["data"] ?? []]]
        let vals = series.map { ($0["values"] as? [Any] ?? []).map { Double(num($0) ?? 0) } }
        let top = max(vals.flatMap { $0 }.max() ?? 1, 1)
        let bar = (n["kind"] as? String ?? "line") == "bar"
        Canvas { ctx, size in
            for (si, v) in vals.enumerated() where !v.isEmpty {
                let color = Color(hex: series[si]["color"]) ?? (si == 0 ? accent : .orange)
                let w = size.width / CGFloat(v.count)
                if bar {
                    for (i, x) in v.enumerated() {
                        let h = size.height * CGFloat(x / top)
                        ctx.fill(Path(roundedRect: CGRect(x: CGFloat(i) * w + w * 0.15, y: size.height - h, width: w * 0.7, height: h), cornerRadius: 4), with: .color(color))
                    }
                } else {
                    var p = Path()
                    for (i, x) in v.enumerated() {
                        let pt = CGPoint(x: CGFloat(i) * size.width / CGFloat(max(v.count - 1, 1)), y: size.height * (1 - CGFloat(x / top)))
                        i == 0 ? p.move(to: pt) : p.addLine(to: pt)
                    }
                    ctx.stroke(p, with: .color(color), lineWidth: 3)
                }
            }
        }.frame(height: num(n["height"]) ?? 180)
    }
}

struct PolyCanvas: View {
    let n: [String: Any]
    @EnvironmentObject var m: PolyModel
    var body: some View {
        Canvas { ctx, _ in
            for case let o as [String: Any] in n["ops"] as? [Any] ?? [] {
                let c = Color(hex: o["color"] ?? o["fill"]) ?? .black
                let x = num(o["x"]) ?? 0, y = num(o["y"]) ?? 0
                switch o["op"] as? String ?? "" {
                case "rect": ctx.fill(Path(roundedRect: CGRect(x: x, y: y, width: num(o["w"]) ?? 0, height: num(o["h"]) ?? 0), cornerRadius: num(o["r"]) ?? 0), with: .color(c))
                case "circle": let r = num(o["r"]) ?? 0; ctx.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)), with: .color(c))
                case "line": var p = Path(); p.move(to: CGPoint(x: num(o["x1"]) ?? 0, y: num(o["y1"]) ?? 0)); p.addLine(to: CGPoint(x: num(o["x2"]) ?? 0, y: num(o["y2"]) ?? 0)); ctx.stroke(p, with: .color(c), lineWidth: num(o["width"]) ?? 2)
                case "text": ctx.draw(Text(str(o["text"])).font(.system(size: num(o["size"]) ?? 14)).foregroundColor(c), at: CGPoint(x: x, y: y), anchor: .topLeading)
                default: break
                }
            }
        }
        .frame(height: num(n["height"]) ?? 200)
        .gesture(DragGesture(minimumDistance: 0).onEnded { g in if let id = n["id"] as? String { m.send("tap", id, ["x": g.location.x, "y": g.location.y]) } })
    }
}

#if canImport(UIKit)
struct PolyWeb: UIViewRepresentable {
    let url: String, html: String?, base: URL?
    func makeUIView(context: Context) -> WKWebView { WKWebView() }
    func updateUIView(_ w: WKWebView, context: Context) { load(w) }
}
#else
struct PolyWeb: NSViewRepresentable {
    let url: String, html: String?, base: URL?
    func makeNSView(context: Context) -> WKWebView { WKWebView() }
    func updateNSView(_ w: WKWebView, context: Context) { load(w) }
}
#endif
extension PolyWeb {
    func load(_ w: WKWebView) {
        if let html { w.loadHTMLString(html, baseURL: base) }
        else if url.hasPrefix("http"), let u = URL(string: url) { w.load(URLRequest(url: u)) }
        else if let b = base { w.loadFileURL(b.appendingPathComponent(url), allowingReadAccessTo: b) }
    }
}
