import AVFoundation
import SwiftUI

/// openclaw qr 配对二维码扫描页（AVFoundation 原生会话，从 Compose 版 ContentView 移植）。
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
            hint.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            hint.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -48),
            close.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            close.topAnchor.constraint(equalTo: hint.bottomAnchor, constant: 16),
        ])
    }

    @objc private func cancelTapped() {
        finish(nil)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard let obj = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              obj.type == .qr,
              let value = obj.stringValue else { return }
        finish(value)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning { session.stopRunning() }
        if !finished {
            finished = true
            onResult(nil)
        }
    }

    private func finish(_ payload: String?) {
        guard !finished else { return }
        finished = true
        if session.isRunning { session.stopRunning() }
        onResult(payload)
    }
}
