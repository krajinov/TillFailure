import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    let firebaseBridge: NativeFirebaseBridge?
    let recoveryBridge: NativeRecoveryPersistenceBridge?

    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController(
            nativeFirebaseBridge: firebaseBridge,
            nativeRecoveryBridge: recoveryBridge
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    let firebaseBridge: NativeFirebaseBridge?
    let recoveryBridge: NativeRecoveryPersistenceBridge?

    var body: some View {
        ComposeView(firebaseBridge: firebaseBridge, recoveryBridge: recoveryBridge)
            .ignoresSafeArea()
    }
}
