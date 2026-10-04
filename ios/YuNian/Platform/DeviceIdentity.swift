import Foundation
import CryptoKit
import UIKit

/// iOS 设备身份 —— 替代 Android 侧的 `SuFlowApi.deviceFingerprint()` 与
/// `DeviceRequestSigner.deviceId()`。
///
/// ⚠️ **这不是纯客户端改动**：`deviceId` 参与服务端请求签名的 payload
/// （`v1\nMETHOD\nPATH\nBODY_SHA256\nTS\nNONCE\nCLIENT_ID\nDEVICE_ID`），
/// 因此服务端必须接受 iOS 形态的 deviceId。详见
/// `docs/ios-port-feasibility.md` §5.6 与 §9 的 V9。
///
/// Android 侧的取值（`DeviceRequestSigner.deviceId()`）：
///     sha256Hex("$FINGERPRINT|$MANUFACTURER|$MODEL|$BRAND").take(32)
///
/// iOS 没有对应的 Build 字段，且 `identifierForVendor` 会在**卸载同厂商全部 App 后改变**，
/// 语义上与 Android 的 Build 指纹不同。这里保持**形状一致**（32 位小写 hex），
/// 但用 iOS 可得的稳定标识：
///     sha256Hex("iOS|$identifierForVendor|$modelIdentifier").take(32)
enum DeviceIdentity {

    /// 32 位小写 hex，与 Android 侧长度一致。
    static var deviceId: String {
        String(sha256Hex(identityMaterial).prefix(32))
    }

    /// 用于诊断与上报的原始材料（不含敏感字段）。
    static var identityMaterial: String {
        "iOS|\(vendorIdentifier)|\(hardwareModel)"
    }

    /// `identifierForVendor`。卸载同厂商全部 App 后会变化，故调用方应缓存；
    /// 服务端设备注册一旦建立，不应因 IDFV 漂移而失效 —— 这是服务端需要处理的点。
    static var vendorIdentifier: String {
        UIDevice.current.identifierForVendor?.uuidString ?? "unknown-vendor"
    }

    /// 硬件型号标识（如 "iPhone16,2"），等价 Android 的 Build.MODEL 角色。
    /// `UIDevice.current.model` 只会给 "iPhone"，因此走 `uname`。
    static var hardwareModel: String {
        var info = utsname()
        guard uname(&info) == 0 else { return "unknown-model" }
        let mirror = Mirror(reflecting: info.machine)
        let model = mirror.children.reduce(into: "") { partial, element in
            guard let value = element.value as? Int8, value != 0 else { return }
            partial.append(Character(UnicodeScalar(UInt8(bitPattern: value))))
        }
        return model.isEmpty ? "unknown-model" : model
    }

    /// 系统版本串，供上报。
    static var osVersion: String {
        "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion)"
    }

    // MARK: - 内部

    static func sha256Hex(_ string: String) -> String {
        sha256Hex(Data(string.utf8))
    }

    static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
