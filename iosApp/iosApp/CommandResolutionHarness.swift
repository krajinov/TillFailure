import Foundation
import Darwin
import Shared

#if DEBUG
enum CommandResolutionHarness {
    @MainActor private static var bridge: FirebaseNativeBridge!
    @MainActor private static var client: IosProductIdentityClient!
    @MainActor private static var root: NativeProfileModels!
    private static func check(_ ok: Bool, _ label: String) {
        print("M5R_IOS \(label)=\(ok ? "PASS" : "FAIL")"); fflush(stdout)
        if !ok { exit(1) }
    }
    static func runIfRequested() {
        let env=ProcessInfo.processInfo.environment
        guard let phase=env["TF_RESOLUTION_NATIVE"], let password=env["TF_RESOLUTION_FIXTURE_PASSWORD"] else { return }
        Task { @MainActor in await run(phase,password) }
    }
    @MainActor private static func until(_ ready: () -> Bool) async {
        let end=Date().addingTimeInterval(45)
        while !ready() && Date()<end { try? await Task.sleep(nanoseconds:50_000_000) }
        check(ready(),"bounded-state")
    }
    @MainActor private static func run(_ phase: String, _ password: String) async {
        let mode=String(phase.split(separator:"-")[0])
        let prefix=ProcessInfo.processInfo.environment["TF_RESOLUTION_FIXTURE_PREFIX"] ?? "m5r"
        let base="\(prefix)_ios_\(mode)", uid="\(base)_client", wid="\(base)_workspace"
        bridge=FirebaseNativeBridge(productMemoryCache:true,identityDefaults:UserDefaults(suiteName:base)!)
        let path=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent(base)
        client=IosProductIdentityClient(bridge:bridge,recoveryBridge:RecoveryPersistenceBridge(rootURL:path))
        if bridge.debugCurrentUid==nil {
            let error:StableFirebaseFailure?=await withCheckedContinuation { c in _=client.signIn(email:"\(uid)@example.invalid",password:password,epoch:0){ c.resume(returning:$0) } }
            check(error==nil,"sign-in")
        }
        check(bridge.debugCurrentUid==uid,"original-uid")
        root=NativeProfileModels(client:client)
        await until { [.clienthome,.lockedrecovery,.accesslost,.workspacegate].contains(root.identity.currentState().status) }
        if phase.hasSuffix("stage") {
            var held=false
            if mode=="fenced" { bridge.debugBeforeInvitationTransport={ _ in held=true } }
            else if mode=="proof" {
                bridge.debugBeforeCommandRetirement={
                    print("M5R_IOS_PROOF_DURABLE");fflush(stdout)
                    while true { Thread.sleep(forTimeInterval:0.1) }
                }
            } else { bridge.debugBeforeInvitationCompletion={ _ in held=true } }
            let request=InvitationRequest(action:"update-profile",workspaceId:wid,invitationId:nil,email:nil,token:nil,expectedVersion:nil,
                idempotencyKey:CommandResolutionKt.resolutionCommandKey(nonce: UUID().uuidString),onboarding:nil,profileUpdate:ProfileUpdate(displayName:"Resolved Apple",locale:"bs-BA",timeZoneId:"Europe/Sarajevo",expectedRevision:1))
            _=client.invitations.execute(uid:uid,request:request){ _ in }
            if mode != "proof" {
                await until { held };check(client.invitations.pending(uid:uid) != nil,"original-protected")
                root.identity.onEvent(event:IdentityEventSignOut.shared)
                await until { [.lockedrecovery,.departureblocked].contains(root.identity.currentState().status) }
                let error:StableFirebaseFailure?=await withCheckedContinuation { c in _=client.signIn(email:"\(base)_trainer@example.invalid",password:password,epoch:0){c.resume(returning:$0)} }
                check(error?.code == .failedPrecondition,"pending-blocks-other-account")
                print("M5R_IOS_ORIGINAL_HELD");fflush(stdout)
            }
            while true { try? await Task.sleep(nanoseconds:100_000_000) }
        }
        if mode=="proof" {
            check(client.invitations.pending(uid:uid)==nil,"durable-proof-retired-after-restart")
            check(client.invitations.lastSettled(uid:uid)?.source=="normal-v1","normal-proof-preserved")
        } else {
            let original=client.invitations.pending(uid:uid)!
            check(original.idempotencyKey.hasPrefix("or1-") && original.profileUpdate?.expectedRevision==1,"exact-original-restored")
            root.identity.onEvent(event:IdentityEventSignOut.shared)
            await until { [.lockedrecovery,.departureblocked].contains(root.identity.currentState().status) }
            let result:InvitationOutcome=await withCheckedContinuation { c in
                _=client.invitations.execute(uid:uid,request:CommandResolution.shared.resolveRequest(p:original)){c.resume(returning:$0)}
            }
            print("M5R_IOS resolution-status=\(result.error ?? result.reply?.terminalOutcome?.outcome ?? "missing")");fflush(stdout)
            check(result.error==nil && result.reply?.terminalOutcome?.outcome==(mode=="applied" ? "applied":"not-applied-fenced"),"minimal-own-outcome")
            check(client.invitations.pending(uid:uid)==nil && client.invitations.lastSettled(uid:uid)?.source=="resolution-v1","protected-settlement")
        }
        root.clear();root=NativeProfileModels(client:client)
        await until { [.accesslost,.workspacegate].contains(root.identity.currentState().status) }
        check(root.identity.currentState().status != .clienthome,"outcome-restores-no-role")
        root.identity.onEvent(event:IdentityEventSignOut.shared);await until {root.identity.currentState().status == .signedout}
        let replacement=root.identity.activeClient();root.clear()
        let signIn:StableFirebaseFailure?=await withCheckedContinuation { c in _=replacement.signIn(email:"\(base)_trainer@example.invalid",password:password,epoch:0){c.resume(returning:$0)} }
        check(signIn==nil,"clean-b-sign-in");root=NativeProfileModels(client:replacement)
        await until {root.identity.currentState().status == .trainerhome}
        check(replacement.invitations?.pending(uid:"\(base)_trainer")==nil && replacement.invitations?.lastSettled(uid:"\(base)_trainer")==nil,"b-has-no-a-payload-or-proof")
        root.identity.onEvent(event:IdentityEventSignOut.shared);await until {root.identity.currentState().status == .signedout}
        root.clear();check(true,"clean-departure");exit(0)
    }
}
#endif
