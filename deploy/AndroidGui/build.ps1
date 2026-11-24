# Build AlasConsole.apk without gradle.
# Requires: JDK 11, Android build-tools 34, platform android-34 android.jar
$ErrorActionPreference = 'Continue'

$root   = "E:\Users\LIANG\Downloads\AlasApp_0.4.13_fullcn\AzurLaneAutoScript\deploy\AndroidGui"
$sdk    = "C:\Users\LIANG\AppData\Local\Temp\alas-apk"
$bt     = "$sdk\bt\android-14"
$ajar   = "$sdk\plat\android-34\android.jar"
$build  = "$root\build"
$outApk = "$root\AlasConsole.apk"

# JDK 必须显式指定：d8.bat 是个批处理，内部用 %JAVA_HOME%\bin\java 起 JVM，
# 只要 JAVA_HOME 空就直接 "JAVA_HOME is not set" 退出（实测踩过：javac 能编，
# d8 挂了，因为 javac.exe 自己找得到 JVM 而 d8.bat 找不到）。
$jdk = "C:\Program Files\Eclipse Adoptium\jdk-11.0.29.7-hotspot"
if (-not (Test-Path -LiteralPath "$jdk\bin\javac.exe")) { throw "missing JDK: $jdk" }
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;" + $env:PATH
$javac = "$jdk\bin\javac.exe"

# 侧滑抽屉用的是官方 androidx.drawerlayout（自带 ViewDragHelper 手势处理），
# 这套工程没有 gradle，所以直接下 AAR、解出 classes.jar 手动接进来：
#   drawerlayout 1.1.1 / customview 1.1.0 / core 1.12.0 / annotation 1.7.1
#   + 补齐的 collection 系依赖（lifecycle-common/runtime、versionedparcelable 等）
# 注意：只拿 classes.jar 不带库自己的 R 类，DrawerLayout 一构造就会
# NoClassDefFoundError: androidx.drawerlayout.R$attr（真机 logcat 实测）。
# AAR 的 res 里其实只有 3 个符号（attr.drawerLayoutStyle / attr.elevation /
# dimen.def_drawer_elevation），而且都只需要「是个合法 int」，取不到值也不影响
# ——我们显式 setScrimColor / setDrawerElevation 都设过了。所以不编 AAR 的 res，
# 改为在 src/androidx/drawerlayout/R.java 里补一个占位 R 类，改动最小。
#
# jar 放在项目内的 libs/：原来放在 %TEMP% 下，临时目录一清就编不了了。
# libs/aar 里留着 AAR 原件，方便以后核对版本。
$lib = "$root\libs"
if (-not (Test-Path -LiteralPath $lib)) { $lib = "$sdk\androidx-lib" }
$androidxJars = @()
if (Test-Path -LiteralPath $lib) {
    $androidxJars = (Get-ChildItem -LiteralPath $lib -Filter *.jar).FullName
}
$javaCp = @($ajar) + $androidxJars -join ";"

foreach ($f in @("$bt\aapt2.exe", "$bt\d8.bat", "$bt\apksigner.bat", "$bt\zipalign.exe", $ajar)) {
    if (-not (Test-Path -LiteralPath $f)) { throw "missing tool: $f" }
}

Remove-Item -LiteralPath $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$build\classes" | Out-Null
New-Item -ItemType Directory -Force -Path "$build\dex" | Out-Null

Write-Output "[1/6] aapt2 compile res"
& "$bt\aapt2.exe" compile --dir "$root\res" -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

# 依赖库的 res 也必须编进来。
# DrawerLayout 构造时会 getDimensionPixelSize(R.dimen.def_drawer_elevation)，
# 这个 id 只有让 aapt2 真的分配、且资源真的躺在 APK 里才取得到；
# 自己编个常量顶替会直接 Resources$NotFoundException（实测 id 0x7f0f3019 崩过一次）。
Write-Output "[1b] aapt2 compile 依赖 res"
$depResArgs = @()
$depRes = "$sdk\aar-res"
if (Test-Path -LiteralPath $depRes) {
    Get-ChildItem -LiteralPath $depRes -Directory | ForEach-Object {
        $z = "$build\res_" + $_.Name + ".zip"
        & "$bt\aapt2.exe" compile --dir $_.FullName -o $z
        if ($LASTEXITCODE -ne 0) { throw ("aapt2 compile dep res failed: " + $_.Name) }
        $depResArgs += $z
    }
}

Write-Output "[2/6] aapt2 link"
$linkArgs = @("link", "-o", "$build\base.apk", "-I", $ajar,
              "--manifest", "$root\AndroidManifest.xml",
              "$build\res.zip") + $depResArgs + @("--output-text-symbols", "$build\R.txt")
& "$bt\aapt2.exe" @linkArgs
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# 从 aapt2 导出的 R.txt 里取 DrawerLayout 需要的几个符号，生成它自己的 R 类。
# 每次构建现算，id 跟着 aapt2 走，换 DrawerLayout 版本或加资源都不会错位。
Write-Output "[2b] 生成 androidx.drawerlayout.R"
$sym = @{}
foreach ($l in Get-Content -LiteralPath "$build\R.txt") {
    if ($l -match '^int (attr|dimen|color|string|id) ([A-Za-z_0-9]+) (0x[0-9a-fA-F]+)$') {
        $sym[$Matches[2]] = $Matches[3]
    } elseif ($l -match '^int\[\] styleable DrawerLayout \{ (.*) \}$') {
        $sym['_dlArray'] = $Matches[1]
    } elseif ($l -match '^int styleable DrawerLayout_elevation (\d+)$') {
        $sym['_dlIdx'] = $Matches[1]
    }
}
foreach ($need in @('drawerLayoutStyle', 'elevation', 'def_drawer_elevation', '_dlArray', '_dlIdx')) {
    if (-not $sym.ContainsKey($need)) {
        throw ("R.txt 里缺少符号：$need —— 检查 [1b] 是不是没把 aar-res 编进来")
    }
}
$genDir = "$build\gen\androidx\drawerlayout"
New-Item -ItemType Directory -Force -Path $genDir | Out-Null
$genJava = @"
package androidx.drawerlayout;

