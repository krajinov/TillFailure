import Foundation
import Darwin
import Shared

#if DEBUG
enum ClientRevocationHarness {
    @MainActor private static var bridge: FirebaseNativeBridge!
    @MainActor private static var client: IosProductIdentityClient!
    @MainActor private static var root: NativeClientsModels!
    private static func check(_ ok: Bool, _ label: String) {
        print("M5P4_IOS \(label)=\(ok ? "PASS" : "FAIL")");fflush(stdout)
        if !ok { exit(1) }
    }
    static func runIfRequested() {
        let env=ProcessInfo.processInfo.environment
        guard let phase=env["TF_PR4_NATIVE"],let password=env["TF_PR4_FIXTURE_PASSWORD"],let prefix=env["TF_PR4_FIXTURE_PREFIX"] else{return}
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
        guard prefix.range(of:"^m5p4r[0-9]{6,20}$",options:.regularExpression) != nil else{exit(1)}
        let mode=String(phase.split(separator:"-")[0]),base="\(prefix)_ios_\(phase.split(separator:"-")[0])"
        let uid="\(base)_trainer",target="\(base)_client",wid="\(base)_workspace"
        bridge=FirebaseNativeBridge(productMemoryCache:true,identityDefaults:UserDefaults(suiteName:base)!)
        let path=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent(base)
        client=IosProductIdentityClient(bridge:bridge,recoveryBridge:RecoveryPersistenceBridge(rootURL:path))
        if bridge.debugCurrentUid==nil {check(await signIn(client,uid,password)==nil,"sign-in")}
        check(bridge.debugCurrentUid==uid,"original-uid");root=NativeClientsModels(client:client)
        await until { [.trainerhome,.lockedrecovery,.accesslost,.workspacegate].contains(root.identity.currentState().status) }
        if mode=="live" {
            let observer=makeClient(base+"_observer");check(await signIn(observer,target,password)==nil,"client-sign-in")
            let observed=NativeClientsModels(client:observer);await until {observed.identity.currentState().status == .multiplememberships}
            observed.identity.onEvent(event:IdentityEventChooseWorkspace(workspaceId:wid));await until {observed.identity.currentState().status == .clienthome}
            let preparation=await execute(client.invitations,uid,ClientRevocationProtocol.shared.prepare(workspace:wid,clientUid:target))
            check(preparation.error==nil,"live-prepare")
            let outcome=await execute(client.invitations,uid,ClientRevocationProtocol.shared.command(workspace:wid,binding:preparation.reply!.revocationPreparation!,key:CommandResolutionKt.resolutionCommandKey(nonce:UUID().uuidString)))
            check(outcome.reply?.revocationDecision?.status=="client-revoked","live-revocation")
            await until {[.accesslost,.workspacegate].contains(observed.identity.currentState().status)}
            check(observer.hasPinnedIdentity(),"client-auth-not-forced-out")
            observed.identity.onEvent(event:IdentityEventChangeWorkspace.shared);await until {observed.identity.currentState().status == .clienthome}
            check(observed.identity.currentState().verifiedWorkspaceId==base+"_other_workspace","other-workspace-explicitly-selected")
            observed.identity.onEvent(event:IdentityEventSignOut.shared);await until {observed.identity.currentState().status == .signedout};observed.clear()
        } else if phase.hasSuffix("stage") {
            root.details.bind(scope:ClientScope(uid:uid,workspaceId:wid,trainerId:uid,authorityEpoch:root.identity.currentState().authorityEpoch),clientId:target)
            await until {root.details.currentState().revocationPreparation != nil}
            var held=false
            if mode=="fenced" {bridge.debugBeforeInvitationTransport={_ in held=true}}
            else if mode=="proof" {bridge.debugBeforeCommandRetirement={print("M5P4_IOS_PROOF_DURABLE");fflush(stdout);while true {Thread.sleep(forTimeInterval:0.1)}}}
            else {bridge.debugBeforeInvitationCompletion={_ in held=true}}
            root.details.onEvent(event:ClientsEventRevoke.shared)
            root.details.onEvent(event:ClientsEventCancelRevocation(prompt:root.details.currentState().revocationPrompt!))
            root.details.onEvent(event:ClientsEventRevoke.shared)
            let prompt=root.details.currentState().revocationPrompt!
            root.details.onEvent(event:ClientsEventConfirmRevocation(prompt:prompt));root.details.onEvent(event:ClientsEventConfirmRevocation(prompt:prompt))
            if mode != "proof" {
                await until {held};check(client.invitations.pending(uid:uid)?.action=="revoke-client","original-protected")
                root.identity.onEvent(event:IdentityEventSignOut.shared);await until {[.lockedrecovery,.departureblocked].contains(root.identity.currentState().status)}
                check(await signIn(client,target,password)?.code == .failedPrecondition,"pending-blocks-b")
                print("M5P4_IOS_ORIGINAL_HELD");fflush(stdout)
            }
            while true {try? await Task.sleep(nanoseconds:100_000_000)}
        } else if mode=="proof" {
            check(client.invitations.pending(uid:uid)==nil && client.invitations.lastSettled(uid:uid)?.valid()==true,"restart-proof-retirement")
        } else {
            let original=client.invitations.pending(uid:uid)!
            check(original.action=="revoke-client" && original.revocation?.clientUid==target,"exact-original-restored")
            let outcome=await execute(client.invitations,uid,CommandResolution.shared.resolveRequest(p:original))
            check(outcome.error==nil && outcome.reply?.terminalOutcome?.outcome==(mode=="applied" ? "applied":"not-applied-fenced"),"minimal-own-outcome")
            check(client.invitations.pending(uid:uid)==nil && client.invitations.lastSettled(uid:uid)?.valid()==true,"durable-settlement")
        }
        root.clear();root=NativeClientsModels(client:client)
        await until {[.trainerhome,.accesslost,.workspacegate].contains(root.identity.currentState().status)}
        if mode != "live" {check(root.identity.currentState().status != .trainerhome,"outcome-restores-no-role")}
        root.identity.onEvent(event:IdentityEventSignOut.shared);await until {root.identity.currentState().status == .signedout}
        let replacement=root.identity.activeClient();root.clear();check(await signIn(replacement,target,password)==nil,"clean-b-sign-in")
        root=NativeClientsModels(client:replacement);await until {[.clienthome,.onboarding,.accesslost,.workspacegate].contains(root.identity.currentState().status)}
        check(replacement.invitations?.pending(uid:target)==nil && replacement.invitations?.lastSettled(uid:target)==nil,"b-has-no-a-command-or-proof")
        root.identity.onEvent(event:IdentityEventSignOut.shared);await until {root.identity.currentState().status == .signedout};root.clear()
        check(true,"clean-departure");exit(0)
    }
}
#endif
