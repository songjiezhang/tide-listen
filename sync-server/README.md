# 汐听同步服务

同步服务和网页管理使用同一个 HTTPS 地址。账号、设备、歌单、音源和配对码保存在 Docker 数据卷中。

## Docker 部署

### 下载哪些文件

NAS 或 VPS 可以**只下载同步服务**。v0.13.0 的服务端文件在 GitHub Release 的附件里。

| 文件 | 用途 |
| --- | --- |
| [独立部署包 `TideListen-Sync-Deploy-0.13.0.tar.gz`](https://github.com/songjiezhang/tide-listen/releases/download/v0.13.0/TideListen-Sync-Deploy-0.13.0.tar.gz) | 约 31 KB，包含同步服务、管理网页、Dockerfile、Compose 配置、测试和许可证。自行构建镜像时只需这个包。 |
| [预构建镜像 `linux-amd64`](https://github.com/songjiezhang/tide-listen/releases/download/v0.13.0/TideListen-Sync-linux-amd64.tar.gz) · [预构建镜像 `linux-arm64`](https://github.com/songjiezhang/tide-listen/releases/download/v0.13.0/TideListen-Sync-linux-arm64.tar.gz) | `x86_64` 选 amd64，`aarch64` 选 arm64。用 `docker load` 导入。用 `docker run` 时只下载镜像即可，用 Compose 时再下载独立部署包取配置。 |

GitHub 自动生成的 `Source code (zip)` 和 `Source code (tar.gz)` 是整仓归档，包含桌面和 Android 客户端源码。部署同步服务请选上表的附件。

### 用 Docker Compose 部署

解压独立部署包，进入包含 `compose.yaml` 的目录。

```bash
tar -xzf TideListen-Sync-Deploy-0.13.0.tar.gz
cd TideListen-Sync-0.13.0/sync-server
```

在该目录创建 `.env`，填写访问管理网页的域名和宿主机映射端口。

```dotenv
SYNC_DOMAIN=sync.example.com
SYNC_LOCAL_PORT=18472
```

- `SYNC_DOMAIN` 填浏览器实际访问的域名，例如 `sync.example.com`。Compose 将它拼成 `SYNC_PUBLIC_URL=https://sync.example.com`，服务用这个地址校验 `/admin` 的登录等写入请求来源。使用群晖或其他现有反向代理时，由代理处理证书和 HTTPS。
- `SYNC_LOCAL_PORT` 填宿主机上空闲的端口。示例把 `127.0.0.1:18472` 映射到容器的 `8472`，因此同一宿主机上的反向代理应转发到 `http://127.0.0.1:18472`。省略此项时默认映射到宿主机的 `8472`。

镜像准备方式二选一。自行构建时运行 `docker compose build`。用预构建镜像时，导入与你的设备匹配的文件。下面以 amd64 为例，文件路径按实际下载位置填写。导入后的镜像名为 `tidelisten-sync:local`。

```bash
docker load -i TideListen-Sync-linux-amd64.tar.gz
```

仅在首次部署时创建管理员。在 Bash 中执行以下命令，密码通过标准输入传给容器。

```bash
read -rs -p '初始管理密码（至少 12 字符）: ' TIDE_INITIAL_PASSWORD
printf '\n'
printf '%s\n' "$TIDE_INITIAL_PASSWORD" | docker compose run --rm -T sync init admin
unset TIDE_INITIAL_PASSWORD
```

群晖已有 HTTPS 反向代理时，将域名解析到群晖的公网入口，再把代理目标设为上面配置的宿主机端口。群晖 Container Manager 也可按 `compose.yaml` 创建项目，初始化账号时使用 NAS 终端。反向代理应正确附加客户端地址。

```bash
docker compose up -d --no-build
```

VPS 可使用部署包自带的 Caddy 配置。Caddy 同样使用 `SYNC_DOMAIN`，在 Docker 网络里直接转发到 `sync:8472`，宿主机映射端口只供宿主机上的代理或本机访问使用。域名解析生效且公网 80、443 端口可达后启动。

```bash
docker compose -f compose.yaml -f compose.https.yaml up -d --no-build
```

### 用 docker run 部署

使用预构建镜像时，先执行上面的 `docker load`。自己构建时，在独立部署包的 `sync-server/` 目录执行 `docker build -t tidelisten-sync:local .`。首次部署创建管理员，然后用同一对数据卷和密钥卷启动容器。把 `SYNC_PUBLIC_URL` 换成浏览器访问的完整 HTTPS 地址，用 `-p` 指定宿主机映射端口。

```bash
read -rs -p '初始管理密码（至少 12 字符）: ' TIDE_INITIAL_PASSWORD
printf '\n'
printf '%s\n' "$TIDE_INITIAL_PASSWORD" | docker run --rm -i \
  -v tidelisten-sync-data:/data \
  -v tidelisten-sync-secrets:/secrets \
  tidelisten-sync:local init admin
unset TIDE_INITIAL_PASSWORD
```

```bash
docker run -d --name tidelisten-sync --restart unless-stopped \
  -p 127.0.0.1:18472:8472 \
  -e SYNC_PUBLIC_URL=https://sync.example.com \
  -e SYNC_TRUST_PROXY=1 \
  -v tidelisten-sync-data:/data \
  -v tidelisten-sync-secrets:/secrets \
  tidelisten-sync:local
```

按上面的端口示例，将 HTTPS 反向代理的目标设为 `http://127.0.0.1:18472`。已有同步实例在升级或切换启动方式时，沿用原数据卷和密钥卷，直接启动服务。

## 网页与设备

1. 打开 `https://sync.example.com/admin`，使用初始化的管理员账号和密码登录。
2. 在「账号设置」创建用户，在「配对与设备」为该用户生成配对码。默认 10 分钟有效。生成时可以单独选有效期，管理员也可以更新全局默认值。
3. 桌面打开「设置 → 数据同步」，手机和车机打开「设置 → 同步」。在「汐听同步服务」填写 `https://sync.example.com` 和配对码。每台设备使用新生成的配对码。配对后可在网页撤销设备。
4. 桌面可合并现有本机歌单，或选择「从 WebDAV 迁移」。迁移先同步配置中的 WebDAV 歌单，再预览列表和曲目数量。确认后客户端写本机 JSON 备份，将歌单合并到配对用户的库，并切换到新同步方式。等待其他已连接 WebDAV 的设备上传未同步的改动，再配对至新服务。
5. 歌单同步自动运行。Android 在前台轮询，离线修改会保留并于下次联机上传。桌面和移动端都可手动同步。
6. 音源同步默认关闭。开启后，如果服务器音源库为空，客户端会上传本机洛雪 JS 音源。如果服务器已有变更，客户端会提示审核；确认后用服务器列表更新本机。网页支持上传脚本、设置更新链接、启停及排序。桌面端通过已启用的 LX API Source Loader 扩展使用洛雪音源。手机或车机的音源设置可回到「音源」编辑并同步。

同步服务管理歌单记录和选中的音源。音频和播放进度保存在原设备。

## 备份与复原

网页「备份与恢复」可下载或恢复当前账号的歌单 JSON，文件也可导入桌面端。首次迁移或准备覆盖前，可以先下载一份。

需要迁移整个同步服务时，运行以下命令使用 SQLite 在线备份：

```bash
docker compose exec sync node /app/server.mjs backup /data/backup.sqlite
docker compose cp sync:/data/backup.sqlite ./backup.sqlite
docker compose cp sync:/secrets/sources.key ./sources.key
```

分别保管数据库和密钥。密钥可解密数据库里的音源脚本和更新链接。还原时先停止服务，恢复两个数据卷内的数据库和密钥，移除旧数据库对应的 `sync.sqlite-wal`、`sync.sqlite-shm`，再启动。确认恢复的是同一套数据库和密钥。
