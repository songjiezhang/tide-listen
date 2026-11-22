# 汐听同步服务

同步服务和网页管理使用同一个 HTTPS 地址。数据库与音源密钥分别保存在两个持久化挂载中。v0.13.8 客户端保存的在线来源、歌单顺序和曲序沿用现有协议，已有 2026.11.0002 服务可直接接入。

## Docker 部署

同步服务的 Docker Hub 镜像是 `zjuzsj/tide-listen-server:latest`，覆盖 Linux amd64 和 arm64。从 v0.13.4 起，客户端会在服务端歌单或已启用同步的音源发生变更后拉取相应数据。已配对的 v0.13.0 客户端可以继续连接。[Docker Hub 仓库](https://hub.docker.com/r/zjuzsj/tide-listen-server)

首次部署可以直接复制下方模板。更新已有实例请跳到[检查部署和升级](#检查部署和升级)。

服务端镜像标签采用 `YYYY.MM.NNNN`，其中 `NNNN` 是从 `0001` 起算的当月发布序号。`latest` 指向最新发布的服务端镜像。

### 用 Docker Compose 启动

首次部署时，在装有 Docker Compose 的 NAS 或 VPS 上新建一个目录，把下面两段配置分别保存为 `compose.yaml` 和 `.env`。启动时会从 Docker Hub 拉取镜像。

**`compose.yaml`**

```yaml
services:
  tide-sync:
    container_name: tide-listen-server
    image: zjuzsj/tide-listen-server:latest
    environment:
      SYNC_PUBLIC_URL: "https://${SYNC_DOMAIN:?请设置 SYNC_DOMAIN}"
      SYNC_TRUST_PROXY: "1"
      SYNC_ADMIN_USERNAME: "${SYNC_ADMIN_USERNAME:-admin}"
      SYNC_ADMIN_PASSWORD: "${SYNC_ADMIN_PASSWORD:-}"
    ports:
      - "${SYNC_LOCAL_PORT:-8472}:8472"
    volumes:
      - data:/data
      - secrets:/secrets
    restart: unless-stopped
    security_opt: [no-new-privileges:true]
    healthcheck:
      test: ["CMD", "node", "-e", "fetch('http://127.0.0.1:8472/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"]
      interval: 30s
      timeout: 4s
      retries: 3
volumes:
  data:
  secrets:
```

**`.env`**

```dotenv
SYNC_DOMAIN=sync.example.com
SYNC_LOCAL_PORT=8472
SYNC_ADMIN_USERNAME=admin
SYNC_ADMIN_PASSWORD=
```

首次启动前，填写以下两项配置。

- `SYNC_DOMAIN` 填对外访问的域名，例如 `sync.example.com`，省略协议和路径。服务用它识别网页登录地址，HTTPS 由反向代理提供。
- `SYNC_ADMIN_PASSWORD` 填 12 至 128 个字符的初始密码。初始管理员默认为 `admin`，可以通过 `SYNC_ADMIN_USERNAME` 修改。已有数据库继续使用原来的账号和密码。

默认在宿主机的所有网络接口上发布 HTTP 端口 `8472`。公网访问走 HTTPS 反向代理，宿主机端口只需对代理开放。如果端口已被占用，修改 `SYNC_LOCAL_PORT` 即可。

在这两个文件所在的目录运行：

```bash
chmod 600 .env
docker compose up -d
```

将域名解析到反向代理。代理运行在宿主机上时，将该域名的 HTTPS 请求转发到 `http://127.0.0.1:8472`。如果改过 `SYNC_LOCAL_PORT`，转发目标也要使用新端口。确认 HTTPS 可访问后，按[网页与设备](#网页与设备)中的步骤登录和配对。

如需单独获取配置文件，可使用 [compose.yaml](compose.yaml) 和 [.env.example](.env.example)。初始化后可在管理页修改密码，再清空 `.env` 中的初始密码并重新创建容器。

### 使用随附的 Caddy

如果希望由 Caddy 提供 HTTPS，将 [Caddyfile](Caddyfile) 和 [compose.https.yaml](compose.https.yaml) 保存到 `compose.yaml` 所在目录。将域名解析到 VPS，确保公网可访问 80 和 443 端口，使用下面的启动命令。Caddy 通过 Compose 网络转发到 `tide-sync:8472`。

```bash
docker compose -f compose.yaml -f compose.https.yaml up -d
```

### 用 docker run 启动

单独用 Docker 启动时，用 `--env-file` 读取上面的域名和管理员凭据，文件中各变量的值直接写在等号后。宿主机端口由 `-p` 左侧的数字决定，下面使用 `8472`。

```bash
docker run -d --name tide-listen-server --restart unless-stopped \
  --env-file .env -p 8472:8472 \
  -e SYNC_TRUST_PROXY=1 \
  -v tidelisten-sync-data:/data \
  -v tidelisten-sync-secrets:/secrets \
  zjuzsj/tide-listen-server:latest
```

从已有部署切换启动方式时，把示例卷名换成原来的数据库卷和密钥卷。启动后访问 `https://sync.example.com/admin`，App 配对填写 `https://sync.example.com`。

### 通过 Dockerfile 自行构建

在源码的 `sync-server/` 目录准备好 `.env`，运行：

```bash
docker compose -f compose.yaml -f compose.build.yaml up -d --build
```

### 检查部署和升级

在 Compose 部署目录执行以下命令。健康检查正常时会返回 `{"status":"ok"}`。如果更改了宿主机端口，请同时替换命令中的 `8472`。反向代理的读取超时应长于 20 秒，以便客户端等待变更通知。

```bash
docker compose ps
docker compose logs --tail=50 tide-sync
curl -fsS http://127.0.0.1:8472/health
```

**升级已有实例**时，在原项目目录保留原来的 Compose 项目名、`.env`、数据库和密钥挂载。如果原配置里的服务名是 `sync`，请先用**旧配置**运行 `docker compose rm --stop --force sync`，移除旧容器并保留数据卷，再换用新模板。使用随附 Caddy 的实例将这一步改为 `docker compose -f compose.yaml -f compose.https.yaml rm --stop --force caddy sync`，然后一并更新 `compose.https.yaml` 和 `Caddyfile`。

使用默认的 `latest` 镜像时，执行：

```bash
docker compose pull tide-sync
docker compose up -d
```

使用随附的 Caddy 时，第二条命令换成上面的 [Caddy 启动命令](#使用随附的-caddy)，继续使用两份 Compose 文件。

### 已有绑定挂载

快速部署模板使用 Docker 命名卷。如果原配置使用宿主机目录，请保留原来的两行映射。例如：

```yaml
    volumes:
      - ./data:/data
      - ./secrets:/secrets
```

左侧是原数据所在的宿主机目录，右侧是容器内路径。镜像启动时会修正默认挂载位置的目录、数据库及其辅助文件、`sources.key` 的权限，然后以 `1000:1000` 运行服务。目录须可写。显式指定非 root 的 `user:` 时，需要由部署者管理挂载权限。

## 网页与设备

以下用 `https://sync.example.com` 表示你的服务根地址。

1. **创建账号。** 管理员访问 `/admin`，在「账号设置」创建普通用户。普通用户访问 `/login`，也可以从服务根地址进入。
2. **为设备生成配对码。** 在「配对与设备」选择有效期，点击「生成配对码」，从「待使用的配对码」复制。管理员可以在「为账号生成配对码」中选用户，也可以从「账号设置」里该用户旁的按钮进入。想让多台设备共享歌单，请为**同一个账号**生成配对码，每台设备用一个。
3. **在客户端配对。** 桌面打开「设置 → 数据同步 → 汐听同步服务」，手机和车机打开「设置 → 同步 → 汐听同步服务」。填写服务根地址和配对码。桌面配对后，若要同步本机歌单，选择「合并本机歌单并开启同步」。已有 WebDAV 歌单的用户请按下节步骤迁移。

配对码默认 10 分钟有效。可按分钟、小时、天、月或年设置，也可选「长期有效」。每个码只能用一次。刷新页面后，仍可复制尚未使用且未过期的配对码。旧版服务（0.13.4 以前）签发的有效码仍可使用，页面仅显示对应的账号和有效期。管理员可以在「账号设置」更改默认有效期，在「配对与设备」撤销已配对的设备。

### 从 WebDAV 迁移歌单

1. 先让所有仍在使用 WebDAV 的设备同步一次，确保各端的改动都已上传。保留桌面端原有的 WebDAV 地址，开启 WebDAV 歌单同步。
2. 在桌面端配对汐听同步服务后，选择「从 WebDAV 迁移」。桌面端先同步 WebDAV，再显示歌单名称和曲数。
3. 核对预览后点击「备份并迁移」。桌面端会保存本机 JSON 备份，把歌单与配对账号的歌单合并，并切换到汐听同步服务。完成后，为其余设备分别生成配对码，接入同一账号。

### 自动同步与音源

歌单发生改动时，客户端会自动上传。桌面端和处于前台的 Android 客户端会接收其他设备的变更。离线修改在恢复连接后上传，也可点击「立即同步」。音频和播放进度保存在原设备。

「同步音源」默认关闭。开启后，如果服务器尚未保存过音源，客户端会上传本机音源。如果服务器已有音源，设备会在首次开启时请你审核。确认后，同一配对下的服务器音源变更自动同步。升级客户端或重新打开开关会沿用确认结果，断开后重新配对则需独立确认。桌面端需启用 LX API Source Loader 扩展。

网页「音源」可调整优先级和启停状态。「添加音源」时可填写公网更新链接，也可上传 JS 文件。名称留空会使用脚本自带的名称。「编辑」可更换文件或修改更新链接。

## 备份与复原

只需备份歌单时，在网页「备份与恢复」下载当前账号的歌单 JSON，也可将它导入桌面端。网页恢复会替换该账号的歌单，并同步到已配对的设备。

迁移整台服务时，需要同时备份 SQLite 数据库和音源密钥。在部署目录执行以下命令，每次会创建独立的备份目录：

```bash
backup_dir=$(mktemp -d tide-backup-XXXXXXXX) &&
  docker compose exec -T --user node tide-sync node /app/server.mjs backup "/tmp/$backup_dir.sqlite" &&
  docker compose cp "tide-sync:/tmp/$backup_dir.sqlite" "$backup_dir/sync.sqlite" &&
  docker compose cp tide-sync:/secrets/sources.key "$backup_dir/sources.key" &&
  docker compose exec -T --user node tide-sync rm "/tmp/$backup_dir.sqlite"
```

恢复前先停止服务。将同一备份目录里的 `sync.sqlite` 和 `sources.key` 分别恢复到容器对应的 `/data/sync.sqlite` 和 `/secrets/sources.key`。若目标目录有旧数据库的 `sync.sqlite-wal` 或 `sync.sqlite-shm`，先移走再启动。备份中的密钥能解密音源脚本和更新链接，请妥善保管。
