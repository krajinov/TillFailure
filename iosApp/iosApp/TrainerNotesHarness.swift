import Foundation
import Darwin
import Shared

#if DEBUG
enum TrainerNotesHarness {
    @MainActor private static var bridge: FirebaseNativeBridge!
    @MainActor private static var client: IosProductIdentityClient!
    @MainActor private static var root: NativeClientsModels!
    private static func check(_ ok: Bool, _ label: String) {
        print("M5P5_IOS \(label)=\(ok ? "PASS" : "FAIL")");fflush(stdout)
        if !ok { exit(1) }
    }
    static func runIfRequested() {
        let env=ProcessInfo.processInfo.environment
        guard let phase=env["TF_PR5_NATIVE"],let password=env["TF_PR5_FIXTURE_PASSWORD"],let prefix=env["TF_PR5_FIXTURE_PREFIX"] else{return}
        Task { @MainActor in await run(phase,password,prefix) }
    }
    @MainActor private static func until(_ ready:()->Bool) async {
        let deadline=Date().addingTimeInterval(45)
        while !ready() && Date()<deadline {try? await Task.sleep(nanoseconds:50_000_000)}
        check(ready(),"bounded-state")
    }
    @MainActor private static func makeClient(_ base:String)->IosProductIdentityClient {
        let b=FirebaseNativeBridge(productMemoryCache:true,identityDefaults:UserDefaults(suiteName:base)!)
        let path=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent(base)
        return IosProductIdentityClient(bridge:b,recoveryBridge:RecoveryPersistenceBridge(rootURL:path))
    }
    @MainActor private static func signIn(_ c:ProductIdentityClient,_ uid:String,_ password:String) async -> StableFirebaseFailure? {
        await withCheckedContinuation { continuation in _=c.signIn(email:"\(uid)@example.invalid",password:password,epoch:0){continuation.resume(returning:$0)} }
    }
    @MainActor private static func execute(_ c:InvitationClient,_ uid:String,_ request:InvitationRequest) async -> InvitationOutcome {
        await withCheckedContinuation { continuation in _=c.execute(uid:uid,request:request){continuation.resume(returning:$0)} }
    }
    @MainActor private static func run(_ phase:String,_ password:String,_ prefix:String) async {
        guard prefix.range(of:"^m5p5r[0-9]{6,20}$",options:.regularExpression) != nil else {exit(1)}
        let mode=String(phase.split(separator:"-")[0]),base="\(prefix)_ios_\(mode)"
        let uid="\(base)_trainer",target="\(base)_client",wid="\(base)_workspace"
        bridge=FirebaseNativeBridge(productMemoryCache:true,identityDefaults:UserDefaults(suiteName:base)!)
        let path=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent(base)
        client=IosProductIdentityClient(bridge:bridge,recoveryBridge:RecoveryPersistenceBridge(rootURL:path))
        if bridge.debugCurrentUid==nil {check(await signIn(client,uid,password)==nil,"sign-in")}
        check(bridge.debugCurrentUid==uid,"original-uid");root=NativeClientsModels(client:client)
        await until {[.trainerhome,.lockedrecovery,.accesslost,.workspacegate].contains(root.identity.currentState().status)}
        if phase.hasSuffix("stage") {
            let loaded=await execute(client.invitations,uid,TrainerNoteProtocol.shared.read(workspace:wid,clientUid:target))
            check(loaded.error==nil && loaded.reply?.noteRead != nil,"authorized-read")
            let original=TrainerNoteProtocol.shared.saveFromRead(read:loaded.reply!.noteRead!,body:"Review demonstration next meeting.",nonce:bridge.noteBindingNonce(),key:CommandResolutionKt.resolutionCommandKey(nonce:UUID().uuidString))
            if mode=="conflict" {
                let first=await execute(client.invitations,uid,TrainerNoteProtocol.shared.saveFromRead(read:loaded.reply!.noteRead!,body:"Review session plan.",nonce:bridge.noteBindingNonce(),key:CommandResolutionKt.resolutionCommandKey(nonce:UUID().uuidString)))
                check(first.error==nil,"competing-save")
            }
            var held=false
            if mode=="fenced" {bridge.debugBeforeInvitationTransport={_ in held=true}}
            else if mode=="proof" {bridge.debugBeforeCommandRetirement={print("M5P5_IOS_PROOF_DURABLE");fflush(stdout);while true {Thread.sleep(forTimeInterval:0.1)}}}
            else if mode != "settled" {bridge.debugBeforeInvitationCompletion={_ in held=true}}
            if mode=="settled" {
                let saved=await execute(client.invitations,uid,original);check(saved.error==nil,"saved-and-compacted")
                check(client.invitations.lastSettled(uid:uid)?.command==nil,"no-retired-content");print("M5P5_IOS_SETTLED");fflush(stdout)
            } else {
                _=client.invitations.execute(uid:uid,request:original){_ in}
                if mode != "proof" {
                    await until {held};check(client.invitations.pending(uid:uid)?.noteSave?.clientUid==target,"original-protected")
                    root.identity.onEvent(event:IdentityEventSignOut.shared);await until {[.lockedrecovery,.departureblocked].contains(root.identity.currentState().status)}
                    check(await signIn(client,target,password)?.code == .failedPrecondition,"pending-blocks-b")
                    print("M5P5_IOS_ORIGINAL_HELD");fflush(stdout)
                }
            }
            while true {try? await Task.sleep(nanoseconds:100_000_000)}
        }
        if !["proof","settled"].contains(mode) {
            let p=client.invitations.pending(uid:uid)!
            check(p.action=="save-trainer-note" && p.noteSave?.clientUid==target && p.noteSave?.body=="Review demonstration next meeting.","exact-original-restored")
            let outcome=await execute(client.invitations,uid,CommandResolution.shared.resolveRequest(p:p))
            let expected=mode=="fenced" ? "not-applied-fenced" : mode=="conflict" ? "not-applied-conflict" : "applied"
            check(outcome.error==nil && outcome.reply?.terminalOutcome?.outcome==expected,"own-outcome")
        }
        let proof=client.invitations.lastSettled(uid:uid)
        check(client.invitations.pending(uid:uid)==nil && proof?.valid()==true && proof?.command==nil && proof?.marker != nil,"restart-content-free-settlement")
        root.clear();root=NativeClientsModels(client:client)
        await until {[.accesslost,.workspacegate].contains(root.identity.currentState().status)}
        check(root.identity.currentState().status != .trainerhome,"no-role-restoration")
        root.identity.onEvent(event:IdentityEventSignOut.shared);await until {root.identity.currentState().status == .signedout}
        let replacement=root.identity.activeClient();root.clear();check(await signIn(replacement,target,password)==nil,"clean-b-sign-in")
        root=NativeClientsModels(client:replacement);await until {[.clienthome,.onboarding].contains(root.identity.currentState().status)}
        check(replacement.invitations?.pending(uid:target)==nil && replacement.invitations?.lastSettled(uid:target)==nil,"b-isolation")
        root.identity.onEvent(event:IdentityEventSignOut.shared);await until {root.identity.currentState().status == .signedout};root.clear()
        print("M5P5_IOS_PASS_\(mode)");fflush(stdout);exit(0)
    }
}
#endif
