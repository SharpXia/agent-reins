import AVFoundation
import UIKit
import SwiftUI
import composeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    @State private var showScanner = false
    var body: some View {
        ComposeView()
            // 全屏铺满（状态栏/Home 指示条/键盘一律交给 Compose 侧 insets 消费）
            .ignoresSafeArea()
            // Compose 侧 launchQrScanner 经通知拉起扫码页；结果经 QrScanBridgeKt 回传
            .onReceive(NotificationCenter.default.publisher(for: Notification.Name("agentreins.qrscan.request"))) { _ in
                showScanner = true
            }
            .sheet(isPresented: $showScanner) {
                QrScannerView { payload in
                    showScanner = false
                    QrScanBridgeKt.onQrScanResult(payload: payload)
                }
            }
    }
}

/// openclaw qr 配对二维码扫描页（AVFoundation 原生会话）。
/// 识别成功或取消（含下滑关闭）都会回调一次 onResult。
struct QrScannerView: UIViewControllerRepresentable {
    let onResult: (String?) -> Void

    func makeUIViewController(context: Context) -> UIViewController {
        QrScannerController(onResult: onResult)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

final class QrScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let session = AVCaptureSession()
    private let onResult: (String?) -> Void
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var finished = false

    init(onResult: @escaping (String?) -> Void) {
        self.onResult = onResult
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            setupSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted { self?.setupSession() } else { self?.finish(nil) }
                }
            }
        default:
            finish(nil)
        }
    }

    private func setupSession() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            finish(nil)
            return
        }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            finish(nil)
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)
        previewLayer = preview
        DispatchQueue.global(qos: .userInitiated).async { [session] in
            session.startRunning()
        }

        let hint = UILabel()
        hint.text = "对准网关机器 openclaw qr 输出的二维码"
        hint.textColor = .white
        hint.textAlignment = .center
        hint.font = .preferredFont(forTextStyle: .callout)
        hint.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hint)
        let close = UIButton(type: .system)
        close.setTitle("取消", for: .normal)
        close.setTitleColor(.white, for: .normal)
        close.translatesAutoresizingMaskIntoConstraints = false
        close.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        view.addSubview(close)
        NSLayoutConstraint.activate([
            hint.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 24),
            hint.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            hint.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            close.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            close.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
        ])
    }

    @objc private func cancelTapped() {
        finish(nil)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // 下滑关闭 sheet 的取消兜底；finished 幂等防重复回调
        finish(nil)
    }

    private func finish(_ value: String?) {
        guard !finished else { return }
        finished = true
        session.stopRunning()
        onResult(value)
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard let obj = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              obj.type == .qr, let value = obj.stringValue else { return }
        finish(value)
    }
}
