FROM node:22-bookworm-slim
WORKDIR /app
COPY server.mjs admin.html admin.js site.css ./
RUN install -d -m 0700 -o node -g node /data /secrets
USER node
EXPOSE 8472
ENTRYPOINT ["node", "/app/server.mjs"]
