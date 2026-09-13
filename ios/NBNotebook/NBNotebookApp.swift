import SwiftUI
import WebKit

/// 牛逼笔记本 · iOS 客户端
/// 构建方式见 ios/README.md（需要 Mac + Xcode）
@main
struct NBNotebookApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

// MARK: - 根视图：无地址时显示设置页，有地址时加载 WebView

struct RootView: View {
    @AppStorage("serverURL") private var serverURL: String = ""

    var body: some View {
        if serverURL.isEmpty {
            SetupView()
        } else {
            NavigationView {
                WebContainerView(urlString: serverURL)
                    .navigationBarTitle("", displayMode: .inline)
                    .toolbar {
                        ToolbarItem(placement: .navigationBarLeading) {
                            Button("← 切换服务器") { serverURL = "" }
                                .font(.footnote)
                        }
                    }
            }
            .navigationViewStyle(StackNavigationViewStyle())
        }
    }
}

// MARK: - 设置页（首次使用：输入电脑的局域网 IP 和端口）

struct SetupView: View {
    @AppStorage("serverURL") private var serverURL: String = ""
    @State private var ip: String = ""
    @State private var port: String = "8080"
    @State private var showError = false

    var body: some View {
        VStack(spacing: 20) {
            Text("🔥 牛逼笔记本")
                .font(.system(size: 30, weight: .bold))
                .foregroundColor(Color(red: 0.12, green: 0.18, blue: 0.26))
            Text("连接你的电脑，随时随地记笔记")
                .font(.subheadline)
                .foregroundColor(.secondary)

            VStack(alignment: .leading, spacing: 8) {
                TextField("电脑局域网 IP（如 192.168.0.2）", text: $ip)
                    .textFieldStyle(RoundedBorderTextFieldStyle())
                    .keyboardType(.numbersAndPunctuation)
                    .autocapitalization(.none)
                TextField("端口（默认 8080）", text: $port)
                    .textFieldStyle(RoundedBorderTextFieldStyle())
                    .keyboardType(.numberPad)
            }
            .padding(.horizontal)

            Button(action: connect) {
                Text("连 接")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Color(red: 0.18, green: 0.43, blue: 0.96))
                    .foregroundColor(.white)
                    .cornerRadius(12)
            }
            .padding(.horizontal)

            Text("提示：手机和电脑需连同一个 Wi-Fi。\n电脑端启动服务器后会显示访问地址。")
                .font(.footnote)
                .foregroundColor(.gray)
                .multilineTextAlignment(.center)

            if showError {
                Text("请填写正确的 IP 地址（如 192.168.0.2）")
                    .font(.footnote)
                    .foregroundColor(.red)
            }
        }
        .padding()
    }

    private func connect() {
        let trimmed = ip.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { showError = true; return }
        showError = false
        let p = port.trimmingCharacters(in: .whitespaces).isEmpty ? "8080" : port
        let url: String
        if trimmed.hasPrefix("http") {
            url = trimmed
        } else {
            url = "http://\(trimmed):\(p)"
        }
        serverURL = url
    }
}

// MARK: - WebView 容器

struct WebContainerView: UIViewRepresentable {
    let urlString: String

    @AppStorage("serverURL") private var serverURL: String = ""

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.isOpaque = false
        webView.backgroundColor = UIColor.systemBackground
        if let url = URL(string: urlString) {
            webView.load(URLRequest(url: url))
        }
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        // URL 变化时重新加载（从设置页返回）
        if uiView.url?.absoluteString != urlString {
            if let url = URL(string: urlString) {
                uiView.load(URLRequest(url: url))
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, WKNavigationDelegate {
        let parent: WebContainerView

        init(_ parent: WebContainerView) { self.parent = parent }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            // 加载失败：弹回设置页
            parent.serverURL = ""
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            parent.serverURL = ""
        }
    }
}
