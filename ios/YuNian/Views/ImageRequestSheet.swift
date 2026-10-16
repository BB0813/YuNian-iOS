import SwiftUI

/// 「画一张」弹层（第 228 轮，BACKLOG B2）。
///
/// ## 为什么是一个独立弹层，而不是把描述塞进聊天输入框
/// 输入框里写的话会走**对话回合**（模型可能选择不画，或先聊两句）；
/// 这里写的是**直接给生图接口的 prompt** —— 用户点"画一张"要的是立刻出图。
/// 两者的输入语义不同，混在一起会让"为什么它没画"变成玄学。
///
/// ## 失败时要做的事
/// 生图有四个常见失败点（没配生图线路 / 线路没指定模型 / 接口报错 / 没返回图片），
/// 每一个的**下一步动作都不一样**。所以这里把后端给的原因原样展示，
/// 并在页脚写清"该去哪儿配"，而不是统一说一句"生成失败"。
struct ImageRequestSheet: View {

    @ObservedObject var model: ChatViewModel

    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let c = YNTheme.palette(scheme)
        NavigationStack {
            List {
                Section {
                    TextField("画面描述，例如：窗边的小猫，逆光", text: $model.imagePromptDraft, axis: .vertical)
                        .font(.body)
                        .lineLimit(3...8)
                } header: {
                    Text("想画什么")
                } footer: {
                    Text("这句话**直接发给生图接口**，不经过对话模型 —— "
                         + "写得具体一点（主体、动作、光线、风格）比写得长更有用。")
                }

                if let error = model.imageError {
                    Section {
                        Label(error, systemImage: "exclamationmark.triangle.fill")
                            .font(.subheadline)
                            .foregroundStyle(c.warning)
                            .fixedSize(horizontal: false, vertical: true)
                    } header: {
                        Text("没画成")
                    } footer: {
                        Text("去「我 → AI 能力 → 用途线路」把「生图」这条线路指到一个"
                             + "**支持生图的渠道与模型**上（很多渠道只提供对话模型，"
                             + "拿它打生图接口注定失败）。")
                    }
                }

                Section {
                    Button {
                        model.requestImage()
                    } label: {
                        HStack {
                            if model.imageBusy {
                                ProgressView().controlSize(.small)
                            }
                            Text(model.imageBusy ? "正在画…" : "画一张")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(c.accent)
                    .disabled(model.imageBusy
                              || model.imagePromptDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                } footer: {
                    Text("出图会作为一条图片消息进入对话，和它自己配的图存在同一个地方 —— "
                         + "重启 App 也还在。")
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(YNCanvas())
            .navigationTitle("画一张")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
            }
            // 出图成功（消息流里已经出现图片）就自动收起，省一次点击
            .onChange(of: model.imageBusy) { previous, current in
                if previous && !current && model.imageError == nil {
                    dismiss()
                }
            }
        }
    }
}
