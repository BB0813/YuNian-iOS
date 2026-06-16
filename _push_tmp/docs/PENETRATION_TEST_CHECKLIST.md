# LianYu 渗透测试清单

> 对应分支：`security/vmp-encryption`
> 执行前提：APK 编译完成，安装到测试设备

---

## 一、Frida 注入攻击

| # | 测试项 | 操作 | 预期结果 | 状态 |
|---|--------|------|---------|------|
| 1.1 | `frida -U -l script.js com.lianyu.ai` | 尝试 Frida attach 到进程 | ptrace 自附加 + 端口 27042 检测 → BREACH → 密钥自毁 | [ ] |
| 1.2 | `frida -U -f com.lianyu.ai` | spawn 模式注入 | maps 中 gum-js/frida-agent 检测 → BREACH | [ ] |
| 1.3 | frida-gadget 注入 | 手动注入 libfrida-gadget.so | /proc/self/maps 扫描拦截 → BREACH | [ ] |
| 1.4 | frida-server 重命名 | 将 frida-server 重命名为 innocuous | 端口扫描仍命中 (27042) | [ ] |

## 二、Xposed / LSPosed 攻击

| # | 测试项 | 操作 | 预期结果 | 状态 |
|---|--------|------|---------|------|
| 2.1 | LSPosed hook SecurityGuard.isSafe() | 编写 LSPosed 模块 hook isSafe 返回 true | ClassLoader 检测 → DETECT_XPOSED → BREACH | [ ] |
| 2.2 | Xposed hook NativeBridge.wbAesEncrypt() | 拦截加密函数导出密钥 | maps 扫描 xposed/lsposed → BREACH | [ ] |
| 2.3 | EdXposed / Dreamland 检测 | 安装不同 Hook 框架 | /data/data 路径扫描全覆盖 | [ ] |

## 三、内存攻击

| # | 测试项 | 操作 | 预期结果 | 状态 |
|---|--------|------|---------|------|
| 3.1 | GameGuardian 内存搜索 | 搜索加密前后的数据 | 密钥在 NEON 不在 RAM → 搜索无结果 | [ ] |
| 3.2 | `/proc/self/mem` 读取 | `dd if=/proc/<pid>/mem` | 保护页 mprotect(PROT_NONE) → SIGSEGV | [ ] |
| 3.3 | `ptrace` 内存 dump | gdb attach 后 dump 内存 | ptrace 自附加抢先占坑 → attach 失败 | [ ] |
| 3.4 | Kernel 级内存 dump (LiME) | root 设备上 dump 全内存 | 密钥 μs 级生命周期 + NEON 驻留 → dump 中无明文 | [ ] |

## 四、Root 环境检测

| # | 测试项 | 操作 | 预期结果 | 状态 |
|---|--------|------|---------|------|
| 4.1 | Magisk (Zygisk) | 安装 Magisk + 隐藏 | 多路径 su + Magisk mount 检测 → DETECT_MAGISK | [ ] |
| 4.2 | KernelSU | 安装 KernelSU | /dev/ksu + /proc/self/attr 检测 → DETECT_KERNELSU | [ ] |
| 4.3 | APatch | 安装 APatch | 检测链覆盖 | [ ] |
| 4.4 | Root 后尝试解密数据库 | 拷贝 DB 文件到 PC 分析 | DB key 绑定 TEE → 无法解密 | [ ] |

## 五、MITM / 网络攻击

| # | 测试项 | 操作 | 预期结果 | 状态 |
|---|--------|------|---------|------|
| 5.1 | Charles Proxy 安装用户 CA | 安装 MITM 代理证书 | CA 证书目录扫描 → 检测 | [ ] |
| 5.2 | HTTP 代理设置 (8080/8888) | 系统代理指向 Burp | 代理端口/进程扫描 → 检测 | [ ] |
| 5.3 | VPN MITM (Packet Capture) | 安装抓包 VPN | /dev/tun + 代理进程检测 | [ ] |
| 5.4 | DNS 劫持 | 修改 /etc/hosts | DNS over HTTPS 异常检测 | [ ] |

## 六、模拟器 / 虚拟环境

| # | 测试项 | 操作 | 预期结果 | 状态 |
|---|--------|------|---------|------|
| 6.1 | Android Emulator | 官方模拟器运行 | QEMU 管道/goldfish 电池 → 检测 | [ ] |
| 6.2 | VirtualApp / Parallel Space | 双开环境 | 虚拟环境特征检测 | [ ] |
| 6.3 | VMOS / VPhoneGaga | 虚拟机 App | 传感器缺失 + 多进程异常 → 检测 | [ ] |

## 七、代码完整性

| # | 测试项 | 操作 | 预期结果 | 状态 |
|---|--------|------|---------|------|
| 7.1 | APK 重打包签名 | 修改 classes.dex 后重新签名 | APK 签名 v1+v2+v3 验证 → SIGNATURE_FAIL | [ ] |
| 7.2 | .so 文件替换 | 替换 liblianyu_security.so | .so CRC32 校验 → BREACH | [ ] |
| 7.3 | DEX 热修复注入 | 通过 Tinker/Sophix 注入代码 | DEX CRC32 校验 | [ ] |

## 八、审计日志攻击

| # | 测试项 | 操作 | 预期结果 | 状态 |
|---|--------|------|---------|------|
| 8.1 | 删除 audit.log | 手动删除审计日志 | 链头在 chain.dat → 检测到断链 → AUDIT_CHAIN_BREACH | [ ] |
| 8.2 | 篡改审计条目 | 修改一条日志的 hash | verifyAuditChain 全链校验 → BREACH | [ ] |
| 8.3 | 回滚 chain.dat | 替换为旧版本 | 条目数不连续 → 链断裂 | [ ] |
| 8.4 | 绕过 KMS 直接加密 | 不经过 SecurityOrchestrator 直接调用 KmsProvider | 缺少检测链调用 → AuditLogger 记录缺失 | [ ] |

---

## 测试执行记录

| 日期 | 测试人 | 通过项 | 失败项 | 备注 |
|------|--------|--------|--------|------|
|      |        |        |        |      |
