# Python 备份恢复说明

本目录是 Java 主运行时的参考备份，不是生产 fallback。只有维护者明确决定进行历史对照或灾难恢复时才执行以下步骤。

## 完整性验证

在仓库根目录运行：

```powershell
Get-Content python-backend/SHA256SUMS | ForEach-Object {
  $hash, $path = $_ -split '  ', 2
  $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path 'python-backend' $path)).Hash.ToLowerInvariant()
  if ($actual -ne $hash) { throw "Checksum mismatch: $path" }
}
```

Git 冻结标签 `python-backend-phase10-windows-20260803` 指向一个只包含 `python-backend/` 的精确 tree snapshot。可在临时目录查看或导出，不会覆盖当前工作区：

```powershell
git archive --format=zip --output=python-backend-phase10-windows-20260803.zip python-backend-phase10-windows-20260803
```

## 显式开发对照

```powershell
node python-backend/setup-python.mjs
$env:OPENGIS_BACKEND='python'
npm run dev:electron
```

这只允许在未打包开发环境运行。Windows 安装包会忽略 `OPENGIS_BACKEND=python` 并强制 Java。

## 回到 Java

关闭开发进程，清除当前终端中的临时环境变量，然后重新启动：

```powershell
Remove-Item Env:OPENGIS_BACKEND -ErrorAction SilentlyContinue
npm run dev:electron
```

禁止把 Python 配置成 Java RPC 失败后的自动回退；禁止为了恢复而删除或覆盖用户 workspace。
