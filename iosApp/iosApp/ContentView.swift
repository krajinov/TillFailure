import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    let firebaseBridge: NativeFirebaseBridge?
    let recoveryBridge: NativeRecoveryPersistenceBridge?
    let showDevelopmentCatalog: Bool

    func makeUIViewController(context: Self.Context) -> UIViewController {
        #if DEBUG
        if let stage = ProcessInfo.processInfo.environment["TF_PR3_VISUAL"] {
            let scale = Float(ProcessInfo.processInfo.environment["TF_PR3_FONT_SCALE"] ?? "1") ?? 1
            return InvitationVisualControllerKt.InvitationVisualController(stage: stage, fontScale: scale)
        }
        #endif
        return MainViewControllerKt.MainViewController(
            nativeFirebaseBridge: firebaseBridge,
            nativeRecoveryBridge: recoveryBridge,
            showDevelopmentCatalog: showDevelopmentCatalog,
            fontScaleOverride: debugFontScale
        )
    }

    private var debugFontScale: Float {
        #if DEBUG
        return Float(ProcessInfo.processInfo.environment["TF_PR3_FONT_SCALE"] ?? "0") ?? 0
        #else
        return 0
        #endif
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    let firebaseBridge: NativeFirebaseBridge?
    let recoveryBridge: NativeRecoveryPersistenceBridge?
    let showDevelopmentCatalog: Bool

    var body: some View {
        ComposeView(firebaseBridge: firebaseBridge, recoveryBridge: recoveryBridge,
                    showDevelopmentCatalog: showDevelopmentCatalog)
            .ignoresSafeArea()
    }
}
