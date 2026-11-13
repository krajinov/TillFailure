// Separate simulator sizing executable. Never included in the product Xcode target.
import Foundation
import CryptoKit
import Security
import Shared

@main
struct AssignmentStorageMain {
    static func main() throws {
        let env=ProcessInfo.processInfo.environment
        guard let fixturePath=env["TF_M7_PR1A_FIXTURES"] else { fatalError("Missing independent fixture directory") }
        let manager=FileManager.default
        let fixtures=try manager.contentsOfDirectory(at:URL(fileURLWithPath:fixturePath),includingPropertiesForKeys:nil)
            .filter{$0.pathExtension=="json" && !$0.lastPathComponent.contains("oversize")}.sorted{$0.lastPathComponent<$1.lastPathComponent}
        precondition(!fixtures.isEmpty)
        let root=manager.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("m7-pr1a-sizing-\(UUID().uuidString)")
        // The simulator is task-owned and new. This key never replaces an existing identity/key.
        let lookup:[String:Any]=[kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:"com.delminiusapps.tillfailure.recovery",kSecAttrAccount as String:"tillfailure.recovery.v1",kSecReturnAttributes as String:true,kSecMatchLimit as String:kSecMatchLimitOne]
        var attributes:CFTypeRef?
        let keyStatus=SecItemCopyMatching(lookup as CFDictionary,&attributes)
        print("M7_SWIFT_KEYCHAIN metadataStatus=\(keyStatus)")
        precondition(keyStatus==errSecSuccess || keyStatus==errSecItemNotFound,"Independent keychain unavailable; no replacement allowed")
        var maximum=0,maximumPlaintext=0
        for (index,file) in fixtures.enumerated() {
            let store=RecoveryPersistenceBridge(rootURL:root.appendingPathComponent(String(index)))
            let data=try Data(contentsOf:file), text=String(data:data,encoding:.utf8)!
            let value=try JSONSerialization.jsonObject(with:data) as! [String:Any],uid=value["uid"] as! String
            let result=store.write(uid:uid,plaintext:text)
            precondition(result.failureCode==nil,"Production native write locked")
            precondition(store.read(uid:uid).payload==text,"Production native round trip changed bytes")
            let encoded=try store.debugRawData(uid:uid)
            let bound=try RecoveryEnvelopeEncoder.upperBound(plaintextBytes:data.count,keyIdentifier:"tillfailure.recovery.v1")
            precondition(data.count<=36500 && encoded.count<=bound && encoded.count<=98304)
            maximum=max(maximum,encoded.count);maximumPlaintext=max(maximumPlaintext,data.count)
        }
        let fixture=try Data(contentsOf:fixtures.first!),object=try JSONSerialization.jsonObject(with:fixture) as! [String:Any]
        let uid=object["uid"] as! String
        let pending=object["pendingInvitationCommands"] as! [[String:Any]]
        // First fixture is the non-retired state 0, explicitly checked rather than assumed.
        precondition(pending.count==1)
        let command=pending[0]
        let descriptor:[String:Any]=["action":"assign-program-version","workspaceId":command["workspaceId"]!,"idempotencyKey":command["idempotencyKey"]!,"assignment":command["assignment"]!]
        let descriptorText=String(data:try JSONSerialization.data(withJSONObject:descriptor,options:[.sortedKeys,.withoutEscapingSlashes]),encoding:.utf8)!
        var prior=object
        prior["pendingInvitationCommands"]=[];prior["pendingTerminalProof"]=NSNull();prior["lastSettledCommand"]=NSNull();prior["switchMarker"]=NSNull()
        prior["preferredWorkspaceId"]=String(repeating:"x",count:36500)
        let priorText=String(data:try JSONSerialization.data(withJSONObject:prior,options:[.sortedKeys,.withoutEscapingSlashes]),encoding:.utf8)!
        let store=RecoveryPersistenceBridge(rootURL:root.appendingPathComponent("oversize"))
        precondition(store.write(uid:uid,plaintext:priorText).failureCode==nil)
        let original=try store.debugRawData(uid:uid)
        precondition(!AssignmentProspective.shared.admissionJson(prior:priorText,descriptor:descriptorText))
        let after=try store.debugRawData(uid:uid)
        precondition(after==original,"Oversize refusal changed existing encrypted bytes")
        let result:[String:Any]=["fixtures":fixtures.count,"maximumPlaintext":maximumPlaintext,"maximumEncrypted":maximum,"oversizeByteIdentical":true]
        let summary=try JSONSerialization.data(withJSONObject:result,options:[.sortedKeys])
        print("M7_SWIFT_NATIVE \(String(data:summary,encoding:.utf8)!)")
        if let output=env["TF_M7_PR1A_OUTPUT"] {try summary.write(to:URL(fileURLWithPath:output),options:.atomic)}
        // Retain all new ciphertext/key state. No production Firebase client is initialized.
    }
}
