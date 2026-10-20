# 城市目录版本化发布

城市目录可通过 `attractions.catalog.external-directory` 指向外置目录热刷新。启用发布版本后，该目录必须是**完整的权威快照**：每个城市一个 JSON 文件，外加固定文件名 `catalog-release.json`。版本化刷新只安装外置快照，不混入 jar 中的城市文件。

## 发布合同

```json
{
  "version": 1,
  "files": {
    "beijing.json": "对应文件原始字节的64位小写SHA-256",
    "hangzhou.json": "对应文件原始字节的64位小写SHA-256"
  }
}
```

`version` 是正整数，`files` 必须精确列出该目录中除 manifest 自身之外的所有城市 `.json` 文件。实际发布时哈希应是工具生成的十六进制值，上例只是格式说明。刷新在数据库安装锁和事务内读取每个文件一次，用这批原始字节先校验 SHA-256，再解析和安装，任一错误均保留旧快照。manifest 被保留为元数据，不作为城市数组解析。

旧目录无 manifest 且数据库 `release_version=0` 时，继续沿用原有“jar 内置资源 + 外置同名覆盖”模式。首次成功安装版本化快照后，数据库禁止再用无 manifest 刷新；更低版本、同版本异文件内容均拒绝。同版同内容幂等；更高版本而目录数据相同，仅更新 `release_version` 和发布文件指纹，不增加数据 `revision`。数据确实变化时，原有 `revision` 增加一次。回滚内容须作为**更高版本的新发布**准备，不重用旧版本号。

## 准备与切换

1. 先停止所有仍可能运行旧版目录刷新代码的实例。旧程序不知道新版本字段，单靠新 Java 代码无法阻止旧程序写库。所有实例升级后再启用版本化目录。
2. 在新的隔离目录中放入要保留的**全部**城市 JSON。不要在仍供运行中实例读取的目录里逐个改文件。文件可以从已经审定的资源复制，并按实际发布内容核验。
3. 为该新目录生成一次 manifest：

   ```powershell
   node D:\scenic-guide\ai\scripts\publish-city-catalog.mjs --directory D:\city-catalog-release-v1 --version 1
   ```

   工具会校验 JSON 数组、单文件城市一致性和跨文件景点 ID 唯一性，按实际字节生成完整清单，并拒绝覆盖已有 manifest。服务端还会执行完整业务字段校验。
4. 将 `attractions.catalog.external-directory` 切换到该完整目录，再执行显式目录 refresh。校验数据库 `attraction_catalog_revision.release_version`、`revision` 和目录条目数量。若刷新失败，保持当前配置和旧快照，修正新目录后以新目录、新版本重发。

本功能只约束支持 manifest 的新程序。它没有加入跨数据库触发器、发布队列，也不会凭静态目录推断景点坐标。外置 JSON 来源字段和 `verifiedFields` 仍须按各城市官方资料逐项审核。

## 验证

- Java SQLite：`CityCatalogReleaseVersionTest` 覆盖权威快照、旧表迁移、同版幂等/篡改、降版、缺失/额外文件、无版回退和数据 revision。
- PostgreSQL 多实例：`Cp6CityCatalogReleaseIntegrationTest` 覆盖独立 JVM 下的版本栅栏、事务原子性与新版同数据。
- 发布工具：`node --test D:\scenic-guide\ai\scripts\publish-city-catalog.test.mjs`。

上述测试均使用隔离数据；没有对真实城市目录或业务数据库执行发布。
