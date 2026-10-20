# CP6 PDF导出与字体运行环境适配验收

日期：2026-10-16。裁决：本板块代码、专项回归、独立复审、实际打包JAR与中文视觉核验完成。仅本地可审阅交付，未提交/推送/部署，未重启用户服务。

## 原问题与证据

此前5处Windows字体候选路径本身不等同业务bug；真实问题是两条PDF链在系统中文字体不可用时，无法保证导出携带实际字库。Trip最终STSong-Light明确NOT_EMBEDDED；Analytics即便对STSong请求EMBEDDED，当前OpenPDF 2.0.3的CJK字体也没有字体程序流，进一步回退Helvetica/WinAnsi无法编码中文。新回归构造旧STSong回退PDF，验证所有字体均无FontFile2/3，保留`legacy-font-fallback.pdf`作为结构证据，不声称所有阅读器必然显示乱码。

旧Trip测试只查字节大小、PDF魔数，历史运营测试只保证生成文件；它们不足以验证中文可携带性。

## 最小修复

- 新增公共infrastructure `PdfFontProvider`。默认从classpath字节流加载随应用打包的静态Noto Sans SC，IDENTITY_H编码并嵌入实际使用字形；Trip、Analytics日报与历史报表共享Spring provider。没有引入新的领域SPI或字体热更新框架。
- 删除两条链的OS路径候选、非嵌入STSong和Helvetica回退；移除Trip静态全局字体缓存，改provider实例缓存，避免不同配置实例互相污染。
- 可选`PDF_FONT_FILE`，默认空。显式指定的缺失/损坏/不支持中文字库使导出失败，不静默改用另一字库。读取文件后将字节交给OpenPDF，失败不遗留OS文件句柄。配置变更需重启；yaml、唯一env示例及启动说明同步。
- 完整31036字形的静态weight400中文字体资产约10.6MB，未按当前样例删字。OpenPDF为各PDF嵌入使用字形子集，本轮三份JAR样例约45–74KB。Python/FontTools仅用于可复现构建，不是应用运行依赖，不在运行时下载字体。

字体源来自[官方Google Fonts固定提交](https://github.com/google/fonts/tree/1d0e46c7933245c8180fe83e47917961c913bbdb/ofl/notosanssc)。同包保留[SIL OFL授权原文](https://github.com/google/fonts/blob/1d0e46c7933245c8180fe83e47917961c913bbdb/ofl/notosanssc/OFL.txt)、来源与源/成品哈希manifest；FontTools4.62.1完整实例化wght400并更新名称。`tools/cp6-pdf/build_font.py`实际重建哈希吻合。

## 实际验证

| 检查 | 结果 |
|---|---|
| 最终专项回归 | 42 run /42 pass /0 skip /0 failures/errors；其中新增字体7项 |
| 默认全量package | 1322 run /1280 pass /42 skip /0 failures/errors；跳过不计通过 |
| YAML解析、配置合同 | 通过；PDF_FONT_FILE归OPTIONAL_FEATURE，未读取真实env |
| 实际可执行Boot JAR | PropertiesLauncher从jar:nested资源加载字体，生成Trip/日报/历史3类PDF；未启动Spring、数据库或外部服务 |
| PDF结构及文字 | 3份Type0字体实际FontFile2/3与ToUnicode解压非空；中文提取通过；精准关键文案由JUnit覆盖 |
| 视觉核验 | 最终3份PDF全部页面Poppler渲染并由主线程查看：中文、标点、温度符号、标题和表格可读，未发现裁切或乱码 |
| 全后端静态扫描 | 360个生产Java，0 findings；原5处字体路径命中消失；仍只是既有规则覆盖范围 |
| 独立审查 | 初审、实现复审、实际JAR与校验脚本末审通过；审查智能体未修改源码、未跑Maven |

最终JAR SHA256：`9ce3c6e6cdcd9bde9a5fbb3c015b382640cc9feb251294cd4590ec8f3a08fe7a`。
字体SHA256：`eeb06b8a64fd04a2744d95579db1571b51027cda61ed78c62e4b730791525461`。实际JAR资源与工作树manifest一致。

## 首轮失败与工具边界

第一次专项42项中1项error：OpenPDF直接解析损坏的外部字体后Windows句柄未关，JUnit临时目录删除失败。未关闭清理规则、未忽略断言；改为Files.readAllBytes后解析，并增加失败后立即删除文件断言。原始失败日志与最终42/42日志均保留。

初次配置检查因未传SCENIC_GUIDE_JAVA_DIR而拒绝执行，按既有契约补进程变量后通过；此前命令的PowerShell占位符解析错误发生在Maven启动前，不算测试结果。GitHub API取提交遇到限流，改用官方git ls-remote固定commit、raw URL下载，源/产物SHA已记录。

日报Poppler渲染记录两条`No display font for Symbol/ArialUnicode`警告，进程退出0。实际中文字体程序与Unicode映射已嵌入，页面查看中文可读；没有把它写成零警告，也未删除告警制造通过。

## 完成范围与限制

默认代码路径只读JAR字体资源，不再探测主机字体；本轮没有删除本机系统字体，也没有启动Linux无字体容器。可执行JAR的classpath加载和PDF嵌入证明默认字体不由OS候选决定，但不扩大为Linux部署、所有阅读器或打印机已验收。

显式外部TTF的读入、缺失/损坏与实例隔离已验；TTC索引沿用OpenPDF支持，但本轮未另做外部TTC样本。轻量中/文字符检查不保证任意外部字库完整覆盖所有输入。不保证全部生僻字、emoji、彩色字形或其他语种。未改浏览器下载、鉴权和业务内容，本轮没有重跑浏览器端到端或供应商流程。

证据目录：`docs/refactor/evidence/cp6-pdf-portability-20261016/`，包含机器统计、首轮与最终日志、实际JAR/字体哈希、3份最终PDF及PNG、旧回退结构样例。复现工具位于`tools/cp6-pdf/`。前端/BFF未因本轮修改，不把此前其测试成绩重复算成本轮。
