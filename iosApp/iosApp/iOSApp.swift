import Shared
import SwiftUI

@main
struct iOSApp: App {
    private let firebaseBridge: FirebaseNativeBridge?
    private let recoveryBridge: RecoveryPersistenceBridge?

    init() {
        DependencyInjectionKt.initializeKoin()
        #if DEBUG
        let bridge = FirebaseNativeBridge(productMemoryCache: true)
        firebaseBridge = bridge
        recoveryBridge = RecoveryPersistenceBridge()
        FirebaseSpikeHarness.runIfRequested(bridge: bridge)
        ProductIdentityHarness.runIfRequested(bridge: bridge)
        CleanDepartureHarness.runIfRequested(password: ProcessInfo.processInfo.environment["TF_PR1_TEST_PASSWORD"])
        CleanDepartureHarness.runUnsupportedIfRequested(password: ProcessInfo.processInfo.environment["TF_PR1_TEST_PASSWORD"])
        CleanDepartureHarness.runAnonymousIfRequested(password: ProcessInfo.processInfo.environment["TF_PR1_TEST_PASSWORD"])
        CleanDepartureHarness.runCatalogReturnIfRequested()
        CleanDepartureHarness.runInterruptedPhaseIfRequested(password: ProcessInfo.processInfo.environment["TF_PR1_TEST_PASSWORD"])
        CleanDepartureHarness.runUnverifiedEmailIfRequested(password: ProcessInfo.processInfo.environment["TF_PR1_TEST_PASSWORD"])
        CleanDepartureHarness.runRecheckVerificationIfRequested(password: ProcessInfo.processInfo.environment["TF_PR1_TEST_PASSWORD"])
        CleanDepartureHarness.runRecreatedRootIfRequested(password: ProcessInfo.processInfo.environment["TF_PR1_TEST_PASSWORD"])
        NativeRevocationHarness.runIfRequested(password: ProcessInfo.processInfo.environment["TF_PR1_TEST_PASSWORD"])
        #else
        firebaseBridge = nil
        recoveryBridge = nil
        #endif
    }

    var body: some Scene {
        WindowGroup {
            #if DEBUG
            ContentView(firebaseBridge: firebaseBridge, recoveryBridge: recoveryBridge,
                        showDevelopmentCatalog: true)
            #else
            ContentView(firebaseBridge: firebaseBridge, recoveryBridge: recoveryBridge,
                        showDevelopmentCatalog: false)
            #endif
        }
    }
}
