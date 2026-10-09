#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_own_types.py — 核对业务代码里引用的**自有类型**是否真的存在。

## 为什么需要（第 50 轮的真实事故）
`AgentToolCatalog.install(...)` 的参数类型写成了 `AgentHost`，而该类型不存在
（真实类名 `AgentToolHostImpl`，定义在 `AgentHost.swift` 里 —— **文件名误导了我**）。

13 道关卡全部放过它：语法正确、括号配平、不涉及 SQL/字面量/生成绑定。
「引用不存在的自有类型」是本地检查的结构性盲区。

## 做法
1. 收集全部自有类型声明（struct / class / final class / enum / actor / protocol / typealias）
2. 收集 `TypeName(` 与 `TypeName.member` 形式的引用
3. 报告「引用了但未声明、且不在外部类型白名单」的引用

**白名单是必须的**：我的代码还引用 Swift 标准库 / SwiftUI / GRDB / Foundation /
Security / os / 以及 UniFFI 生成绑定里的类型，那些不在本仓声明。
新增外部依赖时会命中白名单缺失 —— 那时应把它加进白名单，而不是删检查。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import swift_text  # noqa: E402  共享的 Swift 文本处理（本项目在此栽过三次）

REPO_ROOT = Path(__file__).resolve().parents[2]
BUSINESS_DIRS = [
    REPO_ROOT / "ios/YuNian",
    REPO_ROOT / "ios/YuNianTests",
]
GENERATED = REPO_ROOT / "ios/Generated/LianyuAgent.swift"

