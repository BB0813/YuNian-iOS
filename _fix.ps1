param()
$ErrorActionPreference = "Stop"
Set-Location 'C:\Users\hbq30\AppData\Local\Temp\yn-ios-probe'

$py = "C:\Users\hbq30\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
$env:PYTHONIOENCODING = "utf-8"

# 1) YAML 有效性 + 确认 runs-on 已改
@'
import pathlib, sys
sys.path.insert(0, r"C:\Users\hbq30\AppData\Local\Temp\pylibs")
import yaml
d = yaml.safe_load(pathlib.Path(".github/workflows/ios-agent.yml").read_text(encoding="utf-8"))
print("jobs:", list(d["jobs"].keys()))
for name, job in d["jobs"].items():
    print(f"  {name}: runs-on={job.get('runs-on')}")
'@ | Set-Content -Path _y4.py -Encoding UTF8
& $py _y4.py
Remove-Item _y4.py -Force

# 2) 关卡自洽
& $py ios\Tools\verify_ci_workflow.py
"workflow 自洽 exit=$LASTEXITCODE"

& $py ios\Tools\verify_readme_accounting.py --fix
& $py ios\Tools\run_all_gates.py *> $null
"gates exit=$LASTEXITCODE"

# 3) 提交推送
$msg = @'
fix: app-device 换到 macos-15 —— 我新建 job 时又踩了第 71 轮的坑

真机 IPA job 挂在 archive：

  xcodebuild: error: Unable to read project 'YuNian.xcodeproj'.
  Reason: ...it is in a future Xcode project file format (70).

XcodeGen 2.44 生成 format 70，而 macos-14 自带 Xcode 15.4 读不了。
app-simulator 在第 71 轮就已因此换成 macos-15，我这次新建 job
又写回 macos-14 —— 同一个坑踩第二次。

顺带从日志确认：xcframework 两个切片都在
（ios-arm64 / ios-arm64-simulator），设备片架构正确，
所以只差 Xcode 版本这一项。
'@
$msg | Out-File -FilePath "$env:TEMP\cm.txt" -Encoding UTF8
git add -A
git -c user.name="Binbim_ProMax" -c user.email="binbim_promax@163.com" commit -F "$env:TEMP\cm.txt" 2>&1 | Select-Object -Last 2
git push origin HEAD:main 2>&1 | Select-Object -Last 2
"push exit=$LASTEXITCODE"
