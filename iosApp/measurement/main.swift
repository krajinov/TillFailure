import Foundation
import CryptoKit
let root=URL(fileURLWithPath:CommandLine.arguments[1])
let key=SymmetricKey(size:.bits256)
let admissionBound=try RecoveryEnvelopeEncoder.upperBound(plaintextBytes:36500,keyIdentifier:"tillfailure.recovery.v1")
precondition(admissionBound==97517 && admissionBound<=98304)
print("SWIFT_PRODUCTION admissionPlaintext=36500 deterministicBound=\(admissionBound)")
var maximum=0
for url in try FileManager.default.contentsOfDirectory(at:root,includingPropertiesForKeys:nil).sorted(by:{$0.lastPathComponent<$1.lastPathComponent}) {
    let data=try Data(contentsOf:url)
    let encoded=try RecoveryEnvelopeEncoder.encode(plaintext:data,key:key,associatedData:Data("sizing".utf8),keyIdentifier:"tillfailure.recovery.v1")
    let bound = try RecoveryEnvelopeEncoder.upperBound(plaintextBytes: data.count, keyIdentifier: "tillfailure.recovery.v1")
    precondition(encoded.count <= bound)
    if url.lastPathComponent.hasPrefix("draft-") || url.lastPathComponent.hasPrefix("prior-") || url.lastPathComponent.hasPrefix("switch-") { precondition(data.count <= 36500 && bound <= 98304) }
    maximum=max(maximum,encoded.count)
    print("SWIFT_PRODUCTION fixture=\(url.lastPathComponent) plaintext=\(data.count) encrypted=\(encoded.count) bound=\(bound)")
}
print("SWIFT_PRODUCTION maximum=\(maximum)")