# 外部类型白名单（本仓之外声明的）。
# 覆盖：Swift 标准库 / SwiftUI / GRDB / Foundation / Security / os / XCTest。
EXTERNAL_ALLOWLIST = {
    # Swift 标准库
    "String", "Int", "Int8", "Int16", "Int32", "Int64", "UInt", "UInt8", "UInt16",
    "UInt32", "UInt64", "Double", "Float", "Bool", "Character", "Array", "Dictionary",
    "Set", "Optional", "Result", "Error", "Never", "Void", "Any", "AnyObject",
    # SwiftUI
    # ⚠️ 第 141 轮：TabView 加入白名单。
    # 三个一级 tab（予念/通讯录/我）用 TabView(.page) 实现，
    # 对应 Android MainNavGraph.kt:436-474 的 HorizontalPager。
    "TabView",
    # SwiftUI 基础视图/图形
    # ⚠️ 第 123 轮：LinearGradient / EmptyView / Gradient.Stop 加入白名单。
    # 玻璃组件的竖直高光渐变要用（GlassSurface.kt:52-58 的
    # 0f→White@0.14、0.35f→White@0.04、1f→White@0）；
    # EmptyView 是 YuNianTopBar 的默认 actions 占位。
    # ⚠️ 同轮：Rectangle —— HomeScreen.kt:164-169 的 1dp 分割线要用。
    "LinearGradient", "Gradient", "EmptyView", "Rectangle",
    # PhotosUI（相册多选）
    # ⚠️ 第 115 轮：PHPicker* 加入白名单。
    # 表情导入需要相册选择器，对应 Android 的 SAF/相册入口
    # （StickerManager.importStickerFile 的 uri 来源）。
    # 选 PHPicker 而非 UIImagePickerController：前者不需相册权限、支持多选。
    # 这道关卡要求新外部依赖显式登记 —— 正是它该有的行为。
    "PHPickerConfiguration", "PHPickerViewController", "PHPickerResult",
    # UIKit（图片数据 → 导入前的本地预览）
    # ⚠️ 第 115 轮：UIImage 加入白名单。
    "UIImage",
    # Foundation（URL 组件解析）
    # ⚠️ 第 89 轮：URLComponents 加入白名单。
    # RequestSigner.path(from:) 原先用了 URL 上并不存在的
    # percentEncodedPath/percentEncodedQuery（CI 报 has no member），
    # 改用 URLComponents。这道关卡随即要求把新外部依赖显式登记 ——
    # 这正是它该有的行为：新依赖不能悄悄进来。
    "URLComponents",
    # ⚠️ 第 178 轮：GlassEffectContainer / Glass 加入白名单。
    # Liquid Glass 的容器与样式类型（iOS 26 SDK）。
    # 不包 GlassEffectContainer 的话，每个 glassEffect 是相互隔离的孤岛，
    # 拿不到"相邻玻璃融合"这个 26 代招牌行为。
    "GlassEffectContainer", "Glass",
    # ⚠️ 第 195 轮：SecRandomCopyBytes 加入白名单。
    # 它是 **Security 框架的 C 函数**，不是 Swift 类型 —— 本关卡按
    # `Xxx(` 的调用形态扫描，会把 C 函数也当成"未声明的类型"。
    # 用途：`BackupCrypto.encrypt` 生成 salt / IV（导出侧）。
    # 该框架本就已链接（`KeychainStore` 一直在用），没有新增依赖。
    "SecRandomCopyBytes",
    # ⚠️ 第 199 轮：ShareLink 加入白名单。
    # SwiftUI 的系统分享面板（iOS 16+，本工程部署目标 17.0）。
    # 用途：备份导出后把 .lybk 交给用户存进「文件」或 AirDrop 出去 ——
    # 只写进沙盒临时目录等于没备份（系统会回收）。
    "ShareLink",
    # ⚠️ 第 175 轮：RadialGradient 加入白名单。
    # 群聊头像兜底要用（GroupListItem 的
    # radialGradient(PinkPrimary@0.6 → PinkPrimary@0.3)，HomeScreen.kt:388-391）。
    # 第 123 轮加 LinearGradient 时漏了它 —— 那次只做了玻璃卡的高光渐变。
    "RadialGradient",
    # ⚠️ 第 150 轮：URLRequest / URLSession 加入白名单。
    # ImageGenClient（生图，第 149 轮）要自己发 HTTP —— 之前仓里所有网络
    # 都走 Rust Agent，iOS 侧这是**第一个直连**网络的组件。
    # 关卡要求显式登记新外部依赖：这正是它该有的行为。
    "URLRequest", "URLSession",
    "HTTPURLResponse", "URLResponse",
    "Task", "AsyncStream", "Continuation", "CheckedContinuation", "UUID", "Date",
    "Data", "URL", "FileManager", "Bundle", "UserDefaults", "JSONSerialization",
    "NSLock", "NSNumber", "NSString", "NSNull", "NSArray", "NSDictionary",
    "JSONEncoder", "JSONDecoder", "Encoder", "Decoder", "CodingKey", "Encodable",
    "Decodable", "Sendable", "Equatable", "Hashable", "Identifiable", "ObservableObject",
    "Published", "StateObject", "State", "EnvironmentObject", "Environment",
    "UnsafeMutableRawPointer", "CFError", "SecKey", "OSAllocatedUnfairLock",
    "TimeZone", "Calendar", "Locale", "NotificationCenter", "ProcessInfo",
    "Realm", "CGFloat", "CGSize", "CGRect", "CGPoint", "CGAffineTransform",
    "os", "Logger", "StaticString", "CustomStringConvertible", "LocalizedError",
    "CaseIterable", "RawRepresentable", "Range", "ClosedRange", "IndexSet", "Mirror",
    "unicode", "Unicode", "Scalar", "UTF8", "UTF16", "Encoding", "ComparisonResult",
    "Formatter", "DateFormatter", "ISO8601DateFormatter", "NumberFormatter",
    "AttributedString", "NSAttributedString", "Predicate", "SortDescriptor",
    "UUID", "DispatchQueue", "DispatchGroup", "OperationQueue", "Thread",
    "RunLoop", "Timer", "DateInterval", "Measurement", "Unit", "Dimension",
    "KeyPath", "ReferenceWritableKeyPath", "WritableKeyPath", "PartialKeyPath",
    "AnyKeyPath", "ObjectIdentifier", "Hashable", "Hasher", "StrideThrough",
    "Strideable", "SignedNumeric", "BinaryInteger", "FloatingPoint", "Numeric",
    "AdditiveArithmetic", "Comparable", "Stridable", "Sequence", "Collection",
    "BidirectionalCollection", "RandomAccessCollection", "RangeReplaceableCollection",
    "MutableCollection", "LazySequenceProtocol", "LazyCollectionProtocol",
    "BidirectionalCollection", "DropFirstSequence", "PrefixSequence",
    "FlattenSequence", "JoinedSequence", "Zip2Sequence", "EnumeratedSequence",
    "Repeated", "Repeat", "EmptyCollection", "OptionSet", "SetAlgebra",
    "SIMD", "Endian", "CommandLine", "FileHandle", "Pipe", "InputStream",
    "OutputStream", "BufferedWriter", "BufferedReader", "TextOutputStream",
    "TextInputStream", "Stdout", "Stderr", "Stdin", "Unmanaged", "AutoreleasingUnsafeMutablePointer",
    "UnsafePointer", "UnsafeMutablePointer", "UnsafeBufferPointer", "UnsafeMutableBufferPointer",
    "UnsafeRawPointer", "UnsafeRawBufferPointer", "UnsafeMutableRawBufferPointer",
    "OpaquePointer", "CVaListPointer", "Codable", "KeyedDecodingContainer",
    "KeyedEncodingContainer", "UnkeyedDecodingContainer", "UnkeyedEncodingContainer",
    "SingleValueEncodingContainer", "Decoder", "Encoder", "CodingUserInfoKey",
    # SwiftUI
    "View", "Text", "VStack", "HStack", "ZStack", "LazyVStack", "LazyHStack",
    "ScrollView", "ScrollViewProxy", "List", "Section", "ForEach", "Button",
    "NavigationLink", "NavigationStack", "NavigationBar", "ToolbarItem",
    "Picker", "Label", "Image", "Color", "Shape", "RoundedRectangle", "Capsule",
    "Circle", "Divider", "Spacer", "Form", "Group", "GroupBox", "DisclosureGroup",
    "TextField", "SecureField", "TextEditor", "Toggle", "Slider", "Stepper",
    "DatePicker", "ProgressView", "LabeledContent", "Frame", "Padding",
    "Background", "Overlay", "ClipShape", "CornerRadius", "Shadow", "Opacity",
    "ScaleEffect", "RotationEffect", "Offset", "Font", "Font", "TextStyle",
    "EdgeInsets", "SafeArea", "NavigationPath", "Binding", "State", "Gesture",
    "TapGesture", "LongPressGesture", "DragGesture", "MagnificationGesture",
    "RotationGesture", "AnyGesture", "SimultaneousGesture", "SequentialGesture",
    "GestureMask", "GestureState", "FocusState", "FocusedValue", "Scene",
    "WindowGroup", "DocumentGroup", "Settings", "Commands", "CommandGroup",
    "App", "SceneStorage", "NavigationView", "NavigationBarItem",
    "ToolbarItemPlacement", "NavigationBarItem", "LabelStyle", "ButtonStyle",
    "PrimitiveButtonStyle", "ToggleStyle", "PickerStyle", "TextFieldStyle",
    "ListStyle", "NavigationViewStyle", "ControlSize", "ControlActiveState",
    "HorizontalAlignment", "VerticalAlignment", "Alignment", "Edge", "UnitPoint",
    "GeometryReader", "GeometryProxy", "PreferenceKey", "Anchor", "Bounds",
    "CoordinateSpace", "NamedCoordinateSpace", "Layout", "LayoutProperties",
    "LayoutSubviews", "AnyLayout", "ViewDimensions", "ViewThatFits",
    "ContentUnavailableView", "GridItem", "Grid", "LazyVGrid", "LazyHGrid",
    "Path", "LayoutDirection", "LayoutPriority", "ScaledMetric", "EnvironmentValues",
    "Transaction", "withTransaction", "withAnimation", "Animation", "Transition",
    "AnyTransition", "AsymmetricTransition", "MatchedGeometryEffect",
    "Namespace", "redacted", "redactionReasons", "TextItem", "PreferenceValues",
    "onChange", "onAppear", "onDisappear", "onReceive", "onSubmit", "onTapGesture",
    "onLongPressGesture", "onDrag", "onDrop", "onOpenURL", "task", "alert",
    "confirmationDialog", "sheet", "fullScreenCover", "popover", "contextMenu",
    "swipeActions", "refreshable", "searchable", "navigationTitle",
    "navigationBarTitleDisplayMode", "navigationDestination", "toolbar",
    "keyboardType", "textInputAutocapitalization", "autocorrectionDisabled",
    "textSelection", "disabled", "hidden", "labelsHidden", "id", "tag",
    "equatable", "scaleEffect", "rotationEffect", "offset", "position",
    "frame", "padding", "background", "overlay", "clipShape", "cornerRadius",
    "shadow", "opacity", "blur", "brightness", "saturation", "contrast",
    "grayscale", "hueRotation", "colorInvert", "colorMultiply", "blendMode",
    "compositingGroup", "drawingGroup", "layoutPriority", "fixedSize",
    "layoutPriority", "aspectRatio", "scaledToFill", "scaledToFit",
    "GridRow", "LazyVGrid", "TimelineView", "TimelineSchedule", "EveryMinute",
    "Canvas", "StrokeStyle", "Stroke", "fill", "stroke", "Rotation3D",
    "Angle", "rotation3DEffect", "projectionEffect", "Transform3D",
    # GRDB
    "DatabasePool", "DatabaseQueue", "DatabaseReader", "DatabaseWriter",
    "Database", "Row", "Statement", "StatementArguments", "Configuration",
    "DatabaseValueConvertible", "DatabaseValue", "Value", "Persistable",
    "FetchableRecord", "MutablePersistableRecord", "TableRecord", "EncodableRecord",
    "DecodableRecord", "Record", "DatabaseRegion", "TransactionObservation",
    "SerializedDatabase", "FetchedRecordsController", "FTS4", "FTS5", "FTS4Pattern",
    "FTS5TokenizerDescriptor", "FTS3", "FTS5CustomTokenizer", "FTS5Tokenizer",
    "SpatialResult", "GRDB", "DatabaseArgument", "StatementColumnConvertible",
    # XCTest
    "XCTAssert", "XCTAssertTrue", "XCTAssertFalse", "XCTAssertNil", "XCTAssertNotNil",
    "XCTAssertEqual", "XCTAssertNotEqual", "XCTAssertGreaterThan", "XCTAssertLessThan",
    "XCTAssertThrowsError", "XCTAssertNoThrow", "XCTUnwrap", "XCTFail", "XCTSkip",
    "XCTAttachment", "XCTContext", "XCTestCase", "XCTestExpectation", "XCTWaiter",
    "XCTKeyPathExpectation", "XCTKVOExpectation", "XCTNSNotificationExpectation",
    "XCTAssertIdentical", "XCTAssertNotIdentical", "XCTAssertGreaterThanOrEqual",
    "XCTAssertLessThanOrEqual", "XCTAssertEqualWithAccuracy", "XCTAssertCase",
    # 第三方 / 生成绑定（按需追加）
    "AgentRuntime", "AgentGlobalConfig", "AgentTurnRequest", "AgentTurnResult",
    "PromptOrchestrator", "MemorySelector", "SkillSelector", "StickerPreferenceSelector",
    "StickerPreferenceStore", "MemoryStore", "SkillStore", "ToolDefinition",
    "SkillMenuEntry", "SkillMeta", "ToolCategory", "AgentEvent", "AgentToolHost",
    "StreamSink", "ToolHost", "AgentHostThreading",
}

