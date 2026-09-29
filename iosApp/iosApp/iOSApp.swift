import Shared
import SwiftUI

@main
struct iOSApp: App {
    private let firebaseBridge: FirebaseNativeBridge?

    init() {
        DependencyInjectionKt.initializeKoin()
        #if DEBUG
        let bridge = FirebaseNativeBridge()
        firebaseBridge = bridge
        FirebaseSpikeHarness.runIfRequested(bridge: bridge)
        #else
        firebaseBridge = nil
        #endif
    }

    var body: some Scene {
        WindowGroup {
            ContentView(firebaseBridge: firebaseBridge)
        }
    }
}
