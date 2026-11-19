# 汐听同步服务

同步服务和网页管理使用同一个 HTTPS 地址。账号、设备、歌单、音源和配对码保存在 Docker 数据卷中。

## Docker 部署

支持 Linux x64 和 ARM64。下载与客户端版本对应的源码，在 `sync-server/` 下创建 `.env`：

```dotenv
SYNC_DOMAIN=sync.example.com
```

将子域名解析到对外提供 HTTPS 的公网 IP。使用以下命令构建镜像并创建第一个管理员。命令会提示输入密码，密码通过标准输入传给容器。

```bash
docker compose build
read -rs -p '初始管理密码（至少 12 字符）: ' TIDE_INITIAL_PASSWORD
printf '\n'
printf '%s\n' "$TIDE_INITIAL_PASSWORD" | docker compose run --rm -T sync init admin
unset TIDE_INITIAL_PASSWORD
```

如使用 Release 中的预构建镜像，选对应架构的 `TideListen-Sync-linux-*.tar.gz`，运行 `docker load -i 文件名`，随后直接创建管理员。启动服务时可以加 `--no-build`。

群晖已有反向代理时，配置 HTTPS 子域名转发到 `http://127.0.0.1:8472`，并运行 `docker compose up -d`。容器仅将服务端口映射到宿主机回环地址。群晖 Container Manager 也可按 `compose.yaml` 创建项目，初始化账号时需使用 NAS 终端。后端通过反向代理取连接 IP，反向代理应正确附加客户端地址。

VPS 上可以使用预置的 Caddy 配置，在 DNS 指向服务器且 80、443 可达后运行：

```bash
docker compose -f compose.yaml -f compose.https.yaml up -d
```

如更改回环端口，可在 `.env` 调整 `SYNC_LOCAL_PORT`，同时修改已有反向代理的目标端口。

## 网页与设备

1. 打开 `https://sync.example.com/admin`，使用初始化的管理员账号和密码登录。
2. 在「账号设置」创建用户，在「配对与设备」为该用户生成配对码。默认 10 分钟有效。生成时可以单独选有效期，管理员也可以更新全局默认值。
3. 桌面、手机、车机在「设置 → 同步」填写 `https://sync.example.com` 和配对码。每台设备使用新生成的配对码。配对后可在网页撤销设备。
4. 桌面可合并现有本机歌单，或选择「从 WebDAV 迁移」。迁移先同步配置中的 WebDAV 歌单，再预览列表和曲目数量。确认后客户端写本机 JSON 备份，将歌单合并到配对用户的库，并切换到新同步方式。等待其他已连接 WebDAV 的设备上传未同步的改动，再配对至新服务。
5. 歌单同步自动运行。Android 在前台轮询，离线修改会保留并于下次联机上传。桌面和移动端都可手动同步。
6. 音源同步默认关闭。开启后，如果服务器音源库为空，客户端会上传本机洛雪 JS 音源。如果服务器已有变更，客户端会提示审核；确认后用服务器列表更新本机。网页支持上传脚本、设置更新链接、启停及排序。桌面端通过已启用的 LX API Source Loader 扩展使用洛雪音源。手机或车机的音源设置可回到「音源」编辑并同步。

同步服务管理歌单记录和选中的音源。音频和播放进度保存在原设备。

## 备份与复原

运行以下命令使用 SQLite 在线备份：

```bash
docker compose exec sync node /app/server.mjs backup /data/backup.sqlite
docker compose cp sync:/data/backup.sqlite ./backup.sqlite
docker compose cp sync:/secrets/sources.key ./sources.key
```

分别保管数据库和密钥。密钥可解密数据库里的音源脚本和更新链接。还原时先停止服务，恢复两个数据卷内的数据库和密钥，移除旧数据库对应的 `sync.sqlite-wal`、`sync.sqlite-shm`，再启动。确认恢复的是同一套数据库和密钥。
