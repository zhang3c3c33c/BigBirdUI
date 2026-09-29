# BBUI / BigBird 品牌资源

定稿方向：大鸟抱小手机，无尾轮廓，翅膀用轻弧线表示。以用户确认的概念稿重绘为矢量；所有应用位置共用同一份几何源。

## 资源

- `bbui-mark.svg`：标准深绿标志，透明背景。
- `bbui-mark-light.svg`、`bbui-mark-mono.svg`：反白、黑色单色版；留白是真正的透明孔洞。
- `bbui-mark-small.svg`：小尺寸版，适度加宽翅膀留白和听筒，用于聊天头像与通知。
- `bbui-wordmark.svg`：全路径字标，无字体依赖。
- `bbui-lockup.svg`：横向图文组合。
- `bbui-app-icon.svg`、`bbui-avatar.svg`：桌面与圆形头像导出版。
- `png/`：透明标志、应用图标及头像，各提供 1024、512、192、48 px。
- Windows 应用图标另提供 16、24、32、64、128、256 px；小于 48 px 使用小尺寸版几何。桌面构建将这些 PNG 合并为多尺寸 ICO，供程序、窗口和任务栏使用。
- `preview.png`：整套预览。
- `bbui-brand-kit.zip`：可直接取用的 SVG、PNG 和本说明。

## 配色与使用

主色 `#2D513E`，反白 `#F7F6EF`，头像底色 `#D6DFCB`。保持宽高比例，不拉伸、不添加尾巴，不将翅膀留白重新填成大月牙。图标本体不加描边、投影或渐变。

标准标志适合 48 px 以上；更小尺寸使用 small 版。16 px 仅保证轮廓辨识，不承诺保留所有内部细节。24–32 px 的头像和通知使用小尺寸版。标志四周至少保留约标志宽度 1/8 的净空。

Android 桌面图标使用独立背景和矢量前景，前景保留系统自适应裁切余量；Android 13+ 提供 monochrome 层。PNG 应用图标是完整展示图，不作为自适应前景直接使用。通知使用透明底白色轮廓，不使用带底色的桌面图标。

## 维护

统一源文件：`design/brand/generate.mjs`。运行 `node design/brand/generate.mjs` 会生成品牌导出文件、React 标志组件和 Android drawable；脚本依赖项目已有 Playwright 和本机 Chrome。修改源后重新运行，再构建聊天前端与 APK。

`npm run desktop:build` 会自动运行同一生成器；桌面聊天使用共享 React 标志，手机栏和设置页使用共享 UI 配色变量。打包会把图标写入 Windows 可执行文件，无需单独维护一套桌面标志。

接入位置：聊天空白页、助手头像、思考标记、原生初始设置页、前台服务通知、桌面图标、网页 favicon。品牌更新不改变任务、权限、配置及会话逻辑。
