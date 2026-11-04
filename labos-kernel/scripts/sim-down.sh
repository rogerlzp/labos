#!/bin/bash
# 停掉所有设备模拟器，并把设备从模拟驱动上摘下来
set -uo pipefail
cd "$(dirname "$0")/.."

# 两种入口都要停：无界面版 + 桌面窗口版
KILLED=0
pkill -f "com.labos.kernel.sim.DeviceSimulator" 2>/dev/null && KILLED=1
pkill -f "com.labos.kernel.sim.SimulatorApp" 2>/dev/null && KILLED=1
if [[ $KILLED -eq 1 ]]; then echo "已停止所有模拟器"; else echo "没有运行中的模拟器"; fi

if [[ -n "${LABOS_DB_HOST:-}" ]]; then
    PSQL=$(command -v psql || true)
    [[ -z "$PSQL" && -x /opt/homebrew/opt/libpq/bin/psql ]] && PSQL=/opt/homebrew/opt/libpq/bin/psql
    PGPASSWORD="${LABOS_DB_PASS:-}" "$PSQL" -h "$LABOS_DB_HOST" -U "${LABOS_DB_USER:-}" \
        -d "${LABOS_DB_NAME:-}" -q -c "
        UPDATE device_instance SET driver_id = NULL
        WHERE driver_id IN (SELECT id FROM driver WHERE code LIKE 'DRV-SIM-%');
        UPDATE driver SET status = 0 WHERE code LIKE 'DRV-SIM-%';
    " && echo "已把设备从模拟驱动上摘下"
fi