# 第 52 轮收尾：把首轮 150 处噪点降噪后剩余的 **21 处（17 个唯一名）** 逐一归类。
# 它们全是本仓之外的真实依赖，且所属 API 都是**封闭集合**：
#   · Security 框架的 C 函数 —— Sec* 的签名由系统固定，不会新增
#   · Foundation / CryptoKit / UIKit 的具体类型
# 固化它们之后，任何**新的**未解析类型引用都会暴露 —— 这正是门禁的价值。
#
# ⚠️ 新增外部依赖时会在此处报错。那时做两选一：
#     1. 确实是新外部依赖 → 加进这里（并说明来源）
#     2. 拼写错误 / 引用了不存在的自有类型 → 那是真 bug，修代码
SECURITY_C_API = {
    "SecItemAdd", "SecItemCopyMatching", "SecItemDelete", "SecItemUpdate",
    "SecAccessControlCreateWithFlags", "SecKeyCopyAttributes",
    "SecKeyCreateRandomKey", "SecKeyCreateSignature",
    "SecKeyCopyExternalRepresentation", "SecKeyCopyPublicKey",
}
FOUNDATION_TYPES = {
    "CharacterSet", "URLResourceValues", "UnicodeScalar", "NSRange",
    "NSRegularExpression", "Base64", "DateComponents", "NSError",
    "DispatchSemaphore", "UIApplication", "UIPasteboard",
    # UIKit（BackupImportView 的文档选择器）
    "UIDocumentPickerViewController", "UIViewControllerRepresentable",
    "UIDocumentPickerDelegate", "NSObject", "UTType",
    # UserNotifications 框架（device_notify 用）。
    # ⚠️ 这几个类型名与 API 形态是**凭资料写的、未经编译器验证**的 ——
    #    自有类型门禁只能确认「本仓没声明它们」，无法确认框架 API 是否正确。
    #    首次编译时请重点核对这些调用点。
    "UNUserNotificationCenter", "UNMutableNotificationContent",
    "UNNotificationRequest", "UNNotificationSound",
    # CryptoKit（`BackupCrypto` 的 AES-GCM 与派生密钥）
    "SymmetricKey", "AES", "SealedBox", "Nonce",
    # CommonCrypto（PBKDF2 —— CryptoKit 未暴露）
    "CCKeyDerivationPBKDF2", "CCPBKDFAlgorithm", "CCPseudoRandomAlgorithm",
}
CRYPTOKIT_UIKIT = {"SHA256", "UIDevice", "SHA256Digest"}

