# 汐听同步服务

同步服务和网页管理使用同一个 HTTPS 地址。账号、设备、歌单、音源和配对码保存在 Docker 数据卷中。

## Docker 部署

同步服务的 Docker Hub 镜像是 `zjuzsj/tide-listen-server:2026.11.0002`，覆盖 Linux amd64 和 arm64。桌面、Android 手机和车机从 v0.13.4 起根据版本变更同步。已配对的 v0.13.0 客户端可以继续连接。[Docker Hub 仓库](https://hub.docker.com/r/zjuzsj/tide-listen-server)

服务端镜像标签采用 `YYYY.MM.NNNN`，其中 `NNNN` 是从 `0001` 起算的当月发布序号。`latest` 指向最新发布的服务端镜像。

### 配置 .env

在部署目录准备 `.env`，也可以直接下载 [示例文件](https://raw.githubusercontent.com/songjiezhang/tide-listen/main/sync-server/.env.example) 后改名。在启动前填写自己的初始管理员密码。

```dotenv
SYNC_DOMAIN=sync.example.com
SYNC_LOCAL_PORT=18472
SYNC_ADMIN_USERNAME=admin
SYNC_ADMIN_PASSWORD=
```

- `SYNC_DOMAIN` 填浏览器访问的域名。服务会用对应的 HTTPS 地址校验 `/admin` 请求来源，证书和 HTTPS 由现有反向代理处理。
- `SYNC_LOCAL_PORT` 是 Compose 使用的宿主机端口，默认 `8472`。本例将 `127.0.0.1:18472` 映射到容器的 `8472`。同一宿主机上的反向代理目标填 `http://127.0.0.1:18472`。
- 管理员账号和密码只在首次启动时使用。密码长度为 12 至 128 个字符。以后在 `/admin` 修改密码。已有实例升级时沿用原数据卷和密钥卷。初始化成功后可清空 `.env` 中的初始密码，再重新创建容器。

### 用 Docker Compose 启动

把 [compose.yaml](https://raw.githubusercontent.com/songjiezhang/tide-listen/main/sync-server/compose.yaml) 和 `.env` 放在同一个目录，执行：

```bash
docker compose up -d
```

Compose 会拉取镜像，并在首次启动时创建管理员。群晖 Container Manager 可使用同一份 `compose.yaml`。从旧 Compose 部署升级时，在原目录替换 Compose 配置，并保留原来的项目名与卷。

VPS 也可使用 [Caddy 配置文件](https://raw.githubusercontent.com/songjiezhang/tide-listen/main/sync-server/Caddyfile) 和 [Compose HTTPS 配置](https://raw.githubusercontent.com/songjiezhang/tide-listen/main/sync-server/compose.https.yaml)。待域名解析生效且公网 80、443 端口可达后启动。Caddy 在 Docker 网络里直接转发到 `sync:8472`。

```bash
docker compose -f compose.yaml -f compose.https.yaml up -d
```

### 用 docker run 启动

用同样的 `.env` 变量。其中 `--env-file` 的值直接写在等号后，端口改在 `-p` 里填写。下面以宿主机端口 `18472` 为例。

```bash
docker run -d --name tidelisten-sync --restart unless-stopped \
  --env-file .env -p 127.0.0.1:18472:8472 \
  -e SYNC_TRUST_PROXY=1 \
  -v tidelisten-sync-data:/data \
  -v tidelisten-sync-secrets:/secrets \
  zjuzsj/tide-listen-server:2026.11.0002
```

从已有部署切换启动方式时，把示例卷名换成原来的数据库卷和密钥卷。启动后访问 `https://sync.example.com/admin`，App 配对填写 `https://sync.example.com`。

### 通过 Dockerfile 自行构建

在源码的 `sync-server/` 目录准备好 `.env`，运行：

```bash
docker compose -f compose.yaml -f compose.build.yaml up -d --build
```

v0.13.0 Release 里的独立部署包和预构建镜像使用该版本的手动管理员初始化流程。[v0.13.0 部署说明](https://github.com/songjiezhang/tide-listen/blob/e23b3065dc63a817ec7e1e58bfd9661c3f909f24/sync-server/README.md#docker-部署)

### 从 v0.13.1 升级绑定挂载

如果你的 VPS 使用下面的绑定挂载，请在**原来放置 `compose.yaml` 的目录**操作并保留这两条挂载。

```yaml
    volumes:
      - ./data:/data
      - ./secrets:/secrets
```

只将镜像改为 `zjuzsj/tide-listen-server:2026.11.0002`，再执行：

```bash
docker compose up -d
```

镜像启动时会修正默认 `/data` 和 `/secrets` 的权限，以及数据库、SQLite 辅助文件和 `sources.key` 的归属。随后服务降权到 `1000:1000`，继续使用原数据库、密钥和管理员账号。挂载须允许写入。显式指定非 root 的 `user:` 时，由部署者管理挂载权限。

v0.13.1 的手动权限排查可参考该版本的部署说明。[旧镜像权限排查](https://github.com/songjiezhang/tide-listen/blob/93f6921/sync-server/README.md#密钥目录权限排查)

## 网页与设备

1. 服务根地址会跳转到普通账号登录页 `https://sync.example.com/login`。管理员使用初始化的账号和密码在 `https://sync.example.com/admin` 登录。
2. 在「账号设置」创建用户，在「配对与设备」为该用户生成配对码。默认有效期为 10 分钟。生成时可选择分钟、小时、天、月、年或长期有效，管理员也可修改全局默认值。管理员也可在「账号设置」的用户旁点击「生成配对码」。有效码在「配对与设备」页的「待使用的配对码」中显示，切回该页或刷新后仍可复制。同步服务 0.13.4 之前签发的配对码可继续使用，网页只显示其账号和有效期。选择长期有效的配对码在使用或作废前一直有效，每个码只可配对一次。
3. 桌面打开「设置 → 数据同步」，手机和车机打开「设置 → 同步」。在「汐听同步服务」填写 `https://sync.example.com` 和配对码。每台设备使用新生成的配对码。配对后可在网页撤销设备。
4. 桌面可合并现有本机歌单，或选择「从 WebDAV 迁移」。迁移先同步配置中的 WebDAV 歌单，再预览列表和曲目数量。确认后客户端写本机 JSON 备份，将歌单合并到配对用户的库，并切换到新同步方式。等待其他已连接 WebDAV 的设备上传未同步的改动，再配对至新服务。
5. 汐听客户端在歌单或已启用同步的音源发生改动时上传。桌面和处于前台的 Android 客户端等待服务端版本变化，再下载对应的数据。离线修改会保留并于下次联机上传。桌面和移动端都可手动同步。
6. 音源同步默认关闭。开启后，如果服务器音源库为空，客户端会上传本机洛雪 JS 音源。如果服务器已有变更，客户端会提示审核；确认后用服务器列表更新本机。网页「音源」页显示优先级与启停状态，可直接上移或下移。点击「添加音源」可填写公网更新链接或上传 JS 文件。名称留空时读取脚本自带的名称。点击「编辑」更换文件、修改更新链接或调整启停状态。桌面端通过已启用的 LX API Source Loader 扩展使用洛雪音源。手机或车机的音源设置可回到「音源」编辑并同步。

同步服务管理歌单记录和选中的音源。音频和播放进度保存在原设备。

## 备份与复原

网页「备份与恢复」可下载或恢复当前账号的歌单 JSON，文件也可导入桌面端。首次迁移或准备覆盖前，可以先下载一份。

需要迁移整个同步服务时，运行以下命令使用 SQLite 在线备份：

```bash
docker compose exec --user node sync node /app/server.mjs backup /data/backup.sqlite
docker compose cp sync:/data/backup.sqlite ./backup.sqlite
docker compose cp sync:/secrets/sources.key ./sources.key
```

分别保管数据库和密钥。密钥可解密数据库里的音源脚本和更新链接。还原时先停止服务，恢复两个数据卷内的数据库和密钥，移除旧数据库对应的 `sync.sqlite-wal`、`sync.sqlite-shm`，再启动。确认恢复的是同一套数据库和密钥。
