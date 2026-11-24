#!/bin/bash
# =============================================================================
# 步骤 3.5/5：在容器内用 root 预装 uiautomator2 的 ATX 服务（必须做，否则截图/控制全废）
#
# 为什么必须单独做这一步：
#   u2 的 app-uiautomator.apk / app-uiautomator-test.apk 由 u2 自己用 `adb install`
#   装进设备，但在 MIUI/HyperOS 上会被拦：INSTALL_FAILED_USER_RESTRICTED
#   （开发者选项里的"USB 安装"又要插 SIM 卡才让开，此路不通）。
#   绕法：APK 推到 /data/local/tmp，用 root 执行 pm install -r -d。
#
# 装完之后 deploy.yaml 里 InstallUiautomator2 必须是 false，
# 否则 ALAS 启动时还会用 adb install 重装一遍、失败。
#
# 用法（容器内）：
#   bash /root/AzurLaneAutoScript/deploy/Termux/25-install-u2.sh
# =============================================================================
set -uo pipefail

SERIAL="${SERIAL:-127.0.0.1:5555}"
ADB="${ADB:-/usr/bin/adb}"
PY="${PY:-/usr/bin/python3.10}"

echo "==> adb 连本机"
$ADB start-server >/dev/null 2>&1 || true
for i in $(seq 1 8); do
    $ADB connect "$SERIAL" >/dev/null 2>&1
    $ADB devices 2>/dev/null | grep -q "^$SERIAL.*device" && break
    sleep 2
done
$ADB devices

ASSETS=$($PY -c "import uiautomator2, os; print(os.path.join(os.path.dirname(uiautomator2.__file__), 'assets'))")
if [ ! -d "$ASSETS" ]; then
    echo "!!! 找不到 u2 的 assets 目录（uiautomator2 没装好？）" >&2
    exit 1
fi
echo "==> u2 assets: $ASSETS"
ls -l "$ASSETS"/*.apk

echo "==> 推到设备并 root 安装"
for apk in app-uiautomator.apk app-uiautomator-test.apk; do
    $ADB -s "$SERIAL" push "$ASSETS/$apk" "/data/local/tmp/$apk" 2>&1 | tail -1
    $ADB -s "$SERIAL" shell su -c "pm install -r -d /data/local/tmp/$apk" 2>&1 | tail -2
done

echo "==> 确认已安装"
$ADB -s "$SERIAL" shell pm list packages 2>/dev/null | grep -i uiautomator || echo "   !!! 仍未安装"

echo "==> u2 连通性 + 屏幕信息自检"
$PY - <<'PY' 2>&1 | tail -12
import uiautomator2 as u2
d = u2.connect('127.0.0.1:5555')
info = d.info
print('   displayWidth=%s displayHeight=%s rotation=%s sdk=%s'
      % (info.get('displayWidth'), info.get('displayHeight'),
         info.get('displayRotation'), info.get('sdkInt')))
print('   window_size:', d.window_size())
print('   current_app:', d.app_current())
print('   u2 服务就绪')
PY

echo
echo "完成。u2 已可用 —— ALAS 的 uiautomator2 截图和 MaaTouch 控制都依赖它。"