EXTERNAL_ALLOWLIST |= SECURITY_C_API | FOUNDATION_TYPES | CRYPTOKIT_UIKIT

# 声明的正则：类型名可能带泛型/where 子句
DECL_PATTERNS = [
    r"^\s*(?:public\s+|private\s+|internal\s+|fileprivate\s+|open\s+|final\s+)*struct\s+(\w+)",
    r"^\s*(?:public\s+|private\s+|internal\s+|fileprivate\s+|open\s+|final\s+|@\w+\s+)*class\s+(\w+)",
    r"^\s*(?:public\s+|private\s+|internal\s+|fileprivate\s+|open\s+|final\s+)*enum\s+(\w+)",
    r"^\s*(?:public\s+|private\s+|internal\s+|fileprivate\s+|final\s+)*actor\s+(\w+)",
    r"^\s*(?:public\s+|private\s+|internal\s+|fileprivate\s+)*protocol\s+(\w+)",
    r"^\s*(?:public\s+|private\s+|internal\s+|fileprivate\s+)*typealias\s+(\w+)",
]


def read(p: Path) -> str:
    if not p.exists():
        sys.exit(f"找不到 {p}")
    return p.read_text(encoding="utf-8")


def collect_declared() -> set[str]:
    declared: set[str] = set()
    files: list[Path] = []
    for d in BUSINESS_DIRS:
        if d.exists():
            files.extend(sorted(d.rglob("*.swift")))
    if GENERATED.exists():
        files.append(GENERATED)

    for path in files:
        for line in read(path).split("\n"):
            for pat in DECL_PATTERNS:
                m = re.match(pat, line)
                if m:
                    declared.add(m.group(1))
                    break
    return declared


