import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    let firebaseBridge: NativeFirebaseBridge?

    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController(
            showDevelopmentCatalog: _isDebugAssertConfiguration(),
            nativeFirebaseBridge: firebaseBridge
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    let firebaseBridge: NativeFirebaseBridge?

    var body: some View {
        ComposeView(firebaseBridge: firebaseBridge)
            .ignoresSafeArea()
    }
}
