import Foundation
import Darwin
import Shared

#if DEBUG
enum ProgramDraftsHarness {
    @MainActor private static var bridge: FirebaseNativeBridge!
    @MainActor private static var client: IosProductIdentityClient!
    @MainActor private static var root: NativeClientsModels!
    private static func check(_ ok: Bool, _ label: String) {
        print("M6P3_IOS \(label)=\(ok ? "PASS" : "FAIL")");fflush(stdout)
        if !ok { exit(1) }
    }
    static func prepareUIIfRequested(bridge: FirebaseNativeBridge) {
        let e=ProcessInfo.processInfo.environment
        guard e["TF_M6P3_UI"]=="1",let prefix=e["TF_M6P3_PREFIX"],let password=e["TF_M6P3_PASSWORD"],bridge.debugCurrentUid==nil else{return}
        _=bridge.signIn(email:"\(prefix)_ios_ui@example.invalid",password:password,accountEpoch:0){check($0.failure==nil,"ui-fixture-auth")}
    }
    static func runIfRequested() {
        let e=ProcessInfo.processInfo.environment
        if let fixtures=e["TF_M6P3_STORE_SIZING"] {
            Task { @MainActor in storeSizing(fixtures) }
            return
        }
        guard let phase=e["TF_M6P3_NATIVE"],let prefix=e["TF_M6P3_PREFIX"],let password=e["TF_M6P3_PASSWORD"] else{return}
        Task { @MainActor in await run(phase,prefix,password) }
    }
    @MainActor private static func storeSizing(_ fixtures:String) {
        do {
            let files=try FileManager.default.contentsOfDirectory(at:URL(fileURLWithPath:fixtures),includingPropertiesForKeys:nil).filter{$0.pathExtension=="json"}.sorted{$0.lastPathComponent<$1.lastPathComponent}
            check(files.count==49,"sizing-fixtures")
            let path=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("m6p3-sizing-\(UUID().uuidString)")
            let store=RecoveryPersistenceBridge(rootURL:path),adapter=IosAtomicFilePersistence(bridge:store)
            var maximum=0
            var command:PendingInvitationCommand?
            for file in files {
                let bytes=try Data(contentsOf:file),plaintext=String(data:bytes,encoding:.utf8)!
                let uid=(try JSONSerialization.jsonObject(with:bytes) as! [String:Any])["uid"] as! String
                check(store.write(uid:uid,plaintext:plaintext).failureCode==nil,"native-fixture-write")
                let record=adapter.read(uid:uid)!
                adapter.write(envelope:record) // Exercise the real Kotlin serializer and native atomic replacement too.
                check(store.read(uid:uid).payload==DraftBudget.shared.plaintext(record:record),"native-round-trip")
                let count=try store.debugRawData(uid:uid).count;maximum=max(maximum,count)
                check(count<=98304,"native-wrapper-budget")
                print("M6P3_SWIFT_STORE fixture=\(file.lastPathComponent) plaintext=\(DraftBudget.shared.plaintext(record:record).utf8.count) encrypted=\(count)")
                if file.lastPathComponent=="draft-0.json" {command=record.pendingInvitationCommands.first}
            }
            let pending=command!,uid=pending.uid
            let oversized=AccountPersistenceEnvelope(schemaVersion:1,uid:uid,offlineAccessGrant:nil,downloadManifests:[],workoutRecoverySnapshot:nil,mutationJournal:[],pendingUploads:[],switchMarker:nil,preferredWorkspaceId:String(repeating:"x",count:36500),pendingInvitationCommands:[],pendingTerminalProof:nil,lastSettledCommand:nil)
            adapter.write(envelope:oversized)
            let original=try store.debugRawData(uid:uid),ledger=InvitationLedger(store:adapter),request=CommandResolution.shared.original(p:pending)
            check(!ledger.draftAdmission(uid:uid,request:request),"oversize-admission-denied")
            check(!ledger.prepare(uid:uid,request:request),"oversize-prepare-denied")
            check(try store.debugRawData(uid:uid)==original,"oversize-storage-byte-identical")
            print("M6P3_SWIFT_STORE maximum=\(maximum)");fflush(stdout)
            // Preserve the synthetic partition/key and all original application data.
            exit(0)
        } catch {check(false,"native-sizing-failed")}
    }
    @MainActor private static func until(_ ready:()->Bool) async {
        let end=Date().addingTimeInterval(45)
        while !ready() && Date()<end {try? await Task.sleep(nanoseconds:50_000_000)}
        check(ready(),"bounded-state")
    }
    @MainActor private static func execute(_ uid:String,_ r:InvitationRequest) async -> InvitationOutcome {
        await withCheckedContinuation{c in _=client.invitations.execute(uid:uid,request:r){c.resume(returning:$0)}}
    }
    @MainActor private static func read(_ uid:String,_ wid:String,_ id:String?=nil) async -> DraftReadResult {
        await withCheckedContinuation{c in _=client.drafts.read(scope:ExerciseScope(uid:uid,workspaceId:wid,epoch:1),request:DraftReadRequest(workspaceId:wid,action:id==nil ? "draft-prepare" : "draft-get",templateId:id,afterId:nil)){c.resume(returning:$0)}}
    }
    @MainActor private static func signIn(_ c:ProductIdentityClient,_ uid:String,_ password:String) async -> StableFirebaseFailure? {
        await withCheckedContinuation{k in _=c.signIn(email:"\(uid)@example.invalid",password:password,epoch:0){k.resume(returning:$0)}}
    }
    @MainActor private static func command(_ uid:String,_ wid:String,_ id:String,_ revision:Int32,source:Bool=false) async -> InvitationRequest {
        let prep=await read(uid,wid);check(prep.error==nil && prep.authority != nil,"fresh-prepare")
        return DraftProtocol.shared.write(workspace:wid,draft:DraftSave(templateId:id,expectedRevision:revision,authority:prep.authority!,title:"Synthetic native",description:"Original restart demonstration.",workouts:source ? [DraftWorkout(id:"00000000-0000-4000-8000-000000000002",title:"",items:[DraftItem(id:"00000000-0000-4000-8000-000000000003",sourceKind:"system",sourceId:"missing-native-source",sourceRevision:nil,sourceHash:String(repeating:"a",count:64),name:"Synthetic",prescription:DraftPrescription(sets:nil,reps:nil,restSeconds:nil,loadGrams:nil))])] : []),key:CommandResolutionKt.resolutionCommandKey(nonce:UUID().uuidString))
    }
    @MainActor private static func run(_ phase:String,_ prefix:String,_ password:String) async {
        guard prefix.range(of:"^m6p3r[0-9a-z]{6,24}$",options:.regularExpression) != nil else{exit(1)}
        let parts=phase.split(separator:"-"),kind=String(parts[0]),mode=String(parts[1]),uid="\(prefix)_ios_\(kind)_\(mode)",other="\(prefix)_ios_other"
        let wid="\(uid)_ws",id="00000000-0000-4000-8000-000000000001"
        bridge=FirebaseNativeBridge(productMemoryCache:true,identityDefaults:UserDefaults(suiteName:uid)!)
        let path=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent(uid)
        let store=RecoveryPersistenceBridge(rootURL:path)
        client=IosProductIdentityClient(bridge:bridge,recoveryBridge:store)
        if bridge.debugCurrentUid==nil {let failure=await signIn(client,uid,password);if let failure {print("M6P3_IOS sign-in-code=\(failure.code)");fflush(stdout)};check(failure==nil,"sign-in")}
        check(bridge.debugCurrentUid==uid,"original-uid");root=NativeClientsModels(client:client)
        await until{[.trainerhome,.lockedrecovery].contains(root.identity.currentState().status)}
        if phase.hasSuffix("stage") {
            check(client.freeze(uid:uid),"freeze");check(await read(uid,wid).error != nil,"freeze-denies-read");client.unfreeze(uid:uid)
            if kind=="correct" {check(await execute(uid,command(uid,wid,id,0)).error==nil,"initial-create")}
            let original=await command(uid,wid,id,kind=="correct" ? 1 : 0,source:mode=="source")
            if mode=="conflict" {check(await execute(uid,command(uid,wid,id,kind=="correct" ? 1 : 0)).error==nil,"competing-write")}
            var held=false
            if mode=="fenced" {bridge.debugBeforeInvitationTransport={_ in held=true}}
            else if mode=="proof" {bridge.debugBeforeCommandRetirement={print("M6P3_IOS_PROOF_DURABLE");fflush(stdout);while true {Thread.sleep(forTimeInterval:0.1)}}}
            else if mode=="failure" {var writes=0;store.debugBeforeCommit={_,_ in writes+=1;if writes==2 {throw NSError(domain:"synthetic-proof-failure",code:1)}}}
            else if mode != "settled" {bridge.debugBeforeInvitationCompletion={_ in held=true}}
            if mode=="admission" {
                bridge.debugBeforeInvitationCompletion=nil
                check(client.freeze(uid:uid),"freeze-new-write")
                check(await execute(uid,original).error != nil && client.invitations.pending(uid:uid)==nil,"frozen-command-not-prepared");client.unfreeze(uid:uid)
                var release:(()->Void)?;var callbacks=0
                bridge.debugBeforeInvitationTransport={release=$0}
                let delivery=client.invitations.execute(uid:uid,request:original){_ in callbacks+=1};await until{release != nil}
                check(client.freeze(uid:uid),"freeze-admitted-original");delivery.cancel();release!();await until{client.invitations.pending(uid:uid)==nil}
                check(callbacks==0 && client.invitations.lastSettled(uid:uid)?.marker != nil,"cancelled-presentation-still-settles");bridge.debugBeforeInvitationTransport=nil;client.unfreeze(uid:uid)
                print("M6P3_IOS_READY_admission");fflush(stdout)
            } else if ["settled","failure"].contains(mode) {
                let result=await execute(uid,original)
                if mode=="failure" {check(result.error=="storage-unavailable" && client.invitations.pending(uid:uid) != nil,"proof-write-failure-retains-original")}
                else {check(result.error==nil && client.invitations.lastSettled(uid:uid)?.command==nil,"settled-marker")}
                print("M6P3_IOS_READY_\(mode)");fflush(stdout)
            } else {
                _=client.invitations.execute(uid:uid,request:original){_ in}
                if mode != "proof" {
                    await until{held};check(client.invitations.pending(uid:uid)?.draftSave?.description_=="Original restart demonstration.","original-protected")
                    root.identity.onEvent(event:IdentityEventSignOut.shared);await until{[.lockedrecovery,.departureblocked].contains(root.identity.currentState().status)}
                    check(await signIn(client,other,password) != nil,"pending-blocks-b");print("M6P3_IOS_ORIGINAL_HELD");fflush(stdout)
                }
            }
            while true {try? await Task.sleep(nanoseconds:100_000_000)}
        }
        if !["proof","settled","admission"].contains(mode) {
            let p=client.invitations.pending(uid:uid)!
            check(p.action=="save-program-draft" && p.draftSave?.description_=="Original restart demonstration.","exact-original-restored")
            let outcome=await execute(uid,CommandResolution.shared.resolveRequest(p:p))
            let expected=mode=="fenced" ? "not-applied-fenced" : ["conflict","source"].contains(mode) ? "not-applied-conflict" : "applied"
            check(outcome.error==nil && outcome.reply?.terminalOutcome?.outcome==expected,"own-outcome")
        }
        let proof=client.invitations.lastSettled(uid:uid)
        check(client.invitations.pending(uid:uid)==nil && proof?.valid()==true && proof?.command==nil && proof?.marker != nil,"content-free-settlement")
        let plaintext=store.read(uid:uid).payload ?? ""
        check(!plaintext.contains("Original restart") && !plaintext.contains(id) && !plaintext.contains("draftSave"),"settled-plaintext-has-no-content")
        let detail=await read(uid,wid,id)
        check(kind=="create" && ["fenced","source"].contains(mode) ? detail.error != nil : detail.draft != nil,"fresh-detail-after-restart")
        root.identity.onEvent(event:IdentityEventSignOut.shared);await until{root.identity.currentState().status == .signedout}
        let next=root.identity.activeClient();root.clear();check(await signIn(next,other,password)==nil,"clean-b-admission")
        root=NativeClientsModels(client:next);await until{root.identity.currentState().status == .trainerhome}
        check(next.invitations?.pending(uid:other)==nil && next.invitations?.lastSettled(uid:other)==nil,"uid-isolation")
        root.identity.onEvent(event:IdentityEventSignOut.shared);await until{root.identity.currentState().status == .signedout};root.clear()
        print("M6P3_IOS_PASS_\(kind)_\(mode)");fflush(stdout);exit(0)
    }
}
#endif
