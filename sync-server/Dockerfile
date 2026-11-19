FROM node:22-bookworm-slim
LABEL org.opencontainers.image.source="https://github.com/songjiezhang/tide-listen" \
      org.opencontainers.image.documentation="https://github.com/songjiezhang/tide-listen/tree/main/sync-server"
WORKDIR /app
COPY server.mjs admin.html admin.js site.css ./
RUN install -d -m 0700 -o node -g node /data /secrets
USER 0:0
EXPOSE 8472
ENTRYPOINT ["node", "/app/server.mjs", "--container"]
