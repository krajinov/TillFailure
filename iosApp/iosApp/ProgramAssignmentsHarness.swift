import Foundation
import Darwin
import Security
import Shared

#if DEBUG
enum ProgramAssignmentsHarness {
    @MainActor private static var bridge: FirebaseNativeBridge!
    @MainActor private static var client: IosProductIdentityClient!
    @MainActor private static var root: NativeClientsModels!
    private static func check(_ ok: Bool, _ label: String) {
        print("M7P1B_IOS \(label)=\(ok ? "PASS" : "FAIL")");fflush(stdout)
        if !ok { exit(1) }
    }
    static func prepareUIIfRequested(bridge: FirebaseNativeBridge) {
        let e=ProcessInfo.processInfo.environment
        guard e["TF_M7P1B_UI"]=="1",let prefix=e["TF_M7P1B_PREFIX"],let password=e["TF_M7P1B_PASSWORD"],bridge.debugCurrentUid==nil else{return}
        let role=e["TF_M7P1B_UI_ROLE"]=="client" ? "_client" : ""
        _=bridge.signIn(email:"\(prefix)_ios_assign_ui\(role)@example.invalid",password:password,accountEpoch:0){check($0.failure==nil,"ui-fixture-auth")}
    }
    static func runIfRequested() {
        let e=ProcessInfo.processInfo.environment
        guard let phase=e["TF_M7P1B_NATIVE"],let prefix=e["TF_M7P1B_PREFIX"],let password=e["TF_M7P1B_PASSWORD"] else{return}
        Task { @MainActor in await run(phase,prefix,password) }
    }
    @MainActor private static func until(_ ready:()->Bool) async {
        let end=Date().addingTimeInterval(45)
        while !ready() && Date()<end {try? await Task.sleep(nanoseconds:50_000_000)}
        check(ready(),"bounded-state")
    }
    @MainActor private static func execute(_ uid:String,_ r:InvitationRequest) async -> InvitationOutcome {
        await withCheckedContinuation{c in _=client.invitations.execute(uid:uid,request:r){c.resume(returning:$0)}}
    }
    private static func request(_ action:String,_ wid:String,_ client:String,_ template:String?=nil,_ version:String?=nil,_ scheduled:Int64?=nil,_ assignment:AssignmentCreate?=nil,_ item:String?=nil) -> AssignmentReadRequest {
        AssignmentReadRequest(action:action,workspaceId:wid,clientUid:client,templateId:template,versionId:version,scheduledInstantMillis:scheduled.map{KotlinLong(longLong:$0)},assignment:assignment,itemId:item,assignmentId:nil,relativePath:nil,after:nil,afterId:nil)
    }
    private static func conflict(_ a:AssignmentCreate) -> AssignmentCreate {
        AssignmentCreate(assignmentId:a.assignmentId,plannedWorkoutId:a.plannedWorkoutId,clientUid:a.clientUid,templateId:a.templateId,versionId:a.versionId,versionNumber:a.versionNumber,sourceContentHash:a.sourceContentHash,copyPolicyVersion:a.copyPolicyVersion,snapshotHash:String(repeating:"a",count:64),authority:a.authority,preparedAtMillis:a.preparedAtMillis,scheduledInstantMillis:a.scheduledInstantMillis,accessExpiresAtMillis:a.accessExpiresAtMillis)
    }
    @MainActor private static func modelState(_ model:AssignmentViewModel) -> AssignmentState {model.state.value as! AssignmentState}
    @MainActor private static func read(_ uid:String,_ request:AssignmentReadRequest) async -> AssignmentReadResult {
        await withCheckedContinuation{c in _=client.assignments.read(scope:ExerciseScope(uid:uid,workspaceId:request.workspaceId,epoch:1),request:request){c.resume(returning:$0)}}
    }
    @MainActor private static func signIn(_ c:ProductIdentityClient,_ uid:String,_ password:String) async -> StableFirebaseFailure? {
        await withCheckedContinuation{k in _=c.signIn(email:"\(uid)@example.invalid",password:password,epoch:0){k.resume(returning:$0)}}
    }
    @MainActor private static func command(_ uid:String,_ wid:String,_ id:String,_ conflict:Bool=false) async -> InvitationRequest {
        let other=uid+"_client",result=await read(uid,request("assignment-prepare",wid,other,id,"v0000000001",Int64(Date().timeIntervalSince1970*1000)+3600000))
        check(result.error==nil && result.preparation != nil,"fresh-exact-preparation")
        let p=result.preparation!;var items:[PublicationItem]=[]
        for itemId in p.preview.workouts[0].itemIds {
            let item=await read(uid,request("assignment-preview-item",wid,other,nil,nil,nil,p.assignment,itemId));check(item.error==nil && item.item != nil,"complete-child-preview");items.append(item.item!)
        }
        _ = p.complete(uid:uid,items:items)
        return AssignmentProtocol.shared.write(workspace:wid,assignment:conflict ? Self.conflict(p.assignment) : p.assignment,key:client.invitations.doNewCommandKey())
    }
    @MainActor private static func run(_ phase:String,_ prefix:String,_ password:String) async {
        guard prefix.range(of:"^m7p1br[a-f0-9]{8}$",options:.regularExpression) != nil else{exit(1)}
        if phase=="trainer-expiry" {
            let uid="\(prefix)_ios_trainer_expiry",wid="\(uid)_ws",other=uid+"_client"
            bridge=FirebaseNativeBridge(productMemoryCache:true,identityDefaults:UserDefaults(suiteName:uid)!)
            let path=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent(uid)
            client=IosProductIdentityClient(bridge:bridge,recoveryBridge:RecoveryPersistenceBridge(rootURL:path))
            check(await signIn(client,uid,password)==nil,"trainer-expiry-auth")
            root=NativeClientsModels(client:client);await until{root.identity.currentState().status == .trainerhome}
            let r=AssignmentReadRequest(action:"assignment-get",workspaceId:wid,clientUid:other,templateId:nil,versionId:nil,scheduledInstantMillis:nil,assignment:nil,itemId:nil,assignmentId:"00000000-0000-4000-8000-000000000003",relativePath:nil,after:nil,afterId:nil)
            let detail=await read(uid,r);check(detail.error==nil && detail.serverNowMillis != nil,"trainer-server-bound-packet")
            let model=AssignmentViewModel(repository:client.assignments,drafts:nil)
            model.attach(scope:AssignmentScope(uid:uid,workspaceId:wid,clientUid:other,role:"trainer",epoch:1));await until{!modelState(model).busy}
            model.onEvent(event:AssignmentEventOpen(id:r.assignmentId!));await until{!modelState(model).busy}
            check(modelState(model).content != nil && modelState(model).error==nil,"trainer-complete-copy")
            await until{modelState(model).content==nil}
            check(modelState(model).detail==nil && modelState(model).plan==nil && modelState(model).error?.contains("expired")==true,"trainer-server-expiry-clears-detail")
            model.clear();check(client.invitations.pending(uid:uid)==nil,"trainer-read-no-command")
            print("M7P1B_IOS_TRAINER_EXPIRY");fflush(stdout)
            while true {try? await Task.sleep(nanoseconds:100_000_000)}
        }
        if ["read-header-good","read-header-bad"].contains(phase) {
            let mode=phase.hasSuffix("good") ? "good" : "bad"
            let uid="\(prefix)_ios_header_\(mode)_client",wid="\(prefix)_ios_header_\(mode)_ws"
            bridge=FirebaseNativeBridge(productMemoryCache:true,identityDefaults:UserDefaults(suiteName:uid)!)
            let path=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent(uid)
            client=IosProductIdentityClient(bridge:bridge,recoveryBridge:RecoveryPersistenceBridge(rootURL:path))
            check(await signIn(client,uid,password)==nil,"header-fixture-auth")
            check(client.claimPinnedIdentity(uid:uid),"header-fixture-identity-pin")
            let result=await read(uid,request("client-assignment-list",wid,uid))
            check(mode=="good" ? result.error==nil && result.headers.count==1 : result.error != nil && result.headers.isEmpty,"header-path-binding-\(mode)")
            check(client.invitations.pending(uid:uid)==nil,"read-does-not-issue-command")
            print("M7P1B_IOS_HEADER_READ_\(mode)");fflush(stdout)
            // The host verifies a complete export before stopping this task process.
            while true {try? await Task.sleep(nanoseconds:100_000_000)}
        }
        let parts=phase.split(separator:"-"),kind=String(parts[0]),mode=String(parts[1]),uid="\(prefix)_ios_\(kind)_\(mode)",other="\(uid)_client"
        let wid="\(uid)_ws",id="00000000-0000-4000-8000-000000000001"
        bridge=FirebaseNativeBridge(productMemoryCache:true,identityDefaults:UserDefaults(suiteName:uid)!)
        let path=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent(uid)
        let store=RecoveryPersistenceBridge(rootURL:path)
        client=IosProductIdentityClient(bridge:bridge,recoveryBridge:store)
        if bridge.debugCurrentUid==nil {let failure=await signIn(client,uid,password);if let failure {print("M7P1B_IOS sign-in-code=\(failure.code)");fflush(stdout)};check(failure==nil,"sign-in")}
        check(bridge.debugCurrentUid==uid,"original-uid");root=NativeClientsModels(client:client)
        await until{[.trainerhome,.lockedrecovery].contains(root.identity.currentState().status)}
        if phase.hasSuffix("stage") {
            check(client.freeze(uid:uid),"freeze");check(await read(uid,request("assignment-list",wid,other)).error != nil,"freeze-denies-read");client.unfreeze(uid:uid)
            let original=await command(uid,wid,id,mode=="conflict")
            let descriptor = original.pending(uid: uid)!
            print("M7P1B_COMMAND \(CommandResolution.shared.id(p: descriptor)) \(CommandResolution.shared.hash(p: descriptor))")
            print("M7P1B_BINDING_JSON \(CommandResolution.shared.request(p:descriptor))");fflush(stdout)
            var held=false
            if mode=="fenced" {bridge.debugBeforeInvitationTransport={_ in held=true}}
            else if mode=="proof" {bridge.debugBeforeCommandRetirement={print("M7P1B_IOS_PROOF_DURABLE");fflush(stdout);while true {Thread.sleep(forTimeInterval:0.1)}}}
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
                print("M7P1B_IOS_READY_admission");fflush(stdout)
            } else if ["settled","failure"].contains(mode) {
                let result=await execute(uid,original)
                if mode=="failure" {check(result.error=="storage-unavailable" && client.invitations.pending(uid:uid) != nil,"proof-write-failure-retains-original")}
                else {check(result.error==nil && client.invitations.lastSettled(uid:uid)?.command==nil,"settled-marker")}
                print("M7P1B_IOS_READY_\(mode)");fflush(stdout)
            } else {
                _=client.invitations.execute(uid:uid,request:original){_ in}
                if mode != "proof" {
                    await until{held};check(client.invitations.pending(uid:uid)?.assignment?.templateId==id,"original-protected")
                    root.identity.onEvent(event:IdentityEventSignOut.shared);await until{[.lockedrecovery,.departureblocked].contains(root.identity.currentState().status)}
                    check(await signIn(client,other,password) != nil,"pending-blocks-b");print("M7P1B_IOS_ORIGINAL_HELD");fflush(stdout)
                }
            }
            while true {try? await Task.sleep(nanoseconds:100_000_000)}
        }
        if !["proof","settled","admission"].contains(mode) {
            let p=client.invitations.pending(uid:uid)!
            check(p.action=="assign-program-version" && p.assignment?.templateId==id && p.assignment?.clientUid==other,"exact-original-restored")
            let outcome=await execute(uid,CommandResolution.shared.resolveRequest(p:p))
            let expected=mode=="fenced" ? "not-applied-fenced" : ["conflict","source"].contains(mode) ? "not-applied-conflict" : "applied"
            check(outcome.error==nil && outcome.reply?.terminalOutcome?.outcome==expected,"own-outcome")
        }
        let proof=client.invitations.lastSettled(uid:uid)
        check(client.invitations.pending(uid:uid)==nil && proof?.valid()==true && proof?.command==nil && proof?.marker != nil,"content-free-settlement")
        let marker = proof!.marker!
        let expectedMarker = mode == "fenced" ? "not-applied-fenced" : mode == "conflict" ? "not-applied-conflict" : "applied"
        check(marker.uid == uid && marker.commandKind == "program-assignment-create" && marker.outcome == expectedMarker, "marker-identity-outcome")
        print("M7P1B_MARKER \(marker.commandId) \(marker.requestHash)")
        fflush(stdout)
        let plaintext=store.read(uid:uid).payload ?? ""
        check(!plaintext.contains("Original restart") && !plaintext.contains(id) && !plaintext.contains("draftHash"),"settled-plaintext-has-no-content")
        do {
            let archive=path.appendingPathComponent("evidence",isDirectory:true);try FileManager.default.createDirectory(at:archive,withIntermediateDirectories:true)
            let bytes=try store.debugRawData(uid:uid);try bytes.write(to:archive.appendingPathComponent("settled-ciphertext.json"),options:.atomic)
            check(try Data(contentsOf:archive.appendingPathComponent("settled-ciphertext.json"))==bytes,"settled-checkpoint-byte-identical")
        } catch{check(false,"checkpoint-failed")}
        let detail=await read(uid,request("assignment-list",wid,other))
        check(detail.error==nil && detail.headers.count==(["fenced","conflict"].contains(mode) ? 0 : 1),"fresh-committed-assignment-count")
        root.identity.onEvent(event:IdentityEventSignOut.shared);await until{root.identity.currentState().status == .signedout}
        let next=root.identity.activeClient();root.clear();check(await signIn(next,other,password)==nil,"clean-b-admission")
        root=NativeClientsModels(client:next);await until{root.identity.currentState().status == .clienthome}
        check(next.invitations?.pending(uid:other)==nil && next.invitations?.lastSettled(uid:other)==nil,"uid-isolation")
        let own:AssignmentReadResult=await withCheckedContinuation { c in
            _=next.assignments!.read(scope:ExerciseScope(uid:other,workspaceId:wid,epoch:1),request:request("client-assignment-list",wid,other)){c.resume(returning:$0)}
        }
        check(own.error==nil && own.headers.count==(["fenced","conflict"].contains(mode) ? 0 : 1),"client-b-own-header-discovery")
        if !own.headers.isEmpty {
            let model=AssignmentViewModel(repository:next.assignments!,drafts:nil)
            model.attach(scope:AssignmentScope(uid:other,workspaceId:wid,clientUid:other,role:"client",epoch:1))
            await until{!modelState(model).busy};model.onEvent(event:AssignmentEventOpen(id:own.headers[0].assignmentId))
            await until{!modelState(model).busy}
            check(modelState(model).content != nil && modelState(model).error==nil && AssignmentContract.shared.doCopyHash(c:modelState(model).content!)==own.headers[0].snapshotHash,"client-complete-server-copy-without-source");model.clear()
        }
        let foreign:AssignmentReadResult=await withCheckedContinuation {c in
            _=next.assignments!.read(scope:ExerciseScope(uid:other,workspaceId:wid,epoch:1),request:request("assignment-list",wid,other)){c.resume(returning:$0)}
        }
        check(foreign.error != nil,"b-denied-trainer-discovery")
        root.identity.onEvent(event:IdentityEventSignOut.shared);await until{root.identity.currentState().status == .signedout};root.clear()
        print("M7P1B_IOS_PASS_\(kind)_\(mode)");fflush(stdout);exit(0)
    }
}
#endif