SKIP_TYPES = {"Self", "super", "self", "Type", "Protocol"}

# 已知的外部"文件/文件名"或文档引用形态（出现在注释里，剥离后应已消失；
# 这里兜底排除仍在字符串中的，如 SQL 片段与日志文本）
FILE_SUFFIX_RE = re.compile(r"\.(?:kt|swift|md|json|rs|py|sh|yml|plist|webp|png)$", re.I)


def strip_comments_and_strings(src: str) -> str:
    """委托给共享模块 swift_text（本项目在此栽过三次，不再重写）。"""
    return swift_text.strip_comments_and_strings(src)


def main() -> int:
    declared = collect_declared()
    print(f"自有/生成类型声明 {len(declared)} 个")

    problems: list[str] = []
    checked = 0

    for d in BUSINESS_DIRS:
        if not d.exists():
            continue
        for path in sorted(d.rglob("*.swift")):
            src = strip_comments_and_strings(read(path))
            rel = path.relative_to(REPO_ROOT)

            for m in re.finditer(r"\b([A-Z]\w+)\s*\(", src):
                ty = m.group(1)
                if ty in declared or ty in EXTERNAL_ALLOWLIST or ty in SKIP_TYPES:
                    continue
                if FILE_SUFFIX_RE.search(ty):
                    continue
                checked += 1
                problems.append(f"{rel}: 构造 `{ty}(` 的类型未声明且不在白名单")

            for m in re.finditer(r"\b([A-Z]\w+)\.(\w+)", src):
                ty = m.group(1)
                if ty in declared or ty in EXTERNAL_ALLOWLIST or ty in SKIP_TYPES:
                    continue
                if FILE_SUFFIX_RE.search(ty):
                    continue
                checked += 1
                problems.append(f"{rel}: 静态访问 `{ty}.{m.group(2)}` 的类型未声明且不在白名单")

    problems = sorted(set(problems))
    print(f"未解析的类型引用 {len(problems)} 处")
    if problems:
        print("（若是新增的外部依赖，把它加进 EXTERNAL_ALLOWLIST 并说明来源；")
        print(" 若是拼写错误/不存在的自有类型，那是真 bug —— 修代码）")
        for p in problems[:40]:
            print("  -", p)
        if len(problems) > 40:
            print(f"  … 另有 {len(problems) - 40} 处")
    # 门禁：任何未解析引用都失败（白名单已固化，见文件头说明）
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
