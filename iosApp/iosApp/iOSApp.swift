import Shared
import SwiftUI

@main
struct iOSApp: App {
    init() {
        DependencyInjectionKt.initializeKoin()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