// 构建时由 build.ps1 从 aapt2 的 R.txt 生成，不要手改。
// 这些是真实分配的资源 id（依赖库 res 已编进 APK），
// 用占位 id 会 Resources\`$NotFoundException。
public final class R {
    private R() { }
    public static final class attr {
        public static final int drawerLayoutStyle = $($sym['drawerLayoutStyle']);
        public static final int elevation = $($sym['elevation']);
        private attr() { }
    }
    public static final class dimen {
        public static final int def_drawer_elevation = $($sym['def_drawer_elevation']);
        private dimen() { }
    }
    public static final class styleable {
        public static final int[] DrawerLayout = { $($sym['_dlArray']) };
        public static final int DrawerLayout_elevation = $($sym['_dlIdx']);
        private styleable() { }
    }
}
"@
[IO.File]::WriteAllText("$genDir\R.java", $genJava, (New-Object Text.UTF8Encoding $false))

Write-Output "[3/6] javac"
# 源码全量扫：MainActivity 之外还有 src/androidx/*/R.java（其余库的占位 R 类）
# 以及 build/gen 下构建时生成的 androidx.drawerlayout.R（真实资源 id）
$srcs = @()
$srcs += (Get-ChildItem -LiteralPath "$root\src" -Filter *.java -Recurse).FullName
$srcs += (Get-ChildItem -LiteralPath "$build\gen" -Filter *.java -Recurse).FullName
& $javac -nowarn -source 8 -target 8 -encoding UTF-8 -cp $javaCp -d "$build\classes" $srcs 2> "$build\javac.err"
if ($LASTEXITCODE -ne 0) {
    Get-Content -LiteralPath "$build\javac.err" | Select-Object -Last 30
    throw "javac failed"
}

Write-Output "[4/6] d8"
$classFiles = (Get-ChildItem -LiteralPath "$build\classes" -Filter *.class -Recurse).FullName
# 不能把这些绝对路径直接铺在命令行里：每个匿名内部类都会多出一个 MainActivity$N.class，
# 数量上来以后总长度会撞上 Windows 的 8191 字符上限，d8 直接报 "The command line is too long"。
# 改成写进参数文件、用 @file 交给 d8 自己展开。
# androidx 的 jar 一并作为输入，让 d8 把它们和自己的类 dex 到一起。
$d8Args = @("--release", "--min-api", "24", "--lib", $ajar, "--output", "$build\dex")
foreach ($c in $classFiles) { $d8Args += $c }
foreach ($j in $androidxJars) { $d8Args += $j }
$argFile = "$build\d8args.txt"
[IO.File]::WriteAllLines($argFile, $d8Args, (New-Object Text.UTF8Encoding $false))
& "$bt\d8.bat" "@$argFile" 2> "$build\d8.err"
if ($LASTEXITCODE -ne 0) {
    Get-Content -LiteralPath "$build\d8.err" | Select-Object -Last 30
    throw "d8 failed"
}

Write-Output "[5/6] inject classes.dex"
$py = @"
import zipfile, shutil, sys
shutil.copyfile(sys.argv[1], sys.argv[3])
with zipfile.ZipFile(sys.argv[3], 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], 'classes.dex')
print('dex injected')
"@
[IO.File]::WriteAllText("$build\merge.py", $py, (New-Object Text.UTF8Encoding $false))
& python "$build\merge.py" "$build\base.apk" "$build\dex\classes.dex" "$build\merged.apk"
if ($LASTEXITCODE -ne 0) { throw "dex merge failed" }

Write-Output "[6/6] zipalign + sign"
& "$bt\zipalign.exe" -f -p 4 "$build\merged.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

# keystore 必须放在 build 目录外面：脚本开头会整个删掉 $build，
# 放里面等于每次重新生成密钥 → 签名变化 → 覆盖安装报
# INSTALL_FAILED_UPDATE_INCOMPATIBLE，只能先卸载（会丢 root 授权）。
$ksFile = "E:\Users\LIANG\Downloads\AlasApp_0.4.13_fullcn\AzurLaneAutoScript\deploy\AndroidGui\debug.keystore"
if (-not (Test-Path -LiteralPath $ksFile)) {
    & keytool -genkeypair -keystore "$ksFile" -alias androiddebugkey `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass android -keypass android `
        -dname "CN=AlasConsole, OU=Dev, O=Alas, L=QZ, ST=FJ, C=CN" 2> "$build\keytool.err"
}

if (Test-Path -LiteralPath $outApk) { Remove-Item -LiteralPath $outApk -Force }
& "$bt\apksigner.bat" sign --ks "$ksFile" --ks-pass pass:android --key-pass pass:android `
    --out $outApk "$build\aligned.apk" 2> "$build\sign.err"
if ($LASTEXITCODE -ne 0) {
    Get-Content -LiteralPath "$build\sign.err" | Select-Object -Last 20
    throw "sign failed"
}

& "$bt\apksigner.bat" verify $outApk 2> "$build\verify.err"
if ($LASTEXITCODE -ne 0) { throw "verify failed" }

$sz = [math]::Round((Get-Item -LiteralPath $outApk).Length / 1KB, 0)
Write-Output "BUILD OK: $outApk ($sz KB)"
