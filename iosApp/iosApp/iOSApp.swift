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
        TrainerProvisioningHarness.runIfRequested()
        OnboardingHarness.runIfRequested()
        ClientsHarness.runIfRequested()
        ProgramDraftsHarness.runIfRequested()
        ProgramDraftsHarness.prepareUIIfRequested(bridge: bridge)
        CustomExercisesHarness.runIfRequested()
        CustomExercisesHarness.prepareUIIfRequested(bridge: bridge)
        SystemExercisesHarness.runIfRequested()
        SystemExercisesHarness.prepareUIIfRequested(bridge: bridge)
        ClientInvitationsHarness.runIfRequested()
        BasicProfileHarness.runIfRequested()
        CommandResolutionHarness.runIfRequested()
        ClientRevocationHarness.runIfRequested()
        TrainerNotesHarness.runIfRequested()
        RecoveryHardeningHarness.runIfRequested()
        InvitationHarness.runIfRequested()
        InvitationLifecycleHarness.runIfRequested()
        InvitationRecoveryHarness.runIfRequested()
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
